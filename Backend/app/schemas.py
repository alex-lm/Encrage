from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


FINGERPRINT_PATTERN = r"^[A-Za-z0-9+/=_-]{32,512}$"
MIME_PATTERN = r"^(image|video)/[A-Za-z0-9][A-Za-z0-9.+-]{0,110}$"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class LoginRequest(StrictModel):
    username: str = Field(min_length=1, max_length=255)
    password: str = Field(min_length=1, max_length=1024)


class ChangePasswordRequest(StrictModel):
    current_password: str = Field(min_length=1, max_length=1024)
    new_password: str = Field(min_length=12, max_length=1024)


class RefreshRequest(StrictModel):
    refresh_token: str = Field(min_length=32, max_length=512)


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in: int


class UserResponse(BaseModel):
    user_id: str
    username: str
    created_at: datetime


class StorageResponse(BaseModel):
    space_allocated_bytes: int
    space_used_bytes: int
    space_available_bytes: int


class UploadCreateRequest(StrictModel):
    media_fingerprint: str = Field(pattern=FINGERPRINT_PATTERN)
    mime_type: str = Field(pattern=MIME_PATTERN, max_length=127)
    media_type: Literal["image", "video"]
    plaintext_size_bytes: int = Field(gt=0)
    datetime_taken: datetime | None = None
    width: int | None = Field(default=None, gt=0)
    height: int | None = Field(default=None, gt=0)
    duration_seconds: float | None = Field(default=None, ge=0)
    private_metadata_ciphertext: str | None = None
    encryption_version: int
    total_chunks: int = Field(gt=0)


class UploadCreateResponse(BaseModel):
    upload_id: str | None
    media_id: str
    already_exists: bool
    upload_in_progress: bool = False


class UploadStatusResponse(BaseModel):
    upload_id: str
    media_id: str
    total_chunks: int
    uploaded_chunks: list[int]
    thumbnail_uploaded: bool
    stored_size_bytes: int
    state: str
    created_at: datetime
    updated_at: datetime


class ChunkUploadResponse(BaseModel):
    chunk_index: int
    stored_size_bytes: int
    ciphertext_sha256: str
    idempotent: bool


class ThumbnailUploadResponse(BaseModel):
    stored_size_bytes: int
    ciphertext_sha256: str
    idempotent: bool


class MediaResponse(BaseModel):
    media_id: str
    media_fingerprint: str
    media_type: Literal["image", "video"]
    mime_type: str
    plaintext_size_bytes: int
    stored_size_bytes: int
    datetime_added: datetime
    datetime_taken: datetime
    width: int | None
    height: int | None
    duration_seconds: float | None
    private_metadata_ciphertext: str | None
    thumbnail_available: bool
    encryption_version: int
    total_chunks: int
    created_at: datetime
    updated_at: datetime


class MediaListResponse(BaseModel):
    items: list[MediaResponse]
    next_cursor: str | None


class FingerprintCheckRequest(StrictModel):
    media_fingerprints: list[str] = Field(min_length=1, max_length=1000)


class FingerprintMatch(BaseModel):
    media_fingerprint: str
    media_id: str


class FingerprintCheckResponse(BaseModel):
    matches: list[FingerprintMatch]


class ErrorDetail(BaseModel):
    code: str
    message: str
    details: object | None = None


class ErrorResponse(BaseModel):
    error: ErrorDetail
