from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

def _positive_int(name: str, default: int) -> int:
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    if value <= 0:
        raise RuntimeError(f"{name} must be positive")
    return value


@dataclass(frozen=True)
class Settings:
    database_path: Path
    media_root: Path
    jwt_secret: str
    access_token_minutes: int = 60
    refresh_token_days: int = 30
    max_chunk_bytes: int = 5 * 1024 * 1024
    max_thumbnail_bytes: int = 10 * 1024 * 1024
    max_total_chunks: int = 1_000_000
    max_private_metadata_chars: int = 1_000_000
    upload_ttl_hours: int = 168
    stream_block_bytes: int = 1024 * 1024
    environment: str = "development"
    console_session_hours: int = 12
    activity_retention_days: int = 90
    console_api_url: str = "http://127.0.0.1:8000"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    environment = os.getenv("ENCRAGE_ENV", "development").strip().lower()
    jwt_secret = os.getenv("ENCRAGE_JWT_SECRET", "")
    if len(jwt_secret.encode("utf-8")) < 32:
        raise RuntimeError("ENCRAGE_JWT_SECRET must contain at least 32 bytes")

    return Settings(
        database_path=Path(os.getenv("ENCRAGE_DATABASE_PATH", "./data/encrage.sqlite3")).resolve(),
        media_root=Path(os.getenv("ENCRAGE_MEDIA_ROOT", "./data/media")).resolve(),
        jwt_secret=jwt_secret,
        access_token_minutes=_positive_int("ENCRAGE_ACCESS_TOKEN_MINUTES", 60),
        refresh_token_days=_positive_int("ENCRAGE_REFRESH_TOKEN_DAYS", 30),
        max_chunk_bytes=_positive_int("ENCRAGE_MAX_CHUNK_BYTES", 5 * 1024 * 1024),
        max_thumbnail_bytes=_positive_int("ENCRAGE_MAX_THUMBNAIL_BYTES", 10 * 1024 * 1024),
        max_total_chunks=_positive_int("ENCRAGE_MAX_TOTAL_CHUNKS", 1_000_000),
        max_private_metadata_chars=_positive_int("ENCRAGE_MAX_PRIVATE_METADATA_CHARS", 1_000_000),
        upload_ttl_hours=_positive_int("ENCRAGE_UPLOAD_TTL_HOURS", 168),
        stream_block_bytes=_positive_int("ENCRAGE_STREAM_BLOCK_BYTES", 1024 * 1024),
        environment=environment,
        console_session_hours=_positive_int("ENCRAGE_CONSOLE_SESSION_HOURS", 12),
        activity_retention_days=_positive_int("ENCRAGE_ACTIVITY_RETENTION_DAYS", 90),
        console_api_url=os.getenv("ENCRAGE_CONSOLE_API_URL", "http://127.0.0.1:8000").rstrip("/"),
    )
