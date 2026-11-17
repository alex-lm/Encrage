from __future__ import annotations

import hashlib
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from .config import Settings


PASSWORD_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4)
JWT_ALGORITHM = "HS256"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def utc_iso(value: datetime | None = None) -> str:
    return (value or utc_now()).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def hash_password(password: str) -> str:
    return PASSWORD_HASHER.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return PASSWORD_HASHER.verify(password_hash, password)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


@dataclass(frozen=True)
class AccessToken:
    token: str
    expires_at: datetime


@dataclass(frozen=True)
class RefreshToken:
    token: str
    token_id: str
    expires_at: datetime


def issue_access_token(settings: Settings, user_id: str, session_id: str) -> AccessToken:
    now = utc_now()
    expires_at = now + timedelta(minutes=settings.access_token_minutes)
    payload = {
        "sub": user_id,
        "sid": session_id,
        "jti": str(uuid.uuid4()),
        "iat": now,
        "nbf": now,
        "exp": expires_at,
        "type": "access",
    }
    encoded = jwt.encode(payload, settings.jwt_secret, algorithm=JWT_ALGORITHM)
    return AccessToken(encoded, expires_at)


def issue_refresh_token(settings: Settings) -> RefreshToken:
    return RefreshToken(
        token=secrets.token_urlsafe(48),
        token_id=str(uuid.uuid4()),
        expires_at=utc_now() + timedelta(days=settings.refresh_token_days),
    )


def decode_access_token(settings: Settings, token: str) -> dict:
    return jwt.decode(
        token,
        settings.jwt_secret,
        algorithms=[JWT_ALGORITHM],
        options={"require": ["sub", "sid", "jti", "iat", "exp", "type"]},
    )
