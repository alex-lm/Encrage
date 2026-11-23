from __future__ import annotations

import hashlib
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from .storage import StorageError, safe_path


CURRENT_SCHEMA_VERSION = 4

CURRENT_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
    password_hash TEXT NOT NULL,
    space_allocated_bytes INTEGER NOT NULL CHECK (space_allocated_bytes >= 0),
    space_used_bytes INTEGER NOT NULL DEFAULT 0 CHECK (space_used_bytes >= 0),
    folder_location TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    CHECK (space_used_bytes <= space_allocated_bytes)
);

CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    issued_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT,
    replaced_by_token_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session ON refresh_tokens(session_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS media (
    media_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    media_fingerprint TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK (media_type IN ('image', 'video')),
    plaintext_size_bytes INTEGER NOT NULL CHECK (plaintext_size_bytes >= 0),
    stored_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (stored_size_bytes >= 0),
    datetime_added TEXT NOT NULL,
    datetime_taken TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    duration_seconds REAL,
    private_metadata_ciphertext TEXT,
    thumbnail_available INTEGER NOT NULL DEFAULT 0 CHECK (thumbnail_available IN (0, 1)),
    thumbnail_storage_location TEXT,
    thumbnail_stored_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (thumbnail_stored_size_bytes >= 0),
    encryption_version INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL CHECK (total_chunks > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, media_fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_media_user ON media(user_id);
CREATE INDEX IF NOT EXISTS idx_media_user_taken ON media(user_id, datetime_taken DESC, media_id DESC);
CREATE INDEX IF NOT EXISTS idx_media_user_added ON media(user_id, datetime_added DESC, media_id DESC);
CREATE INDEX IF NOT EXISTS idx_media_user_type ON media(user_id, media_type);
CREATE UNIQUE INDEX IF NOT EXISTS idx_media_user_fingerprint ON media(user_id, media_fingerprint);

CREATE TABLE IF NOT EXISTS media_chunks (
    media_id TEXT NOT NULL REFERENCES media(media_id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    stored_size_bytes INTEGER NOT NULL CHECK (stored_size_bytes > 0),
    storage_location TEXT NOT NULL UNIQUE,
    ciphertext_sha256 TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (media_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS upload_sessions (
    upload_id TEXT PRIMARY KEY,
    media_id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    media_fingerprint TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK (media_type IN ('image', 'video')),
    plaintext_size_bytes INTEGER NOT NULL CHECK (plaintext_size_bytes >= 0),
    datetime_taken TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    duration_seconds REAL,
    private_metadata_ciphertext TEXT,
    encryption_version INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL CHECK (total_chunks > 0),
    stored_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (stored_size_bytes >= 0),
    thumbnail_uploaded INTEGER NOT NULL DEFAULT 0 CHECK (thumbnail_uploaded IN (0, 1)),
    thumbnail_storage_location TEXT,
    thumbnail_stored_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (thumbnail_stored_size_bytes >= 0),
    thumbnail_ciphertext_sha256 TEXT,
    state TEXT NOT NULL DEFAULT 'uploading' CHECK (state IN ('uploading', 'finalizing')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (user_id, media_fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_upload_sessions_user ON upload_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_upload_sessions_stale ON upload_sessions(state, updated_at);

CREATE TABLE IF NOT EXISTS upload_chunks (
    upload_id TEXT NOT NULL REFERENCES upload_sessions(upload_id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    stored_size_bytes INTEGER NOT NULL CHECK (stored_size_bytes > 0),
    storage_location TEXT NOT NULL UNIQUE,
    ciphertext_sha256 TEXT NOT NULL,
    uploaded_at TEXT NOT NULL,
    PRIMARY KEY (upload_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS file_delete_queue (
    queue_id TEXT PRIMARY KEY,
    storage_location TEXT NOT NULL,
    queued_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS legacy_migration_issues (
    media_id TEXT PRIMARY KEY,
    reason TEXT NOT NULL,
    recorded_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS legacy_media_migrations (
    media_id TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('migrated', 'deleted')),
    migrated_at TEXT NOT NULL
);

-- Schema v4: operational activity for the admin console. Holds no media metadata
-- beyond opaque sizes. user_id has no foreign key so history survives user deletion.
CREATE TABLE IF NOT EXISTS activity_events (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TEXT NOT NULL,
    event_type TEXT NOT NULL,
    user_id TEXT,
    username TEXT,
    actor TEXT NOT NULL,
    bytes INTEGER,
    client_ip TEXT,
    details TEXT
);
CREATE INDEX IF NOT EXISTS idx_activity_events_time ON activity_events(occurred_at);
CREATE INDEX IF NOT EXISTS idx_activity_events_type_time ON activity_events(event_type, occurred_at);

CREATE TABLE IF NOT EXISTS console_admins (
    admin_id TEXT PRIMARY KEY,
    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS console_sessions (
    session_hash TEXT PRIMARY KEY,
    admin_id TEXT NOT NULL REFERENCES console_admins(admin_id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    client_ip TEXT,
    revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_console_sessions_admin ON console_sessions(admin_id);
"""


def _execute_schema(connection: sqlite3.Connection, schema: str) -> None:
    statement = ""
    for line in schema.splitlines():
        statement += line + "\n"
        if sqlite3.complete_statement(statement):
            if statement.strip():
                connection.execute(statement)
            statement = ""


def _table_exists(connection: sqlite3.Connection, name: str) -> bool:
    return connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (name,)
    ).fetchone() is not None


def _columns(connection: sqlite3.Connection, table: str) -> set[str]:
    return {row["name"] for row in connection.execute(f"PRAGMA table_info({table})")}


class Database:
    def __init__(self, path: Path, media_root: Path | None = None):
        self.path = path
        self.media_root = media_root

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = self.connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL
                )
                """
            )
            if _table_exists(connection, "media") and "original_filename" in _columns(connection, "media"):
                if not _table_exists(connection, "legacy_media_v1"):
                    for index_name in (
                        "idx_media_user_added",
                        "idx_media_user_taken",
                        "idx_media_user_type",
                        "idx_media_user_checksum",
                    ):
                        connection.execute(f"DROP INDEX IF EXISTS {index_name}")
                    connection.execute("ALTER TABLE media RENAME TO legacy_media_v1")
            _execute_schema(connection, CURRENT_SCHEMA)
            if _table_exists(connection, "upload_sessions"):
                upload_columns = _columns(connection, "upload_sessions")
                if "thumbnail_ciphertext_sha256" not in upload_columns:
                    connection.execute("ALTER TABLE upload_sessions ADD COLUMN thumbnail_ciphertext_sha256 TEXT")
            self._migrate_legacy_media(connection)
            connection.execute(
                "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
                (CURRENT_SCHEMA_VERSION,),
            )
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _migrate_legacy_media(self, connection: sqlite3.Connection) -> None:
        if not _table_exists(connection, "legacy_media_v1"):
            return
        legacy_columns = _columns(connection, "legacy_media_v1")
        rows = connection.execute("SELECT * FROM legacy_media_v1").fetchall()
        for row in rows:
            migration = connection.execute(
                "SELECT status FROM legacy_media_migrations WHERE media_id = ?", (row["media_id"],)
            ).fetchone()
            if migration is not None:
                continue
            if connection.execute("SELECT 1 FROM media WHERE media_id = ?", (row["media_id"],)).fetchone():
                connection.execute(
                    "INSERT OR IGNORE INTO legacy_media_migrations(media_id, status, migrated_at) VALUES (?, 'migrated', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
                    (row["media_id"],),
                )
                continue
            main_size = int(row["size_bytes"])
            thumbnail_size = int(row["thumbnail_size_bytes"]) if "thumbnail_size_bytes" in legacy_columns else 0
            if self.media_root is not None:
                try:
                    main_path = safe_path(self.media_root, row["storage_location"])
                    if not main_path.is_file():
                        raise FileNotFoundError("legacy original file is missing")
                    main_size = main_path.stat().st_size
                    if row["thumbnail_location"]:
                        thumbnail_path = safe_path(self.media_root, row["thumbnail_location"])
                        if not thumbnail_path.is_file():
                            raise FileNotFoundError("legacy thumbnail file is missing")
                        thumbnail_size = thumbnail_path.stat().st_size
                except (FileNotFoundError, OSError, ValueError, StorageError) as exc:
                    connection.execute(
                        """
                        INSERT INTO legacy_migration_issues(media_id, reason, recorded_at)
                        VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                        ON CONFLICT(media_id) DO UPDATE SET reason = excluded.reason, recorded_at = excluded.recorded_at
                        """,
                        (row["media_id"], str(exc)),
                    )
                    continue
            fingerprint = "legacy_" + hashlib.sha256(
                f"{row['user_id']}:{row['media_id']}".encode("utf-8")
            ).hexdigest()
            stored_size = main_size + thumbnail_size
            thumbnail_available = bool(row["thumbnail_location"] and thumbnail_size > 0)
            connection.execute(
                """
                INSERT INTO media (
                    media_id, user_id, media_fingerprint, mime_type, media_type,
                    plaintext_size_bytes, stored_size_bytes, datetime_added, datetime_taken,
                    width, height, duration_seconds, private_metadata_ciphertext,
                    thumbnail_available, thumbnail_storage_location,
                    thumbnail_stored_size_bytes, encryption_version, total_chunks,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 0, 1, ?, ?)
                """,
                (
                    row["media_id"], row["user_id"], fingerprint, row["mime_type"], row["media_type"],
                    row["size_bytes"], stored_size, row["datetime_added"], row["datetime_taken"],
                    row["width"], row["height"], row["duration_seconds"], int(thumbnail_available),
                    row["thumbnail_location"], thumbnail_size, row["datetime_added"], row["datetime_added"],
                ),
            )
            connection.execute(
                """
                INSERT INTO media_chunks (
                    media_id, chunk_index, stored_size_bytes, storage_location,
                    ciphertext_sha256, created_at
                ) VALUES (?, 0, ?, ?, NULL, ?)
                """,
                (row["media_id"], main_size, row["storage_location"], row["datetime_added"]),
            )
            connection.execute(
                "INSERT INTO legacy_media_migrations(media_id, status, migrated_at) VALUES (?, 'migrated', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
                (row["media_id"],),
            )
        for user in connection.execute("SELECT user_id, space_used_bytes, space_allocated_bytes FROM users"):
            migrated_usage = connection.execute(
                "SELECT COALESCE(SUM(stored_size_bytes), 0) FROM media WHERE user_id = ?", (user["user_id"],)
            ).fetchone()[0]
            corrected_usage = max(int(user["space_used_bytes"]), int(migrated_usage))
            corrected_allocation = max(int(user["space_allocated_bytes"]), corrected_usage)
            connection.execute(
                "UPDATE users SET space_used_bytes = ?, space_allocated_bytes = ? WHERE user_id = ?",
                (corrected_usage, corrected_allocation, user["user_id"]),
            )

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA busy_timeout = 30000")
        return connection

    @contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        connection = self.connect()
        try:
            yield connection
        finally:
            connection.close()

    @contextmanager
    def transaction(self, *, immediate: bool = False) -> Iterator[sqlite3.Connection]:
        connection = self.connect()
        try:
            connection.execute("BEGIN IMMEDIATE" if immediate else "BEGIN")
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
