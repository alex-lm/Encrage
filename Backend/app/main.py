from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import re
import shutil
import sqlite3
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Literal

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, Path as PathParameter, Query, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import activity
from .config import Settings, get_settings
from .database import Database
from .schemas import (
    ChangePasswordRequest,
    ChunkUploadResponse,
    ErrorResponse,
    FingerprintCheckRequest,
    FingerprintCheckResponse,
    LoginRequest,
    MediaListResponse,
    MediaResponse,
    RefreshRequest,
    StorageResponse,
    ThumbnailUploadResponse,
    TokenResponse,
    UploadCreateRequest,
    UploadCreateResponse,
    UploadStatusResponse,
    UserResponse,
)
from .security import (
    decode_access_token,
    hash_password,
    hash_token,
    issue_access_token,
    issue_refresh_token,
    parse_utc,
    utc_iso,
    utc_now,
    verify_password,
)
from .storage import (
    final_media_directory,
    install_file,
    iter_file,
    new_temporary_file,
    promoted_location,
    safe_path,
    upload_chunk_location,
    upload_directory,
    upload_thumbnail_location,
)


LOGGER = logging.getLogger("encrage")
bearer_scheme = HTTPBearer(auto_error=False)
DUMMY_PASSWORD_HASH = hash_password("not-a-real-password")
SUPPORTED_ENCRYPTION_VERSIONS = {1}
FINGERPRINT_PATTERN = re.compile(r"^[A-Za-z0-9+/=_-]{32,512}$")
ERROR_RESPONSES = {
    400: {"model": ErrorResponse},
    401: {"model": ErrorResponse},
    404: {"model": ErrorResponse},
    409: {"model": ErrorResponse},
    413: {"model": ErrorResponse},
    422: {"model": ErrorResponse},
}


class AuthenticatedUser(BaseModel):
    user_id: str
    username: str
    session_id: str
    created_at: str


def api_error(status_code: int, code: str, message: str, *, details: object | None = None) -> HTTPException:
    headers = {"WWW-Authenticate": "Bearer"} if status_code == 401 else None
    return HTTPException(
        status_code=status_code,
        detail={"code": code, "message": message, "details": details},
        headers=headers,
    )


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _database(request: Request) -> Database:
    return request.app.state.database


def _as_utc_iso(value: datetime | None, fallback: str | None = None) -> str:
    if value is None:
        return fallback or utc_iso()
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return utc_iso(value)


