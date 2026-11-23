"""Administrative operations shared by manage.py and the web console.

These act on any account by user_id. They must only be reachable from the console app
(admin-authenticated, separate port) or the CLI. Never expose them on the user API, where
ownership always comes from the access token.
"""
from __future__ import annotations

import re
import sqlite3
import uuid
from datetime import timedelta

from . import activity
from .config import Settings
from .database import Database
from .security import hash_password, utc_iso, utc_now


MIN_PASSWORD_CHARS = 12
MAX_PASSWORD_CHARS = 1024
USERNAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")


class AdminError(Exception):
    def __init__(self, status_code: int, code: str, message: str, details: object | None = None):
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details


def validate_password(password: str) -> None:
    if len(password) < MIN_PASSWORD_CHARS:
        raise AdminError(422, "password_too_short", f"Password must contain at least {MIN_PASSWORD_CHARS} characters")
    if len(password) > MAX_PASSWORD_CHARS:
        raise AdminError(422, "password_too_long", f"Password must contain at most {MAX_PASSWORD_CHARS} characters")


def normalize_username(username: str) -> str:
    cleaned = username.strip()
    if not USERNAME_PATTERN.fullmatch(cleaned):
        raise AdminError(
            422,
            "invalid_username",
            "Usernames are 1-64 characters: letters, digits, '.', '_', '@' or '-', starting with a letter or digit",
        )
    return cleaned


def _require_user(connection: sqlite3.Connection, user_id: str) -> sqlite3.Row:
    row = connection.execute("SELECT * FROM users WHERE user_id = ?", (user_id,)).fetchone()
    if row is None:
        raise AdminError(404, "user_not_found", "User not found")
    return row


def find_user_id(database: Database, username: str) -> str | None:
    with database.connection() as connection:
        row = connection.execute(
            "SELECT user_id FROM users WHERE username = ? COLLATE NOCASE", (username.strip(),)
        ).fetchone()
    return row["user_id"] if row else None


def _revoke_sessions(connection: sqlite3.Connection, user_id: str, now: str) -> int:
    revoked = connection.execute(
        "UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", (now, user_id)
    ).rowcount
    connection.execute(
        "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?", (now, user_id)
    )
    return revoked


def create_user(
    database: Database,
    username: str,
    password: str,
    quota_bytes: int,
    *,
    actor: str,
    client_ip: str | None = None,
) -> str:
    username = normalize_username(username)
    validate_password(password)
    if quota_bytes <= 0:
        raise AdminError(422, "invalid_quota", "Quota must be positive")
    password_hash = hash_password(password)
    user_id = str(uuid.uuid4())
    now = utc_iso()
    try:
        with database.transaction(immediate=True) as connection:
            connection.execute(
                """
                INSERT INTO users (
                    user_id, username, password_hash, space_allocated_bytes,
                    space_used_bytes, folder_location, created_at, updated_at, is_active
                ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, 1)
                """,
                (user_id, username, password_hash, quota_bytes, user_id, now, now),
            )
            activity.record_event(
                connection, activity.USER_CREATED, actor=actor, user_id=user_id, username=username,
                bytes_count=quota_bytes, client_ip=client_ip,
            )
    except sqlite3.IntegrityError as exc:
        raise AdminError(409, "username_taken", f"A user named {username!r} already exists") from exc
    return user_id


def set_user_password(
    database: Database, user_id: str, password: str, *, actor: str, client_ip: str | None = None
) -> int:
    """Replace the password hash and revoke every session. Returns how many sessions were signed out.

    Revoking sessions also kills outstanding access tokens, because every request checks its
    session. The media master key does not depend on the password, so this never affects decryption.
    """
    validate_password(password)
    password_hash = hash_password(password)
    now = utc_iso()
    with database.transaction(immediate=True) as connection:
        user = _require_user(connection, user_id)
        connection.execute(
            "UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?", (password_hash, now, user_id)
        )
        revoked = _revoke_sessions(connection, user_id, now)
        activity.record_event(
            connection, activity.PASSWORD_RESET, actor=actor, user_id=user_id, username=user["username"],
            client_ip=client_ip, details={"sessions_revoked": revoked},
        )
    return revoked


