"""Encrage admin web console.

A separate FastAPI app (own port, own container) that manages accounts and shows health and
recent usage. It shares the SQLite database and media root with the API but is never mounted
on the user API. It sees only account metadata and opaque sizes, never media content or keys.

Auth: console admins (table console_admins, created with `manage.py set-console-admin`) sign in
with a password and get an opaque session cookie (HttpOnly, SameSite=Strict; stored as SHA-256).
State-changing requests also need the X-Encrage-Console header and a same-origin Origin header.
"""
from __future__ import annotations

import asyncio
import json
import secrets
import shutil
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import deque
from contextlib import asynccontextmanager, suppress
from datetime import timedelta
from pathlib import Path
from typing import Annotated
from urllib.parse import urlsplit

from fastapi import Depends, FastAPI, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import activity, admin
from .admin import AdminError
from .config import Settings, get_settings
from .database import CURRENT_SCHEMA_VERSION, Database
from .schemas import StrictModel
from .security import hash_password, hash_token, parse_utc, utc_iso, utc_now, verify_password


COOKIE_NAME = "encrage_console"
CSRF_HEADER = "x-encrage-console"
STATIC_DIR = Path(__file__).parent / "console_static"
STATIC_FILES = {"console.css": "text/css", "console.js": "text/javascript"}
DUMMY_ADMIN_HASH = hash_password("not-a-real-console-password")
USAGE_WINDOWS = (7, 30, 90)
PRUNE_INTERVAL_SECONDS = 3600
SECURITY_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    ),
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}


# ---------------------------------------------------------------------------
# Request bodies
# ---------------------------------------------------------------------------

class ConsoleLoginRequest(StrictModel):
    username: str = Field(min_length=1, max_length=255)
    password: str = Field(min_length=1, max_length=1024)


class CreateUserRequest(StrictModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=1024)
    quota_bytes: int = Field(gt=0, le=1 << 60)


class QuotaRequest(StrictModel):
    quota_bytes: int = Field(gt=0, le=1 << 60)


class PasswordRequest(StrictModel):
    password: str = Field(min_length=1, max_length=1024)


class ActiveRequest(StrictModel):
    active: bool


class DeleteUserRequest(StrictModel):
    confirm_username: str = Field(min_length=1, max_length=255)


# ---------------------------------------------------------------------------
# Login throttling (in-memory; the console runs as a single process)
# ---------------------------------------------------------------------------

class LoginThrottle:
    def __init__(self, per_client: int = 5, overall: int = 30, window_seconds: int = 900):
        self.per_client = per_client
        self.overall = overall
        self.window = window_seconds
        self._failures: dict[str, deque[float]] = {}
        self._all: deque[float] = deque()
        self._lock = threading.Lock()

    def _trim(self, entries: deque[float], now: float) -> None:
        while entries and entries[0] <= now - self.window:
            entries.popleft()

    def retry_after(self, client: str) -> int:
        now = time.monotonic()
        with self._lock:
            entries = self._failures.get(client, deque())
            self._trim(entries, now)
            self._trim(self._all, now)
            if len(entries) >= self.per_client:
                return int(entries[0] + self.window - now) + 1
            if len(self._all) >= self.overall:
                return int(self._all[0] + self.window - now) + 1
            return 0

    def failure(self, client: str) -> None:
        now = time.monotonic()
        with self._lock:
            self._failures.setdefault(client, deque()).append(now)
            self._all.append(now)

    def success(self, client: str) -> None:
        with self._lock:
            self._failures.pop(client, None)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _error(status_code: int, code: str, message: str, details: object | None = None) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"error": {"code": code, "message": message, "details": details}},
    )


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return 0


def _day_series(days: int) -> list[str]:
    today = utc_now().date()
    return [(today - timedelta(days=offset)).isoformat() for offset in range(days - 1, -1, -1)]


def _event_payload(row) -> dict:
    details = None
    if row["details"]:
        with suppress(ValueError):
            details = json.loads(row["details"])
    return {
        "event_id": row["event_id"],
        "occurred_at": row["occurred_at"],
        "event_type": row["event_type"],
        "user_id": row["user_id"],
        "username": row["username"],
        "actor": row["actor"],
        "bytes": row["bytes"],
        "client_ip": row["client_ip"],
        "details": details,
    }