def _media_payload(row: sqlite3.Row) -> dict:
    return {
        "media_id": row["media_id"],
        "media_fingerprint": row["media_fingerprint"],
        "media_type": row["media_type"],
        "mime_type": row["mime_type"],
        "plaintext_size_bytes": row["plaintext_size_bytes"],
        "stored_size_bytes": row["stored_size_bytes"],
        "datetime_added": row["datetime_added"],
        "datetime_taken": row["datetime_taken"],
        "width": row["width"],
        "height": row["height"],
        "duration_seconds": row["duration_seconds"],
        "private_metadata_ciphertext": row["private_metadata_ciphertext"],
        "thumbnail_available": bool(row["thumbnail_available"]),
        "encryption_version": row["encryption_version"],
        "total_chunks": row["total_chunks"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def _token_response(settings: Settings, access, refresh) -> dict:
    return {
        "access_token": access.token,
        "refresh_token": refresh.token,
        "token_type": "bearer",
        "expires_in": settings.access_token_minutes * 60,
    }


def _store_refresh_token(
    connection: sqlite3.Connection,
    *,
    user_id: str,
    session_id: str,
    refresh,
) -> None:
    connection.execute(
        """
        INSERT INTO refresh_tokens (
            token_id, session_id, user_id, token_hash, issued_at, expires_at,
            revoked_at, replaced_by_token_id
        ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
        """,
        (
            refresh.token_id,
            session_id,
            user_id,
            hash_token(refresh.token),
            utc_iso(),
            utc_iso(refresh.expires_at),
        ),
    )


def authenticated_user(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
) -> AuthenticatedUser:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise api_error(401, "access_token_required", "A bearer access token is required")
    try:
        payload = decode_access_token(_settings(request), credentials.credentials)
    except jwt.ExpiredSignatureError as exc:
        raise api_error(401, "access_token_expired", "The access token has expired") from exc
    except jwt.PyJWTError as exc:
        raise api_error(401, "invalid_access_token", "The access token is invalid") from exc
    if payload.get("type") != "access":
        raise api_error(401, "invalid_access_token", "The access token is invalid")
    with _database(request).connection() as connection:
        row = connection.execute(
            """
            SELECT users.user_id, users.username, users.created_at, users.is_active,
                   sessions.session_id, sessions.revoked_at
            FROM sessions
            JOIN users ON users.user_id = sessions.user_id
            WHERE sessions.session_id = ? AND sessions.user_id = ?
            """,
            (payload["sid"], payload["sub"]),
        ).fetchone()
    if row is None or row["revoked_at"] is not None or not row["is_active"]:
        raise api_error(401, "invalid_access_token", "The access token is invalid or revoked")
    return AuthenticatedUser(
        user_id=row["user_id"],
        username=row["username"],
        session_id=row["session_id"],
        created_at=row["created_at"],
    )


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


def _owned_upload(connection: sqlite3.Connection, user_id: str, upload_id: str) -> sqlite3.Row:
    row = connection.execute(
        "SELECT * FROM upload_sessions WHERE upload_id = ? AND user_id = ?",
        (upload_id, user_id),
    ).fetchone()
    if row is None:
        raise api_error(404, "upload_not_found", "Upload session not found")
    return row


def _owned_media(connection: sqlite3.Connection, user_id: str, media_id: str) -> sqlite3.Row:
    row = connection.execute(
        "SELECT * FROM media WHERE media_id = ? AND user_id = ?",
        (media_id, user_id),
    ).fetchone()
    if row is None:
        raise api_error(404, "media_not_found", "Media not found")
    return row


def _reserved_bytes(connection: sqlite3.Connection, user_id: str) -> int:
    return int(
        connection.execute(
            "SELECT COALESCE(SUM(stored_size_bytes), 0) FROM upload_sessions WHERE user_id = ?",
            (user_id,),
        ).fetchone()[0]
    )


async def _stream_request_to_file(request: Request, path: Path, maximum: int, label: str) -> tuple[int, str]:
    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            declared = int(content_length)
        except ValueError as exc:
            raise api_error(400, "invalid_content_length", "Content-Length must be an integer") from exc
        if declared > maximum:
            raise api_error(413, f"{label}_too_large", f"The {label} exceeds the configured size limit")
        if declared <= 0:
            raise api_error(422, f"empty_{label}", f"The {label} body must not be empty")
    size = 0
    digest = hashlib.sha256()
    with path.open("wb") as output:
        async for chunk in request.stream():
            if not chunk:
                continue
            size += len(chunk)
            if size > maximum:
                raise api_error(413, f"{label}_too_large", f"The {label} exceeds the configured size limit")
            digest.update(chunk)
            output.write(chunk)
        output.flush()
        os.fsync(output.fileno())
    if size == 0:
        raise api_error(422, f"empty_{label}", f"The {label} body must not be empty")
    return size, digest.hexdigest()


def _queue_path(connection: sqlite3.Connection, relative_location: str) -> str:
    queue_id = str(uuid.uuid4())
    connection.execute(
        "INSERT INTO file_delete_queue(queue_id, storage_location, queued_at) VALUES (?, ?, ?)",
        (queue_id, relative_location, utc_iso()),
    )
    return queue_id


def process_delete_queue(database: Database, settings: Settings, queue_ids: list[str] | None = None) -> int:
    with database.connection() as connection:
        if queue_ids:
            placeholders = ",".join("?" for _ in queue_ids)
            rows = connection.execute(
                f"SELECT queue_id, storage_location FROM file_delete_queue WHERE queue_id IN ({placeholders})",
                queue_ids,
            ).fetchall()
        else:
            rows = connection.execute("SELECT queue_id, storage_location FROM file_delete_queue").fetchall()
    removed = 0
    for row in rows:
        try:
            target = safe_path(settings.media_root, row["storage_location"])
            if target.is_dir():
                shutil.rmtree(target)
            else:
                target.unlink(missing_ok=True)
        except OSError:
            LOGGER.warning("Deferred deletion remains queued", extra={"queue_id": row["queue_id"]})
            continue
        with database.transaction(immediate=True) as connection:
            connection.execute("DELETE FROM file_delete_queue WHERE queue_id = ?", (row["queue_id"],))
        removed += 1
    return removed


def recover_interrupted_finalizations(database: Database, settings: Settings) -> int:
    recovered = 0
    with database.connection() as connection:
        sessions = connection.execute(
            """
            SELECT upload_id, media_id, user_id
            FROM upload_sessions
            WHERE NOT EXISTS (
                SELECT 1 FROM media WHERE media.media_id = upload_sessions.media_id
            )
            """
        ).fetchall()
    for session in sessions:
        source = upload_directory(settings.media_root, session["user_id"], session["upload_id"])
        destination = final_media_directory(settings.media_root, session["user_id"], session["media_id"])
        if not source.exists() and destination.is_dir():
            source.parent.mkdir(parents=True, exist_ok=True)
            os.replace(destination, source)
            recovered += 1
    return recovered


def cleanup_abandoned_uploads(
    database: Database,
    settings: Settings,
    *,
    now: datetime | None = None,
) -> int:
    cutoff = (now or utc_now()) - timedelta(hours=settings.upload_ttl_hours)
    queue_ids: list[str] = []
    with database.transaction(immediate=True) as connection:
        rows = connection.execute(
            "SELECT upload_id, user_id FROM upload_sessions WHERE state = 'uploading' AND updated_at < ?",
            (utc_iso(cutoff),),
        ).fetchall()
        for row in rows:
            relative_directory = f"{row['user_id']}/.uploads/{row['upload_id']}"
            queue_ids.append(_queue_path(connection, relative_directory))
            connection.execute(
                "DELETE FROM upload_sessions WHERE upload_id = ? AND user_id = ?",
                (row["upload_id"], row["user_id"]),
            )
    if queue_ids:
        process_delete_queue(database, settings, queue_ids)
    return len(queue_ids)


def _encode_cursor(sort_by: str, sort_order: str, value: object, media_id: str) -> str:
    payload = json.dumps(
        {"sort_by": sort_by, "sort_order": sort_order, "value": value, "media_id": media_id},
        separators=(",", ":"),
    ).encode("utf-8")
    return base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")


def _decode_cursor(cursor: str, sort_by: str, sort_order: str) -> tuple[object, str]:
    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")))
        if payload["sort_by"] != sort_by or payload["sort_order"] != sort_order:
            raise ValueError
        return payload["value"], str(uuid.UUID(payload["media_id"]))
    except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        raise api_error(400, "invalid_cursor", "The pagination cursor is invalid for this query") from exc


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved_settings = settings or get_settings()
    database = Database(resolved_settings.database_path, resolved_settings.media_root)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        resolved_settings.media_root.mkdir(parents=True, exist_ok=True)
        database.initialize()
        recover_interrupted_finalizations(database, resolved_settings)
        process_delete_queue(database, resolved_settings)
        cleanup_abandoned_uploads(database, resolved_settings)
        activity.prune_events(database, resolved_settings.activity_retention_days)
        yield

    app = FastAPI(
        title="Encrage Encrypted Media API",
        version="2.0.0",
        description=(
            "Authenticated storage and indexing for opaque client-encrypted media. "
            "The service never receives the user's media encryption key and never decodes media."
        ),
        lifespan=lifespan,
    )
    app.state.settings = resolved_settings
    app.state.database = database

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(_: Request, exc: StarletteHTTPException):
        if isinstance(exc.detail, dict) and "code" in exc.detail:
            detail = exc.detail
        else:
            detail = {"code": "http_error", "message": str(exc.detail), "details": None}
        return JSONResponse(status_code=exc.status_code, content={"error": detail}, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(_: Request, exc: RequestValidationError):
        details = [
            {"location": list(error["loc"]), "message": error["msg"], "type": error["type"]}
            for error in exc.errors()
        ]
        return JSONResponse(
            status_code=422,
            content={"error": {"code": "validation_error", "message": "Request validation failed", "details": details}},
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(_: Request, exc: Exception):
        LOGGER.exception("Unhandled API error", exc_info=exc)
        return JSONResponse(
            status_code=500,
            content={"error": {"code": "internal_error", "message": "An internal server error occurred", "details": None}},
        )

    @app.middleware("http")
    async def security_middleware(request: Request, call_next):
        if resolved_settings.environment == "production" and request.url.scheme != "https":
            return JSONResponse(
                status_code=400,
                content={"error": {"code": "https_required", "message": "HTTPS is required", "details": None}},
            )
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/health", include_in_schema=False)
    def health() -> dict:
        return {"status": "ok"}

    @app.post("/auth/login", response_model=TokenResponse, responses=ERROR_RESPONSES)
    def login(body: LoginRequest, request: Request) -> dict:
        with database.connection() as connection:
            user = connection.execute(
                "SELECT user_id, username, password_hash, is_active FROM users WHERE username = ? COLLATE NOCASE",
                (body.username,),
            ).fetchone()
        password_valid = verify_password(user["password_hash"] if user else DUMMY_PASSWORD_HASH, body.password)
        if user is None or not password_valid or not user["is_active"]:
            # Only name accounts that exist: a mistyped "username" may be a password.
            activity.record_event_now(
                database, activity.LOGIN_FAILED, actor="user",
                user_id=user["user_id"] if user else None,
                username=user["username"] if user else None,
                client_ip=_client_ip(request),
                details=None if user else {"unknown_user": True},
            )
            raise api_error(401, "invalid_credentials", "Invalid username or password")
        session_id = str(uuid.uuid4())
        access = issue_access_token(resolved_settings, user["user_id"], session_id)
        refresh_token = issue_refresh_token(resolved_settings)
        now = utc_iso()
        with database.transaction(immediate=True) as connection:
            connection.execute(
                "INSERT INTO sessions(session_id, user_id, created_at, last_seen_at, revoked_at) VALUES (?, ?, ?, ?, NULL)",
                (session_id, user["user_id"], now, now),
            )
            _store_refresh_token(
                connection,
                user_id=user["user_id"],
                session_id=session_id,
                refresh=refresh_token,
            )
            activity.record_event(
                connection, activity.LOGIN, actor="user", user_id=user["user_id"],
                username=user["username"], client_ip=_client_ip(request),
            )
        return _token_response(resolved_settings, access, refresh_token)

    @app.post("/auth/refresh", response_model=TokenResponse, responses=ERROR_RESPONSES)
    def refresh(body: RefreshRequest) -> dict:
        token_digest = hash_token(body.refresh_token)
        new_refresh = issue_refresh_token(resolved_settings)
        rejection_message: str | None = None
        access = None
        with database.transaction(immediate=True) as connection:
            row = connection.execute(
                """
                SELECT refresh_tokens.*, sessions.revoked_at AS session_revoked_at, users.is_active
                FROM refresh_tokens
                JOIN sessions ON sessions.session_id = refresh_tokens.session_id
                JOIN users ON users.user_id = refresh_tokens.user_id
                WHERE refresh_tokens.token_hash = ?
                """,
                (token_digest,),
            ).fetchone()
            if row is None:
                rejection_message = "The refresh token is invalid"
            elif row["revoked_at"] is not None:
                now = utc_iso()
                connection.execute(
                    "UPDATE sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE session_id = ?",
                    (now, row["session_id"]),
                )
                connection.execute(
                    "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE session_id = ?",
                    (now, row["session_id"]),
                )
                rejection_message = "The refresh token has been revoked"
            elif row["session_revoked_at"] is not None or not row["is_active"] or parse_utc(row["expires_at"]) <= utc_now():
                connection.execute(
                    "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE token_id = ?",
                    (utc_iso(), row["token_id"]),
                )
                rejection_message = "The refresh token is invalid or expired"
            else:
                access = issue_access_token(resolved_settings, row["user_id"], row["session_id"])
                _store_refresh_token(
                    connection,
                    user_id=row["user_id"],
                    session_id=row["session_id"],
                    refresh=new_refresh,
                )
                connection.execute(
                    "UPDATE refresh_tokens SET revoked_at = ?, replaced_by_token_id = ? WHERE token_id = ?",
                    (utc_iso(), new_refresh.token_id, row["token_id"]),
                )
                connection.execute(
                    "UPDATE sessions SET last_seen_at = ? WHERE session_id = ?",
                    (utc_iso(), row["session_id"]),
                )
        if rejection_message is not None:
            raise api_error(401, "invalid_refresh_token", rejection_message)
        assert access is not None
        return _token_response(resolved_settings, access, new_refresh)

    @app.post("/auth/logout", status_code=204, responses=ERROR_RESPONSES)
    def logout(user: Annotated[AuthenticatedUser, Depends(authenticated_user)]) -> Response:
        now = utc_iso()
        with database.transaction(immediate=True) as connection:
            connection.execute(
                "UPDATE sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE session_id = ? AND user_id = ?",
                (now, user.session_id, user.user_id),
            )
            connection.execute(
                "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE session_id = ? AND user_id = ?",
                (now, user.session_id, user.user_id),
            )
            activity.record_event(
                connection, activity.LOGOUT, actor="user", user_id=user.user_id, username=user.username
            )
        return Response(status_code=204)

    @app.post("/auth/change-password", status_code=204, responses=ERROR_RESPONSES)
    def change_password(
        body: ChangePasswordRequest,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> Response:
        with database.connection() as connection:
            row = connection.execute(
                "SELECT password_hash FROM users WHERE user_id = ?",
                (user.user_id,),
            ).fetchone()
        if row is None or not verify_password(row["password_hash"], body.current_password):
            raise api_error(401, "invalid_credentials", "Invalid password")
        if body.new_password == body.current_password:
            raise api_error(
                422,
                "password_unchanged",
                "The new password must differ from the current password",
            )
        now = utc_iso()
        with database.transaction(immediate=True) as connection:
            connection.execute(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?",
                (hash_password(body.new_password), now, user.user_id),
            )
            connection.execute(
                """
                UPDATE sessions
                SET revoked_at = COALESCE(revoked_at, ?)
                WHERE user_id = ? AND session_id != ?
                """,
                (now, user.user_id, user.session_id),
            )
            connection.execute(
                """
                UPDATE refresh_tokens
                SET revoked_at = COALESCE(revoked_at, ?)
                WHERE user_id = ? AND session_id != ?
                """,
                (now, user.user_id, user.session_id),
            )
            activity.record_event(
                connection, activity.PASSWORD_CHANGED, actor="user", user_id=user.user_id, username=user.username
            )
        return Response(status_code=204)

    @app.get("/users/me", response_model=UserResponse, responses=ERROR_RESPONSES)
    def get_me(user: Annotated[AuthenticatedUser, Depends(authenticated_user)]) -> dict:
        return {"user_id": user.user_id, "username": user.username, "created_at": user.created_at}

    @app.get("/users/me/storage", response_model=StorageResponse, responses=ERROR_RESPONSES)
    def get_storage(user: Annotated[AuthenticatedUser, Depends(authenticated_user)]) -> dict:
        with database.connection() as connection:
            row = connection.execute(
                "SELECT space_allocated_bytes, space_used_bytes FROM users WHERE user_id = ?",
                (user.user_id,),
            ).fetchone()
        return {
            "space_allocated_bytes": row["space_allocated_bytes"],
            "space_used_bytes": row["space_used_bytes"],
            "space_available_bytes": row["space_allocated_bytes"] - row["space_used_bytes"],
        }

    @app.post(
        "/media/fingerprints/check",
        response_model=FingerprintCheckResponse,
        responses=ERROR_RESPONSES,
    )
    def check_fingerprints(
        body: FingerprintCheckRequest,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> dict:
        if any(FINGERPRINT_PATTERN.fullmatch(value) is None for value in body.media_fingerprints):
            raise api_error(422, "invalid_media_fingerprint", "One or more media fingerprints are invalid")
        unique_fingerprints = list(dict.fromkeys(body.media_fingerprints))
        placeholders = ",".join("?" for _ in unique_fingerprints)
        with database.connection() as connection:
            rows = connection.execute(
                f"SELECT media_fingerprint, media_id FROM media WHERE user_id = ? AND media_fingerprint IN ({placeholders})",
                [user.user_id, *unique_fingerprints],
            ).fetchall()
        return {"matches": [dict(row) for row in rows]}

    @app.post(
        "/media/uploads",
        response_model=UploadCreateResponse,
        status_code=201,
        responses=ERROR_RESPONSES,
    )
    def create_upload(
        body: UploadCreateRequest,
        response: Response,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> dict:
        if body.encryption_version not in SUPPORTED_ENCRYPTION_VERSIONS:
            raise api_error(
                422,
                "unsupported_protocol_version",
                "The requested encryption protocol version is not supported",
                details={"supported_versions": sorted(SUPPORTED_ENCRYPTION_VERSIONS)},
            )
        if body.total_chunks > resolved_settings.max_total_chunks:
            raise api_error(422, "too_many_chunks", "The upload declares too many chunks")
        if not body.mime_type.lower().startswith(f"{body.media_type}/"):
            raise api_error(422, "mime_type_mismatch", "mime_type must match media_type")
        if (
            body.private_metadata_ciphertext is not None
            and len(body.private_metadata_ciphertext) > resolved_settings.max_private_metadata_chars
        ):
            raise api_error(413, "private_metadata_too_large", "Encrypted private metadata is too large")
        now = utc_iso()
        taken = _as_utc_iso(body.datetime_taken, now)
        with database.transaction(immediate=True) as connection:
            existing = connection.execute(
                "SELECT media_id FROM media WHERE user_id = ? AND media_fingerprint = ?",
                (user.user_id, body.media_fingerprint),
            ).fetchone()
            if existing:
                response.status_code = 200
                return {
                    "upload_id": None,
                    "media_id": existing["media_id"],
                    "already_exists": True,
                    "upload_in_progress": False,
                }
            active = connection.execute(
                "SELECT upload_id, media_id FROM upload_sessions WHERE user_id = ? AND media_fingerprint = ?",
                (user.user_id, body.media_fingerprint),
            ).fetchone()
            if active:
                response.status_code = 200
                return {
                    "upload_id": active["upload_id"],
                    "media_id": active["media_id"],
                    "already_exists": False,
                    "upload_in_progress": True,
                }
            upload_id = str(uuid.uuid4())
            media_id = str(uuid.uuid4())
            connection.execute(
                """
                INSERT INTO upload_sessions (
                    upload_id, media_id, user_id, media_fingerprint, mime_type,
                    media_type, plaintext_size_bytes, datetime_taken, width, height,
                    duration_seconds, private_metadata_ciphertext, encryption_version,
                    total_chunks, stored_size_bytes, thumbnail_uploaded,
                    thumbnail_storage_location, thumbnail_stored_size_bytes,
                    thumbnail_ciphertext_sha256, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, NULL, 0, NULL, 'uploading', ?, ?)
                """,
                (
                    upload_id,
                    media_id,
                    user.user_id,
                    body.media_fingerprint,
                    body.mime_type.lower(),
                    body.media_type,
                    body.plaintext_size_bytes,
                    taken,
                    body.width,
                    body.height,
                    body.duration_seconds,
                    body.private_metadata_ciphertext,
                    body.encryption_version,
                    body.total_chunks,
                    now,
                    now,
                ),
            )
        return {"upload_id": upload_id, "media_id": media_id, "already_exists": False, "upload_in_progress": False}

    @app.get(
        "/media/uploads/{upload_id}",
        response_model=UploadStatusResponse,
        responses=ERROR_RESPONSES,
    )
    def get_upload_status(
        upload_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> dict:
        with database.transaction(immediate=True) as connection:
            upload = _owned_upload(connection, user.user_id, str(upload_id))
            indexes = [
                row[0]
                for row in connection.execute(
                    "SELECT chunk_index FROM upload_chunks WHERE upload_id = ? ORDER BY chunk_index",
                    (str(upload_id),),
                )
            ]
            refreshed_at = utc_iso()
            connection.execute(
                "UPDATE upload_sessions SET updated_at = ? WHERE upload_id = ? AND user_id = ?",
                (refreshed_at, str(upload_id), user.user_id),
            )
        return {
            "upload_id": upload["upload_id"],
            "media_id": upload["media_id"],
            "total_chunks": upload["total_chunks"],
            "uploaded_chunks": indexes,
            "thumbnail_uploaded": bool(upload["thumbnail_uploaded"]),
            "stored_size_bytes": upload["stored_size_bytes"],
            "state": upload["state"],
            "created_at": upload["created_at"],
            "updated_at": refreshed_at,
        }

    @app.put(
        "/media/uploads/{upload_id}/chunks/{chunk_index}",
        response_model=ChunkUploadResponse,
        responses=ERROR_RESPONSES,
        summary="Upload one opaque independently authenticated media chunk",
    )
    async def put_upload_chunk(
        request: Request,
        response: Response,
        upload_id: uuid.UUID,
        chunk_index: Annotated[int, PathParameter(ge=0)],
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
        content_type: Annotated[str | None, Header(alias="Content-Type")] = None,
    ) -> dict:
        upload_id_text = str(upload_id)
        with database.connection() as connection:
            upload = _owned_upload(connection, user.user_id, upload_id_text)
            if chunk_index >= upload["total_chunks"]:
                raise api_error(422, "invalid_chunk_index", "Chunk index is outside the declared range")
            if upload["state"] != "uploading":
                raise api_error(409, "chunk_already_finalised", "The upload is being finalised")
        temporary = new_temporary_file(resolved_settings.media_root)
        destination_relative = upload_chunk_location(
            user.user_id, upload_id_text, chunk_index, str(uuid.uuid4())
        )
        destination = safe_path(resolved_settings.media_root, destination_relative)
        installed = False
        queue_ids: list[str] = []
        try:
            size, digest = await _stream_request_to_file(
                request, temporary, resolved_settings.max_chunk_bytes, "chunk"
            )
            with database.transaction(immediate=True) as connection:
                upload = _owned_upload(connection, user.user_id, upload_id_text)
                if upload["state"] != "uploading":
                    raise api_error(409, "chunk_already_finalised", "The upload is being finalised")
                if chunk_index >= upload["total_chunks"]:
                    raise api_error(422, "invalid_chunk_index", "Chunk index is outside the declared range")
                existing = connection.execute(
                    "SELECT * FROM upload_chunks WHERE upload_id = ? AND chunk_index = ?",
                    (upload_id_text, chunk_index),
                ).fetchone()
                if (
                    existing
                    and existing["stored_size_bytes"] == size
                    and existing["ciphertext_sha256"] == digest
                    and safe_path(resolved_settings.media_root, existing["storage_location"]).is_file()
                ):
                    connection.execute(
                        "UPDATE upload_sessions SET updated_at = ? WHERE upload_id = ?", (utc_iso(), upload_id_text)
                    )
                    response.status_code = 200
                    return {
                        "chunk_index": chunk_index,
                        "stored_size_bytes": size,
                        "ciphertext_sha256": digest,
                        "idempotent": True,
                    }
                old_size = int(existing["stored_size_bytes"]) if existing else 0
                user_quota = connection.execute(
                    "SELECT space_allocated_bytes, space_used_bytes FROM users WHERE user_id = ? AND is_active = 1",
                    (user.user_id,),
                ).fetchone()
                if user_quota is None:
                    raise api_error(401, "invalid_access_token", "The user account is inactive")
                projected = user_quota["space_used_bytes"] + _reserved_bytes(connection, user.user_id) - old_size + size
                if projected > user_quota["space_allocated_bytes"]:
                    raise api_error(413, "quota_exceeded", "The upload would exceed the storage quota")
                install_file(temporary, destination)
                installed = True
                now = utc_iso()
                connection.execute(
                    """
                    INSERT INTO upload_chunks (
                        upload_id, chunk_index, stored_size_bytes, storage_location,
                        ciphertext_sha256, uploaded_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(upload_id, chunk_index) DO UPDATE SET
                        stored_size_bytes = excluded.stored_size_bytes,
                        storage_location = excluded.storage_location,
                        ciphertext_sha256 = excluded.ciphertext_sha256,
                        uploaded_at = excluded.uploaded_at
                    """,
                    (upload_id_text, chunk_index, size, destination_relative, digest, now),
                )
                connection.execute(
                    "UPDATE upload_sessions SET stored_size_bytes = stored_size_bytes - ? + ?, updated_at = ? WHERE upload_id = ?",
                    (old_size, size, now, upload_id_text),
                )
                if existing:
                    queue_ids.append(_queue_path(connection, existing["storage_location"]))
            if queue_ids:
                process_delete_queue(database, resolved_settings, queue_ids)
            response.status_code = 201 if existing is None else 200
            return {
                "chunk_index": chunk_index,
                "stored_size_bytes": size,
                "ciphertext_sha256": digest,
                "idempotent": False,
            }
        except Exception:
            if installed:
                destination.unlink(missing_ok=True)
            raise
        finally:
            temporary.unlink(missing_ok=True)

    @app.put(
        "/media/uploads/{upload_id}/thumbnail",
        response_model=ThumbnailUploadResponse,
        responses=ERROR_RESPONSES,
        summary="Upload the opaque client-generated encrypted thumbnail",
    )
    async def put_upload_thumbnail(
        request: Request,
        response: Response,
        upload_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> dict:
        upload_id_text = str(upload_id)
        with database.connection() as connection:
            upload = _owned_upload(connection, user.user_id, upload_id_text)
            if upload["state"] != "uploading":
                raise api_error(409, "chunk_already_finalised", "The upload is being finalised")
        temporary = new_temporary_file(resolved_settings.media_root)
        destination_relative = upload_thumbnail_location(user.user_id, upload_id_text, str(uuid.uuid4()))
        destination = safe_path(resolved_settings.media_root, destination_relative)
        installed = False
        queue_ids: list[str] = []
        try:
            size, digest = await _stream_request_to_file(
                request, temporary, resolved_settings.max_thumbnail_bytes, "thumbnail"
            )
            with database.transaction(immediate=True) as connection:
                upload = _owned_upload(connection, user.user_id, upload_id_text)
                if upload["state"] != "uploading":
                    raise api_error(409, "chunk_already_finalised", "The upload is being finalised")
                if (
                    upload["thumbnail_uploaded"]
                    and upload["thumbnail_stored_size_bytes"] == size
                    and upload["thumbnail_ciphertext_sha256"] == digest
                    and safe_path(
                        resolved_settings.media_root, upload["thumbnail_storage_location"]
                    ).is_file()
                ):
                    connection.execute(
                        "UPDATE upload_sessions SET updated_at = ? WHERE upload_id = ?", (utc_iso(), upload_id_text)
                    )
                    response.status_code = 200
                    return {"stored_size_bytes": size, "ciphertext_sha256": digest, "idempotent": True}
                old_size = int(upload["thumbnail_stored_size_bytes"])
                user_quota = connection.execute(
                    "SELECT space_allocated_bytes, space_used_bytes FROM users WHERE user_id = ? AND is_active = 1",
                    (user.user_id,),
                ).fetchone()
                if user_quota is None:
                    raise api_error(401, "invalid_access_token", "The user account is inactive")
                projected = user_quota["space_used_bytes"] + _reserved_bytes(connection, user.user_id) - old_size + size
                if projected > user_quota["space_allocated_bytes"]:
                    raise api_error(413, "quota_exceeded", "The upload would exceed the storage quota")
                install_file(temporary, destination)
                installed = True
                now = utc_iso()
                connection.execute(
                    """
                    UPDATE upload_sessions
                    SET thumbnail_uploaded = 1,
                        thumbnail_storage_location = ?,
                        thumbnail_stored_size_bytes = ?,
                        thumbnail_ciphertext_sha256 = ?,
                        stored_size_bytes = stored_size_bytes - ? + ?,
                        updated_at = ?
                    WHERE upload_id = ?
                    """,
                    (destination_relative, size, digest, old_size, size, now, upload_id_text),
                )
                if upload["thumbnail_uploaded"] and upload["thumbnail_storage_location"]:
                    queue_ids.append(_queue_path(connection, upload["thumbnail_storage_location"]))
            if queue_ids:
                process_delete_queue(database, resolved_settings, queue_ids)
            response.status_code = 201 if not upload["thumbnail_uploaded"] else 200
            return {"stored_size_bytes": size, "ciphertext_sha256": digest, "idempotent": False}
        except Exception:
            if installed:
                destination.unlink(missing_ok=True)
            raise
        finally:
            temporary.unlink(missing_ok=True)

    @app.post(
        "/media/uploads/{upload_id}/complete",
        response_model=MediaResponse,
        responses=ERROR_RESPONSES,
    )
    def complete_upload(
        upload_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> dict:
        upload_id_text = str(upload_id)
        source_directory: Path | None = None
        destination_directory: Path | None = None
        directory_moved = False
        try:
            with database.transaction(immediate=True) as connection:
                upload = _owned_upload(connection, user.user_id, upload_id_text)
                if upload["state"] != "uploading":
                    raise api_error(409, "chunk_already_finalised", "The upload is already being finalised")
                chunks = connection.execute(
                    "SELECT * FROM upload_chunks WHERE upload_id = ? ORDER BY chunk_index",
                    (upload_id_text,),
                ).fetchall()
                indexes = [row["chunk_index"] for row in chunks]
                if len(chunks) != upload["total_chunks"] or any(
                    index != expected for expected, index in enumerate(indexes)
                ):
                    uploaded = set(indexes)
                    missing = [index for index in range(upload["total_chunks"]) if index not in uploaded][:100]
                    raise api_error(
                        409,
                        "upload_incomplete",
                        "Not all expected media chunks have been uploaded",
                        details={"missing_chunks": missing, "missing_list_truncated": len(missing) == 100},
                    )
                if not upload["thumbnail_uploaded"] or not upload["thumbnail_storage_location"]:
                    raise api_error(409, "upload_incomplete", "The encrypted thumbnail has not been uploaded")

                actual_chunk_sizes: list[int] = []
                for chunk in chunks:
                    expected_prefix = f"{user.user_id}/.uploads/{upload_id_text}/original/{chunk['chunk_index']:08d}-"
                    if not chunk["storage_location"].startswith(expected_prefix):
                        raise api_error(500, "storage_inconsistent", "Upload storage metadata is inconsistent")
                    path = safe_path(resolved_settings.media_root, chunk["storage_location"])
                    if not path.is_file():
                        raise api_error(500, "storage_inconsistent", "An uploaded chunk is missing from storage")
                    actual_chunk_sizes.append(path.stat().st_size)
                expected_thumbnail_prefix = f"{user.user_id}/.uploads/{upload_id_text}/thumbnail-"
                if not upload["thumbnail_storage_location"].startswith(expected_thumbnail_prefix):
                    raise api_error(500, "storage_inconsistent", "Thumbnail storage metadata is inconsistent")
                thumbnail_path = safe_path(resolved_settings.media_root, upload["thumbnail_storage_location"])
                if not thumbnail_path.is_file():
                    raise api_error(500, "storage_inconsistent", "The uploaded thumbnail is missing from storage")
                actual_thumbnail_size = thumbnail_path.stat().st_size
                exact_stored_size = sum(actual_chunk_sizes) + actual_thumbnail_size

                referenced_paths = {
                    safe_path(resolved_settings.media_root, chunk["storage_location"]).resolve()
                    for chunk in chunks
                }
                referenced_paths.add(thumbnail_path.resolve())
                source_directory = upload_directory(resolved_settings.media_root, user.user_id, upload_id_text)
                if not source_directory.is_dir():
                    raise api_error(500, "storage_inconsistent", "The upload directory is missing")
                try:
                    for stored_file in source_directory.rglob("*"):
                        if stored_file.is_file() and stored_file.resolve() not in referenced_paths:
                            stored_file.unlink()
                    connection.execute(
                        "DELETE FROM file_delete_queue WHERE storage_location LIKE ?",
                        (f"{user.user_id}/.uploads/{upload_id_text}/%",),
                    )
                except OSError as exc:
                    raise api_error(500, "storage_cleanup_failed", "Superseded upload objects could not be cleaned") from exc

                quota = connection.execute(
                    "SELECT space_allocated_bytes, space_used_bytes FROM users WHERE user_id = ? AND is_active = 1",
                    (user.user_id,),
                ).fetchone()
                if quota is None:
                    raise api_error(401, "invalid_access_token", "The user account is inactive")
                reservations = _reserved_bytes(connection, user.user_id)
                projected_total = quota["space_used_bytes"] + reservations - upload["stored_size_bytes"] + exact_stored_size
                if projected_total > quota["space_allocated_bytes"]:
                    raise api_error(413, "quota_exceeded", "Completing the upload would exceed the storage quota")

                destination_directory = final_media_directory(
                    resolved_settings.media_root, user.user_id, upload["media_id"]
                )
                if destination_directory.exists():
                    raise api_error(409, "media_storage_conflict", "Final media storage already exists")
                destination_directory.parent.mkdir(parents=True, exist_ok=True)
                connection.execute(
                    "UPDATE upload_sessions SET state = 'finalizing', updated_at = ? WHERE upload_id = ?",
                    (utc_iso(), upload_id_text),
                )
                os.replace(source_directory, destination_directory)
                directory_moved = True

                now = utc_iso()
                connection.execute(
                    """
                    INSERT INTO media (
                        media_id, user_id, media_fingerprint, mime_type, media_type,
                        plaintext_size_bytes, stored_size_bytes, datetime_added, datetime_taken,
                        width, height, duration_seconds, private_metadata_ciphertext,
                        thumbnail_available, thumbnail_storage_location,
                        thumbnail_stored_size_bytes, encryption_version, total_chunks,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        upload["media_id"],
                        user.user_id,
                        upload["media_fingerprint"],
                        upload["mime_type"],
                        upload["media_type"],
                        upload["plaintext_size_bytes"],
                        exact_stored_size,
                        now,
                        upload["datetime_taken"],
                        upload["width"],
                        upload["height"],
                        upload["duration_seconds"],
                        upload["private_metadata_ciphertext"],
                        promoted_location(
                            upload["thumbnail_storage_location"],
                            user.user_id,
                            upload_id_text,
                            upload["media_id"],
                        ),
                        actual_thumbnail_size,
                        upload["encryption_version"],
                        upload["total_chunks"],
                        now,
                        now,
                    ),
                )
                for chunk, actual_size in zip(chunks, actual_chunk_sizes, strict=True):
                    connection.execute(
                        """
                        INSERT INTO media_chunks (
                            media_id, chunk_index, stored_size_bytes, storage_location,
                            ciphertext_sha256, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (
                            upload["media_id"],
                            chunk["chunk_index"],
                            actual_size,
                            promoted_location(
                                chunk["storage_location"],
                                user.user_id,
                                upload_id_text,
                                upload["media_id"],
                            ),
                            chunk["ciphertext_sha256"],
                            now,
                        ),
                    )
                updated = connection.execute(
                    """
                    UPDATE users
                    SET space_used_bytes = space_used_bytes + ?, updated_at = ?
                    WHERE user_id = ? AND space_used_bytes + ? <= space_allocated_bytes
                    """,
                    (exact_stored_size, now, user.user_id, exact_stored_size),
                )
                if updated.rowcount != 1:
                    raise api_error(413, "quota_exceeded", "Completing the upload would exceed the storage quota")
                connection.execute("DELETE FROM upload_sessions WHERE upload_id = ?", (upload_id_text,))
                activity.record_event(
                    connection, activity.UPLOAD_COMPLETED, actor="user", user_id=user.user_id,
                    username=user.username, bytes_count=exact_stored_size,
                    details={"media_type": upload["media_type"]},
                )
            with database.connection() as connection:
                media = _owned_media(connection, user.user_id, upload["media_id"])
            return _media_payload(media)
        except sqlite3.IntegrityError as exc:
            if directory_moved and destination_directory and source_directory and destination_directory.exists():
                source_directory.parent.mkdir(parents=True, exist_ok=True)
                os.replace(destination_directory, source_directory)
            raise api_error(409, "duplicate_media", "Media with this fingerprint already exists") from exc
        except Exception:
            if directory_moved and destination_directory and source_directory and destination_directory.exists():
                source_directory.parent.mkdir(parents=True, exist_ok=True)
                os.replace(destination_directory, source_directory)
            raise

    @app.delete("/media/uploads/{upload_id}", status_code=204, responses=ERROR_RESPONSES)
    def cancel_upload(
        upload_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> Response:
        queue_ids: list[str] = []
        with database.transaction(immediate=True) as connection:
            upload = _owned_upload(connection, user.user_id, str(upload_id))
            if upload["state"] != "uploading":
                raise api_error(409, "chunk_already_finalised", "The upload is being finalised")
            queue_ids.append(_queue_path(connection, f"{user.user_id}/.uploads/{upload_id}"))
            connection.execute(
                "DELETE FROM upload_sessions WHERE upload_id = ? AND user_id = ?",
                (str(upload_id), user.user_id),
            )
        process_delete_queue(database, resolved_settings, queue_ids)
        return Response(status_code=204)

    @app.get("/media", response_model=MediaListResponse, responses=ERROR_RESPONSES)
    def list_media(
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
        limit: Annotated[int, Query(ge=1, le=200)] = 50,
        cursor: str | None = None,
        media_type: Literal["image", "video"] | None = None,
        mime_type: str | None = Query(default=None, max_length=127),
        datetime_taken_from: datetime | None = None,
        datetime_taken_to: datetime | None = None,
        size_min: Annotated[int | None, Query(ge=0)] = None,
        size_max: Annotated[int | None, Query(ge=0)] = None,
        sort_by: Literal["datetime_taken", "datetime_added", "plaintext_size_bytes"] = "datetime_taken",
        sort_order: Literal["asc", "desc"] = "desc",
    ) -> dict:
        clauses = ["user_id = ?"]
        values: list[object] = [user.user_id]
        if media_type is not None:
            clauses.append("media_type = ?")
            values.append(media_type)
        if mime_type is not None:
            clauses.append("mime_type = ?")
            values.append(mime_type.lower())
        if datetime_taken_from is not None:
            clauses.append("datetime_taken >= ?")
            values.append(_as_utc_iso(datetime_taken_from))
        if datetime_taken_to is not None:
            clauses.append("datetime_taken <= ?")
            values.append(_as_utc_iso(datetime_taken_to))
        if size_min is not None:
            clauses.append("plaintext_size_bytes >= ?")
            values.append(size_min)
        if size_max is not None:
            clauses.append("plaintext_size_bytes <= ?")
            values.append(size_max)
        if cursor:
            cursor_value, cursor_media_id = _decode_cursor(cursor, sort_by, sort_order)
            operator = ">" if sort_order == "asc" else "<"
            clauses.append(f"({sort_by} {operator} ? OR ({sort_by} = ? AND media_id {operator} ?))")
            values.extend([cursor_value, cursor_value, cursor_media_id])
        direction = sort_order.upper()
        where = " AND ".join(clauses)
        with database.connection() as connection:
            rows = connection.execute(
                f"SELECT * FROM media WHERE {where} ORDER BY {sort_by} {direction}, media_id {direction} LIMIT ?",
                [*values, limit + 1],
            ).fetchall()
        has_more = len(rows) > limit
        page_rows = rows[:limit]
        next_cursor = None
        if has_more and page_rows:
            last = page_rows[-1]
            next_cursor = _encode_cursor(sort_by, sort_order, last[sort_by], last["media_id"])
        return {"items": [_media_payload(row) for row in page_rows], "next_cursor": next_cursor}

    @app.get("/media/{media_id}", response_model=MediaResponse, responses=ERROR_RESPONSES)
    def get_media_metadata(
        media_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> dict:
        with database.connection() as connection:
            row = _owned_media(connection, user.user_id, str(media_id))
        return _media_payload(row)

    @app.get(
        "/media/{media_id}/thumbnail",
        responses={**ERROR_RESPONSES, 200: {"content": {"application/octet-stream": {}}}},
    )
    def get_media_thumbnail(
        media_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> StreamingResponse:
        with database.connection() as connection:
            media = _owned_media(connection, user.user_id, str(media_id))
        if not media["thumbnail_available"] or not media["thumbnail_storage_location"]:
            raise api_error(404, "thumbnail_not_found", "Encrypted thumbnail not found")
        path = safe_path(resolved_settings.media_root, media["thumbnail_storage_location"])
        if not path.is_file():
            raise api_error(500, "storage_inconsistent", "The encrypted thumbnail is missing from storage")
        size = path.stat().st_size
        return StreamingResponse(
            iter_file(path, resolved_settings.stream_block_bytes),
            media_type="application/octet-stream",
            headers={"Content-Length": str(size)},
        )

    @app.get(
        "/media/{media_id}/chunks/{chunk_index}",
        responses={**ERROR_RESPONSES, 200: {"content": {"application/octet-stream": {}}}},
    )
    def get_media_chunk(
        media_id: uuid.UUID,
        chunk_index: Annotated[int, PathParameter(ge=0)],
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> StreamingResponse:
        with database.connection() as connection:
            media = _owned_media(connection, user.user_id, str(media_id))
            if chunk_index >= media["total_chunks"]:
                raise api_error(422, "invalid_chunk_index", "Chunk index is outside the media range")
            chunk = connection.execute(
                "SELECT * FROM media_chunks WHERE media_id = ? AND chunk_index = ?",
                (str(media_id), chunk_index),
            ).fetchone()
        if chunk is None:
            raise api_error(404, "chunk_not_found", "Encrypted media chunk not found")
        path = safe_path(resolved_settings.media_root, chunk["storage_location"])
        if not path.is_file():
            raise api_error(500, "storage_inconsistent", "The encrypted media chunk is missing from storage")
        size = path.stat().st_size
        return StreamingResponse(
            iter_file(path, resolved_settings.stream_block_bytes),
            media_type="application/octet-stream",
            headers={"Content-Length": str(size), "X-Chunk-Index": str(chunk_index)},
        )

    @app.delete("/media/{media_id}", status_code=204, responses=ERROR_RESPONSES)
    def delete_media(
        media_id: uuid.UUID,
        user: Annotated[AuthenticatedUser, Depends(authenticated_user)],
    ) -> Response:
        queue_ids: list[str] = []
        with database.transaction(immediate=True) as connection:
            media = _owned_media(connection, user.user_id, str(media_id))
            if media["encryption_version"] == 0:
                locations = [
                    row["storage_location"]
                    for row in connection.execute(
                        "SELECT storage_location FROM media_chunks WHERE media_id = ?", (str(media_id),)
                    )
                ]
                if media["thumbnail_storage_location"]:
                    locations.append(media["thumbnail_storage_location"])
                for location in dict.fromkeys(locations):
                    queue_ids.append(_queue_path(connection, location))
                connection.execute(
                    """
                    INSERT INTO legacy_media_migrations(media_id, status, migrated_at)
                    VALUES (?, 'deleted', ?)
                    ON CONFLICT(media_id) DO UPDATE SET status = 'deleted', migrated_at = excluded.migrated_at
                    """,
                    (str(media_id), utc_iso()),
                )
            else:
                queue_ids.append(_queue_path(connection, f"{user.user_id}/media/{media_id}"))
            connection.execute(
                """
                UPDATE users
                SET space_used_bytes = MAX(0, space_used_bytes - ?), updated_at = ?
                WHERE user_id = ?
                """,
                (media["stored_size_bytes"], utc_iso(), user.user_id),
            )
            connection.execute(
                "DELETE FROM media WHERE media_id = ? AND user_id = ?", (str(media_id), user.user_id)
            )
            activity.record_event(
                connection, activity.MEDIA_DELETED, actor="user", user_id=user.user_id,
                username=user.username, bytes_count=int(media["stored_size_bytes"]),
                details={"media_type": media["media_type"]},
            )
        process_delete_queue(database, resolved_settings, queue_ids)
        return Response(status_code=204)

    return app


app = create_app()