def set_user_quota(
    database: Database, user_id: str, quota_bytes: int, *, actor: str, client_ip: str | None = None
) -> None:
    if quota_bytes <= 0:
        raise AdminError(422, "invalid_quota", "Quota must be positive")
    with database.transaction(immediate=True) as connection:
        user = _require_user(connection, user_id)
        used = int(user["space_used_bytes"])
        if quota_bytes < used:
            raise AdminError(
                409,
                "quota_below_usage",
                "The quota cannot be lower than the storage the user already uses",
                details={"space_used_bytes": used},
            )
        connection.execute(
            "UPDATE users SET space_allocated_bytes = ?, updated_at = ? WHERE user_id = ?",
            (quota_bytes, utc_iso(), user_id),
        )
        activity.record_event(
            connection, activity.QUOTA_CHANGED, actor=actor, user_id=user_id, username=user["username"],
            bytes_count=quota_bytes, client_ip=client_ip,
            details={"previous_bytes": int(user["space_allocated_bytes"])},
        )


def set_user_active(
    database: Database, user_id: str, active: bool, *, actor: str, client_ip: str | None = None
) -> None:
    now = utc_iso()
    with database.transaction(immediate=True) as connection:
        user = _require_user(connection, user_id)
        connection.execute(
            "UPDATE users SET is_active = ?, updated_at = ? WHERE user_id = ?", (int(active), now, user_id)
        )
        revoked = 0 if active else _revoke_sessions(connection, user_id, now)
        activity.record_event(
            connection, activity.USER_ENABLED if active else activity.USER_DISABLED, actor=actor,
            user_id=user_id, username=user["username"], client_ip=client_ip,
            details=None if active else {"sessions_revoked": revoked},
        )


def revoke_user_sessions(database: Database, user_id: str, *, actor: str, client_ip: str | None = None) -> int:
    now = utc_iso()
    with database.transaction(immediate=True) as connection:
        user = _require_user(connection, user_id)
        revoked = _revoke_sessions(connection, user_id, now)
        activity.record_event(
            connection, activity.SESSIONS_REVOKED, actor=actor, user_id=user_id, username=user["username"],
            client_ip=client_ip, details={"sessions_revoked": revoked},
        )
    return revoked


def delete_user(
    database: Database,
    settings: Settings,
    user_id: str,
    *,
    confirm_username: str,
    actor: str,
    client_ip: str | None = None,
) -> dict:
    """Delete an account, its sessions, media index and every stored file.

    The database commits first (rows cascade from users); files are removed through the durable
    file_delete_queue, so a crash mid-way is retried at the next startup or cleanup run.
    """
    from .main import _queue_path, process_delete_queue  # deferred: main imports this module's users

    user_id = str(uuid.UUID(user_id))  # only server-generated IDs ever become a path
    with database.transaction(immediate=True) as connection:
        user = _require_user(connection, user_id)
        if confirm_username.strip().casefold() != str(user["username"]).casefold():
            raise AdminError(422, "confirmation_mismatch", "Type the username exactly to confirm deletion")
        media_count = connection.execute("SELECT COUNT(*) FROM media WHERE user_id = ?", (user_id,)).fetchone()[0]
        legacy = connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'legacy_media_v1'"
        ).fetchone()
        if legacy is not None:
            connection.execute("DELETE FROM legacy_media_v1 WHERE user_id = ?", (user_id,))
        queue_id = _queue_path(connection, user_id)
        connection.execute("DELETE FROM users WHERE user_id = ?", (user_id,))
        activity.record_event(
            connection, activity.USER_DELETED, actor=actor, user_id=user_id, username=user["username"],
            bytes_count=int(user["space_used_bytes"]), client_ip=client_ip, details={"media_count": media_count},
        )
    removed = process_delete_queue(database, settings, [queue_id])
    return {
        "username": user["username"],
        "media_count": media_count,
        "space_freed_bytes": int(user["space_used_bytes"]),
        "files_removed": bool(removed),
    }