def current_admin(request: Request) -> dict:
    database: Database = request.app.state.database
    token = request.cookies.get(COOKIE_NAME)
    if not token or len(token) > 128:
        raise StarletteHTTPException(401, {"code": "not_signed_in", "message": "Sign in required", "details": None})
    session_hash = hash_token(token)
    with database.connection() as connection:
        row = connection.execute(
            """
            SELECT console_sessions.session_hash, console_sessions.expires_at, console_sessions.last_seen_at,
                   console_admins.admin_id, console_admins.username
            FROM console_sessions
            JOIN console_admins ON console_admins.admin_id = console_sessions.admin_id
            WHERE console_sessions.session_hash = ? AND console_sessions.revoked_at IS NULL
            """,
            (session_hash,),
        ).fetchone()
    if row is None or parse_utc(row["expires_at"]) <= utc_now():
        raise StarletteHTTPException(401, {"code": "not_signed_in", "message": "Session expired", "details": None})
    if parse_utc(row["last_seen_at"]) < utc_now() - timedelta(minutes=5):
        with database.transaction() as connection:
            connection.execute(
                "UPDATE console_sessions SET last_seen_at = ? WHERE session_hash = ?", (utc_iso(), session_hash)
            )
    return {"admin_id": row["admin_id"], "username": row["username"], "session_hash": session_hash}


Admin = Annotated[dict, Depends(current_admin)]


