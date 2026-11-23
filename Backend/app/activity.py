"""Operational activity log shown in the admin console.

Events carry only account-level facts: who, when, what kind of action, and opaque byte
counts. Never record media fingerprints, MIME details, filenames, tokens or passwords here.
"""
from __future__ import annotations

import json
import sqlite3
from datetime import timedelta

from .database import Database
from .security import utc_iso, utc_now


# User API events
LOGIN = "login"
LOGIN_FAILED = "login_failed"
LOGOUT = "logout"
PASSWORD_CHANGED = "password_changed"
UPLOAD_COMPLETED = "upload_completed"
MEDIA_DELETED = "media_deleted"

# Console (admin) events
ADMIN_LOGIN = "admin_login"
ADMIN_LOGIN_FAILED = "admin_login_failed"
ADMIN_LOGOUT = "admin_logout"
USER_CREATED = "user_created"
USER_DELETED = "user_deleted"
QUOTA_CHANGED = "quota_changed"
PASSWORD_RESET = "password_reset"
USER_DISABLED = "user_disabled"
USER_ENABLED = "user_enabled"
SESSIONS_REVOKED = "sessions_revoked"

EVENT_TYPES = (
    LOGIN, LOGIN_FAILED, LOGOUT, PASSWORD_CHANGED, UPLOAD_COMPLETED, MEDIA_DELETED,
    ADMIN_LOGIN, ADMIN_LOGIN_FAILED, ADMIN_LOGOUT, USER_CREATED, USER_DELETED,
    QUOTA_CHANGED, PASSWORD_RESET, USER_DISABLED, USER_ENABLED, SESSIONS_REVOKED,
)


def record_event(
    connection: sqlite3.Connection,
    event_type: str,
    *,
    actor: str,
    user_id: str | None = None,
    username: str | None = None,
    bytes_count: int | None = None,
    client_ip: str | None = None,
    details: dict | None = None,
) -> None:
    """Insert an event using the caller's connection, so it commits or rolls back with the action."""
    connection.execute(
        """
        INSERT INTO activity_events (
            occurred_at, event_type, user_id, username, actor, bytes, client_ip, details
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            utc_iso(),
            event_type,
            user_id,
            username,
            actor,
            bytes_count,
            client_ip,
            json.dumps(details, separators=(",", ":")) if details else None,
        ),
    )


def record_event_now(database: Database, event_type: str, **fields) -> None:
    """Record an event in its own transaction (for actions that have no transaction of their own)."""
    with database.transaction() as connection:
        record_event(connection, event_type, **fields)


def prune_events(database: Database, retention_days: int) -> int:
    cutoff = utc_iso(utc_now() - timedelta(days=retention_days))
    with database.transaction(immediate=True) as connection:
        deleted = connection.execute("DELETE FROM activity_events WHERE occurred_at < ?", (cutoff,)).rowcount
        connection.execute(
            "DELETE FROM console_sessions WHERE expires_at < ? OR revoked_at IS NOT NULL",
            (utc_iso(),),
        )
    return deleted
