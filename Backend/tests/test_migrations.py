from __future__ import annotations

import sqlite3
import uuid
from pathlib import Path

from app.database import CURRENT_SCHEMA_VERSION, Database


LEGACY_SCHEMA = """
CREATE TABLE users (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
    password_hash TEXT NOT NULL,
    space_allocated_bytes INTEGER NOT NULL,
    space_used_bytes INTEGER NOT NULL DEFAULT 0,
    folder_location TEXT NOT NULL UNIQUE,
    encryption_enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE media (
    media_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(user_id),
    original_filename TEXT NOT NULL,
    storage_location TEXT NOT NULL UNIQUE,
    size_bytes INTEGER NOT NULL,
    mime_type TEXT NOT NULL,
    media_type TEXT NOT NULL,
    datetime_added TEXT NOT NULL,
    datetime_taken TEXT NOT NULL,
    latitude REAL,
    longitude REAL,
    checksum_sha256 TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    duration_seconds REAL,
    thumbnail_location TEXT,
    thumbnail_size_bytes INTEGER NOT NULL DEFAULT 0,
    thumbnail_mime_type TEXT
);
CREATE INDEX idx_media_user_added ON media(user_id, datetime_added DESC);
CREATE INDEX idx_media_user_taken ON media(user_id, datetime_taken DESC);
CREATE INDEX idx_media_user_type ON media(user_id, media_type);
CREATE INDEX idx_media_user_checksum ON media(user_id, checksum_sha256);
"""


def test_legacy_rows_and_files_are_preserved_with_safe_defaults(tmp_path: Path) -> None:
    database_path = tmp_path / "legacy.sqlite3"
    media_root = tmp_path / "media"
    user_id = str(uuid.uuid4())
    media_id = str(uuid.uuid4())
    original_location = f"{user_id}/{media_id}.bin"
    thumbnail_location = f"{user_id}/thumbnails/{media_id}.bin"
    original = b"legacy-stored-original-bytes"
    thumbnail = b"legacy-stored-thumbnail-bytes"
    original_path = media_root / original_location
    thumbnail_path = media_root / thumbnail_location
    original_path.parent.mkdir(parents=True)
    thumbnail_path.parent.mkdir(parents=True)
    original_path.write_bytes(original)
    thumbnail_path.write_bytes(thumbnail)

    connection = sqlite3.connect(database_path)
    connection.executescript(LEGACY_SCHEMA)
    now = "2026-01-01T00:00:00Z"
    connection.execute(
        "INSERT INTO users VALUES (?, 'legacy', 'argon-hash', 1000000, ?, ?, 1, ?, ?, 1)",
        (user_id, len(original) + len(thumbnail), user_id, now, now),
    )
    connection.execute(
        """
        INSERT INTO media VALUES (
            ?, ?, 'private-name.jpg', ?, ?, 'image/jpeg', 'image', ?, ?,
            1.0, 2.0, 'old-checksum', 10, 20, NULL, ?, ?, 'image/jpeg'
        )
        """,
        (
            media_id,
            user_id,
            original_location,
            len(original),
            now,
            now,
            thumbnail_location,
            len(thumbnail),
        ),
    )
    connection.commit()
    connection.close()

    database = Database(database_path, media_root)
    database.initialize()
    database.initialize()

    with database.connection() as connection:
        migrated = connection.execute("SELECT * FROM media WHERE media_id = ?", (media_id,)).fetchone()
        chunks = connection.execute("SELECT * FROM media_chunks WHERE media_id = ?", (media_id,)).fetchall()
        version = connection.execute("SELECT MAX(version) FROM schema_migrations").fetchone()[0]
        legacy_count = connection.execute("SELECT COUNT(*) FROM legacy_media_v1").fetchone()[0]
    assert version == CURRENT_SCHEMA_VERSION
    assert legacy_count == 1
    assert migrated["encryption_version"] == 0
    assert migrated["media_fingerprint"].startswith("legacy_")
    assert migrated["private_metadata_ciphertext"] is None
    assert migrated["stored_size_bytes"] == len(original) + len(thumbnail)
    assert len(chunks) == 1
    assert original_path.read_bytes() == original
    assert thumbnail_path.read_bytes() == thumbnail