def create_console_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or get_settings()
    database = Database(resolved_settings.database_path, resolved_settings.media_root)
    throttle = LoginThrottle()
    started_at = utc_now()

    async def prune_periodically() -> None:
        while True:
            await asyncio.sleep(PRUNE_INTERVAL_SECONDS)
            with suppress(Exception):
                await asyncio.to_thread(activity.prune_events, database, resolved_settings.activity_retention_days)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        resolved_settings.media_root.mkdir(parents=True, exist_ok=True)
        database.initialize()
        activity.prune_events(database, resolved_settings.activity_retention_days)
        task = asyncio.create_task(prune_periodically())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    app = FastAPI(title="Encrage Console", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = resolved_settings
    app.state.database = database
    app.state.throttle = throttle

    # -- error handling ------------------------------------------------------

    @app.exception_handler(AdminError)
    async def admin_error_handler(_: Request, exc: AdminError):
        return _error(exc.status_code, exc.code, exc.message, exc.details)

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(_: Request, exc: StarletteHTTPException):
        if isinstance(exc.detail, dict) and "code" in exc.detail:
            return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})
        return _error(exc.status_code, "http_error", str(exc.detail))

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(_: Request, exc: RequestValidationError):
        details = [{"location": list(e["loc"]), "message": e["msg"]} for e in exc.errors()]
        return _error(422, "validation_error", "Request validation failed", details)

    # -- transport security, CSRF and headers ---------------------------------

    @app.middleware("http")
    async def console_middleware(request: Request, call_next):
        if resolved_settings.environment == "production" and request.url.scheme != "https":
            return _error(400, "https_required", "HTTPS is required")
        if request.method not in ("GET", "HEAD", "OPTIONS") and request.url.path.startswith("/api/"):
            if request.headers.get(CSRF_HEADER) != "1":
                return _error(403, "csrf_rejected", "Missing console request header")
            origin = request.headers.get("origin")
            if origin and urlsplit(origin).netloc != request.headers.get("host", ""):
                return _error(403, "csrf_rejected", "Cross-origin request rejected")
        response = await call_next(request)
        for name, value in SECURITY_HEADERS.items():
            response.headers.setdefault(name, value)
        return response

    # -- authentication --------------------------------------------------------

    def actor(current: dict) -> str:
        return f"admin:{current['username']}"

    @app.get("/healthz", include_in_schema=False)
    def healthz() -> dict:
        return {"status": "ok"}

    @app.get("/", include_in_schema=False)
    def index() -> FileResponse:
        return FileResponse(STATIC_DIR / "index.html", media_type="text/html")

    @app.get("/static/{name}", include_in_schema=False)
    def static(name: str) -> FileResponse:
        if name not in STATIC_FILES:
            raise StarletteHTTPException(404, "Not found")
        return FileResponse(STATIC_DIR / name, media_type=STATIC_FILES[name])

    @app.get("/api/session")
    def session(request: Request) -> dict:
        with database.connection() as connection:
            admins = connection.execute("SELECT COUNT(*) FROM console_admins").fetchone()[0]
        try:
            current = current_admin(request)
        except StarletteHTTPException:
            current = None
        return {
            "authenticated": current is not None,
            "admin": current["username"] if current else None,
            "setup_required": admins == 0,
            "environment": resolved_settings.environment,
            "secure_transport": request.url.scheme == "https",
        }

    @app.post("/api/login")
    def login(body: ConsoleLoginRequest, request: Request) -> Response:
        client = _client_ip(request)
        wait = throttle.retry_after(client)
        if wait:
            return _error(429, "too_many_attempts", f"Too many failed sign-ins. Try again in {wait} seconds.",
                          {"retry_after_seconds": wait})
        with database.connection() as connection:
            row = connection.execute(
                "SELECT admin_id, username, password_hash FROM console_admins WHERE username = ? COLLATE NOCASE",
                (body.username.strip(),),
            ).fetchone()
        valid = verify_password(row["password_hash"] if row else DUMMY_ADMIN_HASH, body.password)
        if row is None or not valid:
            throttle.failure(client)
            activity.record_event_now(
                database, activity.ADMIN_LOGIN_FAILED, actor="console", client_ip=client,
                details={"known_admin": row is not None},
            )
            return _error(401, "invalid_credentials", "Invalid username or password")
        throttle.success(client)
        token = secrets.token_urlsafe(32)
        now = utc_now()
        expires = now + timedelta(hours=resolved_settings.console_session_hours)
        with database.transaction(immediate=True) as connection:
            connection.execute(
                """
                INSERT INTO console_sessions(session_hash, admin_id, created_at, expires_at, last_seen_at, client_ip)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (hash_token(token), row["admin_id"], utc_iso(now), utc_iso(expires), utc_iso(now), client),
            )
            activity.record_event(connection, activity.ADMIN_LOGIN, actor=f"admin:{row['username']}", client_ip=client)
        response = JSONResponse({"admin": row["username"]})
        response.set_cookie(
            COOKIE_NAME, token, max_age=resolved_settings.console_session_hours * 3600, httponly=True,
            samesite="strict", secure=request.url.scheme == "https", path="/",
        )
        return response

    @app.post("/api/logout")
    def logout(request: Request) -> Response:
        try:
            current = current_admin(request)
        except StarletteHTTPException:
            current = None
        if current:
            with database.transaction(immediate=True) as connection:
                connection.execute(
                    "UPDATE console_sessions SET revoked_at = ? WHERE session_hash = ?",
                    (utc_iso(), current["session_hash"]),
                )
                activity.record_event(connection, activity.ADMIN_LOGOUT, actor=actor(current),
                                      client_ip=_client_ip(request))
        response = Response(status_code=204)
        response.delete_cookie(COOKIE_NAME, path="/")
        return response

    # -- health ------------------------------------------------------------------

    def api_health() -> dict:
        url = f"{resolved_settings.console_api_url}/health"
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(url, timeout=3) as reply:  # noqa: S310 - fixed, admin-configured URL
                ok = reply.status == 200 and json.loads(reply.read(1024)).get("status") == "ok"
            return {"status": "ok" if ok else "degraded", "latency_ms": round((time.perf_counter() - started) * 1000, 1),
                    "url": url, "error": None}
        except (urllib.error.URLError, OSError, ValueError) as exc:
            reason = getattr(exc, "reason", exc)
            return {"status": "down", "latency_ms": None, "url": url, "error": str(reason)[:200]}

    def collect_health() -> dict:
        api = api_health()
        db_path = resolved_settings.database_path
        try:
            disk = shutil.disk_usage(resolved_settings.media_root)
            storage = {"total_bytes": disk.total, "used_bytes": disk.used, "free_bytes": disk.free}
        except OSError:
            storage = {"total_bytes": None, "used_bytes": None, "free_bytes": None}
        stale_cutoff = utc_iso(utc_now() - timedelta(hours=resolved_settings.upload_ttl_hours))
        with database.connection() as connection:
            def scalar(sql: str, params: tuple = ()) -> int:
                return int(connection.execute(sql, params).fetchone()[0] or 0)

            schema_version = scalar("SELECT MAX(version) FROM schema_migrations")
            queues = {
                "pending_file_deletions": scalar("SELECT COUNT(*) FROM file_delete_queue"),
                "uploads_in_progress": scalar("SELECT COUNT(*) FROM upload_sessions WHERE state = 'uploading'"),
                "uploads_finalizing": scalar("SELECT COUNT(*) FROM upload_sessions WHERE state = 'finalizing'"),
                "stale_uploads": scalar(
                    "SELECT COUNT(*) FROM upload_sessions WHERE state = 'uploading' AND updated_at < ?", (stale_cutoff,)
                ),
                "reserved_bytes": scalar("SELECT COALESCE(SUM(stored_size_bytes), 0) FROM upload_sessions"),
                "legacy_migration_issues": scalar("SELECT COUNT(*) FROM legacy_migration_issues"),
            }
            totals = {
                "users": scalar("SELECT COUNT(*) FROM users"),
                "active_users": scalar("SELECT COUNT(*) FROM users WHERE is_active = 1"),
                "media": scalar("SELECT COUNT(*) FROM media"),
                "videos": scalar("SELECT COUNT(*) FROM media WHERE media_type = 'video'"),
                "stored_bytes": scalar("SELECT COALESCE(SUM(space_used_bytes), 0) FROM users"),
                "allocated_bytes": scalar("SELECT COALESCE(SUM(space_allocated_bytes), 0) FROM users"),
            }
            near_quota = [
                row["username"]
                for row in connection.execute(
                    "SELECT username FROM users WHERE space_allocated_bytes > 0 "
                    "AND space_used_bytes * 100 >= space_allocated_bytes * 90 ORDER BY username"
                )
            ]
        warnings: list[dict] = []
        if api["status"] != "ok":
            warnings.append({"level": "critical", "message": f"API is not responding ({api['error'] or 'bad reply'})"})
        if storage["total_bytes"] and storage["free_bytes"] / storage["total_bytes"] < 0.10:
            warnings.append({"level": "critical", "message": "Less than 10% disk space is free"})
        if queues["stale_uploads"]:
            warnings.append({"level": "info", "message": f"{queues['stale_uploads']} abandoned upload(s) will be cleaned up"})
        if queues["pending_file_deletions"]:
            warnings.append({"level": "warning",
                             "message": f"{queues['pending_file_deletions']} file deletion(s) are waiting to be retried"})
        if queues["legacy_migration_issues"]:
            warnings.append({"level": "warning",
                             "message": f"{queues['legacy_migration_issues']} legacy item(s) failed migration"})
        for name in near_quota:
            warnings.append({"level": "warning", "message": f"{name} is using 90% or more of their quota"})
        if resolved_settings.environment != "production":
            warnings.append({"level": "info", "message": "Running without HTTPS enforcement (ENCRAGE_ENV="
                             f"{resolved_settings.environment}). Keep the ports on your LAN only."})
        status = "critical" if any(w["level"] == "critical" for w in warnings) else (
            "warning" if any(w["level"] == "warning" for w in warnings) else "ok")
        return {
            "status": status,
            "checked_at": utc_iso(),
            "api": api,
            "database": {
                "size_bytes": _file_size(db_path),
                "wal_bytes": _file_size(db_path.with_name(db_path.name + "-wal")),
                "schema_version": schema_version,
                "expected_schema_version": CURRENT_SCHEMA_VERSION,
            },
            "storage": storage,
            "queues": queues,
            "totals": totals,
            "console": {
                "started_at": utc_iso(started_at),
                "uptime_seconds": int((utc_now() - started_at).total_seconds()),
                "environment": resolved_settings.environment,
                "activity_retention_days": resolved_settings.activity_retention_days,
            },
            "warnings": warnings,
        }

    # -- usage history --------------------------------------------------------------

    def collect_usage(days: int) -> dict:
        series_days = _day_series(days)
        cutoff = series_days[0] + "T00:00:00Z"
        empty = lambda: {day: 0 for day in series_days}  # noqa: E731
        uploads, upload_bytes = empty(), empty()
        logins, failed, deletions, deleted_bytes = empty(), empty(), empty(), empty()
        with database.connection() as connection:
            for row in connection.execute(
                "SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS n, COALESCE(SUM(stored_size_bytes), 0) AS b "
                "FROM media WHERE created_at >= ? GROUP BY day", (cutoff,)
            ):
                if row["day"] in uploads:
                    uploads[row["day"]], upload_bytes[row["day"]] = row["n"], row["b"]
            for row in connection.execute(
                "SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS n FROM sessions WHERE created_at >= ? GROUP BY day",
                (cutoff,),
            ):
                if row["day"] in logins:
                    logins[row["day"]] = row["n"]
            for row in connection.execute(
                "SELECT substr(occurred_at, 1, 10) AS day, event_type, COUNT(*) AS n, COALESCE(SUM(bytes), 0) AS b "
                "FROM activity_events WHERE occurred_at >= ? AND event_type IN (?, ?, ?) GROUP BY day, event_type",
                (cutoff, activity.MEDIA_DELETED, activity.LOGIN_FAILED, activity.ADMIN_LOGIN_FAILED),
            ):
                if row["day"] not in deletions:
                    continue
                if row["event_type"] == activity.MEDIA_DELETED:
                    deletions[row["day"]], deleted_bytes[row["day"]] = row["n"], row["b"]
                else:
                    failed[row["day"]] += row["n"]
            top_uploaders = [
                {"username": row["username"], "uploads": row["n"], "bytes": row["b"]}
                for row in connection.execute(
                    """
                    SELECT users.username, COUNT(*) AS n, COALESCE(SUM(media.stored_size_bytes), 0) AS b
                    FROM media JOIN users ON users.user_id = media.user_id
                    WHERE media.created_at >= ?
                    GROUP BY users.user_id ORDER BY b DESC LIMIT 5
                    """,
                    (cutoff,),
                )
            ]
            active_users = connection.execute(
                """
                SELECT COUNT(DISTINCT user_id) FROM (
                    SELECT user_id FROM sessions WHERE last_seen_at >= ? OR created_at >= ?
                    UNION SELECT user_id FROM media WHERE created_at >= ?
                )
                """,
                (cutoff, cutoff, cutoff),
            ).fetchone()[0]
            tracked_since = connection.execute("SELECT MIN(occurred_at) FROM activity_events").fetchone()[0]
        daily = [
            {
                "day": day,
                "uploads": uploads[day],
                "upload_bytes": upload_bytes[day],
                "deletions": deletions[day],
                "deleted_bytes": deleted_bytes[day],
                "logins": logins[day],
                "failed_logins": failed[day],
            }
            for day in series_days
        ]
        return {
            "days": days,
            "daily": daily,
            "totals": {
                "uploads": sum(uploads.values()),
                "upload_bytes": sum(upload_bytes.values()),
                "deletions": sum(deletions.values()),
                "deleted_bytes": sum(deleted_bytes.values()),
                "logins": sum(logins.values()),
                "failed_logins": sum(failed.values()),
                "active_users": int(active_users or 0),
            },
            "top_uploaders": top_uploaders,
            # Deletions and failed sign-ins are only known from the activity log, which starts empty.
            "events_tracked_since": tracked_since,
        }

    def recent_events(limit: int, event_type: str | None = None, before_id: int | None = None) -> list[dict]:
        clauses, params = [], []
        if event_type:
            clauses.append("event_type = ?")
            params.append(event_type)
        if before_id:
            clauses.append("event_id < ?")
            params.append(before_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with database.connection() as connection:
            rows = connection.execute(
                f"SELECT * FROM activity_events {where} ORDER BY event_id DESC LIMIT ?", (*params, limit)
            ).fetchall()
        return [_event_payload(row) for row in rows]

    @app.get("/api/overview")
    def overview(_: Admin, days: Annotated[int, Query()] = 30) -> dict:
        if days not in USAGE_WINDOWS:
            raise AdminError(422, "invalid_window", f"days must be one of {list(USAGE_WINDOWS)}")
        return {"health": collect_health(), "usage": collect_usage(days), "recent_events": recent_events(12)}

    @app.get("/api/health")
    def health(_: Admin) -> dict:
        return collect_health()

    @app.post("/api/health/integrity")
    def integrity(_: Admin) -> dict:
        started = time.perf_counter()
        with database.connection() as connection:
            results = [row[0] for row in connection.execute("PRAGMA quick_check(20)")]
        return {"ok": results == ["ok"], "results": results,
                "duration_ms": round((time.perf_counter() - started) * 1000, 1), "checked_at": utc_iso()}

    @app.get("/api/activity")
    def activity_feed(
        _: Admin,
        limit: Annotated[int, Query(ge=1, le=500)] = 100,
        event_type: Annotated[str | None, Query(max_length=64)] = None,
        before_id: Annotated[int | None, Query(ge=1)] = None,
    ) -> dict:
        if event_type and event_type not in activity.EVENT_TYPES:
            raise AdminError(422, "invalid_event_type", "Unknown event type")
        events = recent_events(limit, event_type, before_id)
        return {"events": events, "event_types": list(activity.EVENT_TYPES),
                "next_before_id": events[-1]["event_id"] if len(events) == limit else None}

    # -- user management --------------------------------------------------------------

    @app.get("/api/users")
    def users(_: Admin) -> dict:
        return {"users": admin.list_users(database)}

    @app.post("/api/users", status_code=201)
    def create_user(body: CreateUserRequest, request: Request, current: Admin) -> dict:
        user_id = admin.create_user(database, body.username, body.password, body.quota_bytes,
                                    actor=actor(current), client_ip=_client_ip(request))
        return {"user_id": user_id}

    @app.put("/api/users/{user_id}/quota")
    def update_quota(user_id: str, body: QuotaRequest, request: Request, current: Admin) -> dict:
        admin.set_user_quota(database, user_id, body.quota_bytes, actor=actor(current), client_ip=_client_ip(request))
        return {"quota_bytes": body.quota_bytes}

    @app.post("/api/users/{user_id}/password")
    def reset_password(user_id: str, body: PasswordRequest, request: Request, current: Admin) -> dict:
        revoked = admin.set_user_password(database, user_id, body.password, actor=actor(current),
                                          client_ip=_client_ip(request))
        return {"sessions_revoked": revoked}

    @app.put("/api/users/{user_id}/active")
    def update_active(user_id: str, body: ActiveRequest, request: Request, current: Admin) -> dict:
        admin.set_user_active(database, user_id, body.active, actor=actor(current), client_ip=_client_ip(request))
        return {"active": body.active}

    @app.post("/api/users/{user_id}/revoke-sessions")
    def revoke_sessions(user_id: str, request: Request, current: Admin) -> dict:
        return {"sessions_revoked": admin.revoke_user_sessions(database, user_id, actor=actor(current),
                                                               client_ip=_client_ip(request))}

    @app.delete("/api/users/{user_id}")
    def delete_user(user_id: str, body: DeleteUserRequest, request: Request, current: Admin) -> dict:
        try:
            canonical_id = str(uuid.UUID(user_id))
        except ValueError as exc:
            raise AdminError(404, "user_not_found", "User not found") from exc
        return admin.delete_user(database, resolved_settings, canonical_id, confirm_username=body.confirm_username,
                                 actor=actor(current), client_ip=_client_ip(request))

    return app


app = create_console_app()
