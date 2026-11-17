from __future__ import annotations

import hashlib
import os
import tempfile
from collections.abc import Iterator
from pathlib import Path


class StorageError(RuntimeError):
    pass


def safe_path(root: Path, relative_location: str) -> Path:
    if Path(relative_location).is_absolute():
        raise StorageError("absolute storage locations are forbidden")
    resolved_root = root.resolve()
    candidate = (resolved_root / relative_location).resolve()
    try:
        candidate.relative_to(resolved_root)
    except ValueError as exc:
        raise StorageError("storage location escapes the media root") from exc
    return candidate


def new_temporary_file(root: Path) -> Path:
    incoming = safe_path(root, ".incoming")
    incoming.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix="encrage-", suffix=".part", dir=incoming)
    os.close(descriptor)
    return Path(name)


def upload_chunk_location(
    user_id: str,
    upload_id: str,
    chunk_index: int,
    object_id: str | None = None,
) -> str:
    suffix = f"-{object_id}" if object_id else ""
    return f"{user_id}/.uploads/{upload_id}/original/{chunk_index:08d}{suffix}.chunk"


def upload_thumbnail_location(user_id: str, upload_id: str, object_id: str | None = None) -> str:
    suffix = f"-{object_id}" if object_id else ""
    return f"{user_id}/.uploads/{upload_id}/thumbnail{suffix}.bin"


def promoted_location(relative_location: str, user_id: str, upload_id: str, media_id: str) -> str:
    source_prefix = f"{user_id}/.uploads/{upload_id}/"
    if not relative_location.startswith(source_prefix):
        raise StorageError("upload object is outside its server-generated upload directory")
    return f"{user_id}/media/{media_id}/" + relative_location[len(source_prefix) :]


def upload_directory(root: Path, user_id: str, upload_id: str) -> Path:
    return safe_path(root, f"{user_id}/.uploads/{upload_id}")


def final_media_directory(root: Path, user_id: str, media_id: str) -> Path:
    return safe_path(root, f"{user_id}/media/{media_id}")


def install_file(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    os.replace(source, destination)


def iter_file(path: Path, block_size: int) -> Iterator[bytes]:
    with path.open("rb") as file:
        while chunk := file.read(block_size):
            yield chunk


def sha256_file(path: Path, block_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(block_size):
            digest.update(chunk)
    return digest.hexdigest()