def list_users(database: Database) -> list[dict]:
    active_cutoff = utc_iso(utc_now() - timedelta(days=30))
    with database.connection() as connection:
        rows = connection.execute(
            """
            SELECT users.user_id, users.username, users.is_active, users.created_at,
                   users.space_used_bytes, users.space_allocated_bytes,
                   (SELECT COUNT(*) FROM media WHERE media.user_id = users.user_id) AS media_count,
                   (SELECT COUNT(*) FROM media WHERE media.user_id = users.user_id AND media_type = 'video') AS video_count,
                   (SELECT COALESCE(SUM(stored_size_bytes), 0) FROM upload_sessions
                        WHERE upload_sessions.user_id = users.user_id) AS reserved_bytes,
                   (SELECT COUNT(*) FROM upload_sessions WHERE upload_sessions.user_id = users.user_id) AS uploads_in_progress,
                   (SELECT COUNT(*) FROM sessions WHERE sessions.user_id = users.user_id
                        AND sessions.revoked_at IS NULL AND sessions.last_seen_at >= ?) AS active_devices,
                   (SELECT MAX(last_seen_at) FROM sessions WHERE sessions.user_id = users.user_id) AS last_seen_at,
                   (SELECT MAX(created_at) FROM media WHERE media.user_id = users.user_id) AS last_upload_at
            FROM users
            ORDER BY users.username COLLATE NOCASE
            """,
            (active_cutoff,),
        ).fetchall()
    return [
        {
            "user_id": row["user_id"],
            "username": row["username"],
            "is_active": bool(row["is_active"]),
            "created_at": row["created_at"],
            "space_used_bytes": int(row["space_used_bytes"]),
            "space_allocated_bytes": int(row["space_allocated_bytes"]),
            "reserved_bytes": int(row["reserved_bytes"]),
            "media_count": int(row["media_count"]),
            "video_count": int(row["video_count"]),
            "uploads_in_progress": int(row["uploads_in_progress"]),
            "active_devices": int(row["active_devices"]),
            "last_seen_at": row["last_seen_at"],
            "last_upload_at": row["last_upload_at"],
        }
        for row in rows
    ]


# ---------------------------------------------------------------------------
# Console administrator accounts (separate from app users; managed from the CLI only)
# ---------------------------------------------------------------------------

def set_console_admin(database: Database, username: str, password: str) -> bool:
    """Create a console admin, or reset its password and sign it out everywhere. Returns True if created."""
    username = normalize_username(username)
    validate_password(password)
    password_hash = hash_password(password)
    now = utc_iso()
    with database.transaction(immediate=True) as connection:
        existing = connection.execute(
            "SELECT admin_id FROM console_admins WHERE username = ? COLLATE NOCASE", (username,)
        ).fetchone()
        if existing is None:
            connection.execute(
                "INSERT INTO console_admins(admin_id, username, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                (str(uuid.uuid4()), username, password_hash, now, now),
            )
            return True
        connection.execute(
            "UPDATE console_admins SET password_hash = ?, updated_at = ? WHERE admin_id = ?",
            (password_hash, now, existing["admin_id"]),
        )
        connection.execute(
            "UPDATE console_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE admin_id = ?",
            (now, existing["admin_id"]),
        )
        return False


def remove_console_admin(database: Database, username: str) -> bool:
    with database.transaction(immediate=True) as connection:
        return connection.execute(
            "DELETE FROM console_admins WHERE username = ? COLLATE NOCASE", (username.strip(),)
        ).rowcount == 1
