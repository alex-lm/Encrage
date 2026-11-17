from __future__ import annotations

import hashlib
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.database import Database
from app.main import cleanup_abandoned_uploads, create_app
from app.security import hash_password, utc_iso


def make_settings(tmp_path: Path, *, quota_chunk_limit: int = 5 * 1024 * 1024) -> Settings:
    return Settings(
        database_path=tmp_path / "encrage.sqlite3",
        media_root=tmp_path / "media",
        jwt_secret="test-jwt-secret-that-is-at-least-32-bytes-long",
        access_token_minutes=60,
        refresh_token_days=30,
        max_chunk_bytes=quota_chunk_limit,
        max_thumbnail_bytes=1024 * 1024,
        max_total_chunks=1000,
        max_private_metadata_chars=10_000,
        upload_ttl_hours=24,
        stream_block_bytes=17,
        environment="test",
    )


def add_user(database: Database, username: str, password: str, quota: int) -> str:
    user_id = str(uuid.uuid4())
    now = utc_iso()
    with database.transaction(immediate=True) as connection:
        connection.execute(
            """
            INSERT INTO users (
                user_id, username, password_hash, space_allocated_bytes,
                space_used_bytes, folder_location, created_at, updated_at, is_active
            ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, 1)
            """,
            (user_id, username, hash_password(password), quota, user_id, now, now),
        )
    return user_id


def login(client: TestClient, username: str, password: str) -> dict:
    response = client.post("/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200, response.text
    return response.json()


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def fingerprint(seed: str) -> str:
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()


def upload_metadata(seed: str, *, total_chunks: int = 2, taken: str = "2026-01-02T03:04:05Z") -> dict:
    return {
        "media_fingerprint": fingerprint(seed),
        "mime_type": "image/jpeg",
        "media_type": "image",
        "plaintext_size_bytes": 8_000_000,
        "datetime_taken": taken,
        "width": 4032,
        "height": 3024,
        "duration_seconds": None,
        "private_metadata_ciphertext": "opaque-private-metadata",
        "encryption_version": 1,
        "total_chunks": total_chunks,
    }


def create_upload(client: TestClient, headers: dict[str, str], seed: str, **kwargs) -> dict:
    response = client.post("/media/uploads", headers=headers, json=upload_metadata(seed, **kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def complete_one_chunk_media(
    client: TestClient,
    headers: dict[str, str],
    seed: str,
    *,
    taken: str,
) -> tuple[dict, bytes, bytes]:
    created = create_upload(client, headers, seed, total_chunks=1, taken=taken)
    chunk = f"encrypted-main-{seed}".encode()
    thumbnail = f"encrypted-thumbnail-{seed}".encode()
    assert client.put(
        f"/media/uploads/{created['upload_id']}/chunks/0",
        headers={**headers, "Content-Type": "application/octet-stream"},
        content=chunk,
    ).status_code == 201
    assert client.put(
        f"/media/uploads/{created['upload_id']}/thumbnail",
        headers={**headers, "Content-Type": "application/octet-stream"},
        content=thumbnail,
    ).status_code == 201
    completed = client.post(f"/media/uploads/{created['upload_id']}/complete", headers=headers)
    assert completed.status_code == 200, completed.text
    return completed.json(), chunk, thumbnail


def test_authentication_login_refresh_rotation_and_logout(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        add_user(app.state.database, "alice", "a-secure-password", 1_000_000)

        failed = client.post("/auth/login", json={"username": "alice", "password": "wrong"})
        assert failed.status_code == 401
        assert failed.json()["error"]["code"] == "invalid_credentials"

        tokens = login(client, "alice", "a-secure-password")
        assert tokens["token_type"] == "bearer"
        assert tokens["expires_in"] == 3600
        assert client.get("/users/me", headers=bearer(tokens["access_token"])).status_code == 200

        rotated = client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
        assert rotated.status_code == 200
        rotated_tokens = rotated.json()
        assert rotated_tokens["refresh_token"] != tokens["refresh_token"]
        assert client.get("/users/me", headers=bearer(rotated_tokens["access_token"])).status_code == 200

        reused = client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
        assert reused.status_code == 401
        assert client.get("/users/me", headers=bearer(rotated_tokens["access_token"])).status_code == 401

        fresh_session = login(client, "alice", "a-secure-password")
        assert client.post("/auth/logout", headers=bearer(fresh_session["access_token"])).status_code == 204
        assert client.get("/users/me", headers=bearer(fresh_session["access_token"])).status_code == 401
        assert client.post(
            "/auth/refresh", json={"refresh_token": fresh_session["refresh_token"]}
        ).status_code == 401


def test_change_password_requires_token_and_current_password(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        add_user(app.state.database, "alice", "a-secure-password", 1_000_000)
        first = login(client, "alice", "a-secure-password")
        second = login(client, "alice", "a-secure-password")

        unauthenticated = client.post(
            "/auth/change-password",
            json={"current_password": "a-secure-password", "new_password": "a-newer-password"},
        )
        assert unauthenticated.status_code == 401

        wrong_password = client.post(
            "/auth/change-password",
            headers=bearer(first["access_token"]),
            json={"current_password": "wrong-password", "new_password": "a-newer-password"},
        )
        assert wrong_password.status_code == 401
        assert wrong_password.json()["error"]["code"] == "invalid_credentials"

        too_short = client.post(
            "/auth/change-password",
            headers=bearer(first["access_token"]),
            json={"current_password": "a-secure-password", "new_password": "short"},
        )
        assert too_short.status_code == 422

        changed = client.post(
            "/auth/change-password",
            headers=bearer(first["access_token"]),
            json={"current_password": "a-secure-password", "new_password": "a-newer-password"},
        )
        assert changed.status_code == 204
        assert client.get("/users/me", headers=bearer(first["access_token"])).status_code == 200
        assert client.get("/users/me", headers=bearer(second["access_token"])).status_code == 401
        assert client.post("/auth/login", json={"username": "alice", "password": "a-secure-password"}).status_code == 401
        assert client.post("/auth/login", json={"username": "alice", "password": "a-newer-password"}).status_code == 200


def test_resumable_upload_exact_round_trip_dedup_and_delete(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        database = app.state.database
        alice_id = add_user(database, "alice", "a-secure-password", 10_000_000)
        headers = bearer(login(client, "alice", "a-secure-password")["access_token"])
        created = create_upload(client, headers, "photo", total_chunks=3)
        upload_id = created["upload_id"]
        chunks = {0: b"ciphertext-zero", 1: b"ciphertext-one", 2: b"ciphertext-two"}
        thumbnail = b"ciphertext-thumbnail"

        assert client.put(f"/media/uploads/{upload_id}/chunks/0", headers=headers, content=chunks[0]).status_code == 201
        assert client.put(f"/media/uploads/{upload_id}/chunks/2", headers=headers, content=chunks[2]).status_code == 201
        duplicate = client.put(f"/media/uploads/{upload_id}/chunks/0", headers=headers, content=chunks[0])
        assert duplicate.status_code == 200
        assert duplicate.json()["idempotent"] is True
        replacement = b"replacement-ciphertext-zero"
        replaced = client.put(f"/media/uploads/{upload_id}/chunks/0", headers=headers, content=replacement)
        assert replaced.status_code == 200
        assert replaced.json()["idempotent"] is False
        chunks[0] = replacement
        assert client.put(f"/media/uploads/{upload_id}/thumbnail", headers=headers, content=thumbnail).status_code == 201

        status_response = client.get(f"/media/uploads/{upload_id}", headers=headers)
        assert status_response.json()["uploaded_chunks"] == [0, 2]
        assert status_response.json()["thumbnail_uploaded"] is True
        incomplete = client.post(f"/media/uploads/{upload_id}/complete", headers=headers)
        assert incomplete.status_code == 409
        assert incomplete.json()["error"]["code"] == "upload_incomplete"

        assert client.put(f"/media/uploads/{upload_id}/chunks/1", headers=headers, content=chunks[1]).status_code == 201
        completed = client.post(f"/media/uploads/{upload_id}/complete", headers=headers)
        assert completed.status_code == 200, completed.text
        media = completed.json()
        media_id = media["media_id"]
        expected_stored = sum(map(len, chunks.values())) + len(thumbnail)
        assert media["stored_size_bytes"] == expected_stored
        assert media["total_chunks"] == 3
        assert media["private_metadata_ciphertext"] == "opaque-private-metadata"
        completed_files = [path for path in (settings.media_root / alice_id / "media" / media_id).rglob("*") if path.is_file()]
        assert len(completed_files) == 4

        for index, expected in chunks.items():
            downloaded = client.get(f"/media/{media_id}/chunks/{index}", headers=headers)
            assert downloaded.status_code == 200
            assert downloaded.content == expected
            assert downloaded.headers["content-type"] == "application/octet-stream"
        downloaded_thumbnail = client.get(f"/media/{media_id}/thumbnail", headers=headers)
        assert downloaded_thumbnail.content == thumbnail

        duplicate_create = client.post("/media/uploads", headers=headers, json=upload_metadata("photo", total_chunks=3))
        assert duplicate_create.status_code == 200
        assert duplicate_create.json() == {
            "upload_id": None,
            "media_id": media_id,
            "already_exists": True,
            "upload_in_progress": False,
        }
        checked = client.post(
            "/media/fingerprints/check",
            headers=headers,
            json={"media_fingerprints": [fingerprint("photo"), fingerprint("absent")]},
        )
        assert checked.json()["matches"] == [{"media_fingerprint": fingerprint("photo"), "media_id": media_id}]

        storage = client.get("/users/me/storage", headers=headers).json()
        assert storage["space_used_bytes"] == expected_stored
        assert client.delete(f"/media/{media_id}", headers=headers).status_code == 204
        assert client.get(f"/media/{media_id}", headers=headers).status_code == 404
        assert client.get("/users/me/storage", headers=headers).json()["space_used_bytes"] == 0


def test_all_upload_and_media_operations_are_owner_scoped(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        database = app.state.database
        add_user(database, "alice", "a-secure-password", 1_000_000)
        add_user(database, "bob", "another-secure-password", 1_000_000)
        alice = bearer(login(client, "alice", "a-secure-password")["access_token"])
        bob = bearer(login(client, "bob", "another-secure-password")["access_token"])
        created = create_upload(client, alice, "private", total_chunks=1)
        upload_id = created["upload_id"]

        assert client.get(f"/media/uploads/{upload_id}", headers=bob).status_code == 404
        assert client.put(f"/media/uploads/{upload_id}/chunks/0", headers=bob, content=b"attack").status_code == 404
        assert client.put(f"/media/uploads/{upload_id}/thumbnail", headers=bob, content=b"attack").status_code == 404
        assert client.post(f"/media/uploads/{upload_id}/complete", headers=bob).status_code == 404
        assert client.delete(f"/media/uploads/{upload_id}", headers=bob).status_code == 404

        assert client.put(f"/media/uploads/{upload_id}/chunks/0", headers=alice, content=b"main").status_code == 201
        assert client.put(f"/media/uploads/{upload_id}/thumbnail", headers=alice, content=b"thumb").status_code == 201
        media_id = client.post(f"/media/uploads/{upload_id}/complete", headers=alice).json()["media_id"]

        assert client.get("/media", headers=bob).json()["items"] == []
        assert client.get(f"/media/{media_id}", headers=bob).status_code == 404
        assert client.get(f"/media/{media_id}/thumbnail", headers=bob).status_code == 404
        assert client.get(f"/media/{media_id}/chunks/0", headers=bob).status_code == 404
        assert client.delete(f"/media/{media_id}", headers=bob).status_code == 404
        assert client.get(f"/media/{media_id}", headers=alice).status_code == 200


def test_quota_reservations_are_concurrency_safe(tmp_path: Path) -> None:
    chunk = b"c" * 700
    thumbnail = b"t" * 100
    settings = make_settings(tmp_path, quota_chunk_limit=1000)
    app = create_app(settings)
    with TestClient(app) as client:
        database = app.state.database
        add_user(database, "racer", "a-secure-password", len(chunk) + len(thumbnail))
        headers = bearer(login(client, "racer", "a-secure-password")["access_token"])
        first = create_upload(client, headers, "race-one", total_chunks=1)
        second = create_upload(client, headers, "race-two", total_chunks=1)

        def put_chunk(upload_id: str) -> tuple[str, int]:
            with TestClient(app) as threaded_client:
                result = threaded_client.put(
                    f"/media/uploads/{upload_id}/chunks/0", headers=headers, content=chunk
                )
                return upload_id, result.status_code

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(put_chunk, [first["upload_id"], second["upload_id"]]))
        assert sorted(status_code for _, status_code in results) == [201, 413]
        successful_upload = next(upload_id for upload_id, code in results if code == 201)
        assert client.put(
            f"/media/uploads/{successful_upload}/thumbnail", headers=headers, content=thumbnail
        ).status_code == 201
        assert client.post(f"/media/uploads/{successful_upload}/complete", headers=headers).status_code == 200
        storage = client.get("/users/me/storage", headers=headers).json()
        assert storage["space_used_bytes"] == storage["space_allocated_bytes"] == len(chunk) + len(thumbnail)


def test_cleanup_abandoned_upload_and_cursor_pagination(tmp_path: Path) -> None:
    settings = make_settings(tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        database = app.state.database
        add_user(database, "alice", "a-secure-password", 10_000_000)
        add_user(database, "bob", "another-secure-password", 10_000_000)
        alice = bearer(login(client, "alice", "a-secure-password")["access_token"])
        bob = bearer(login(client, "bob", "another-secure-password")["access_token"])

        abandoned = create_upload(client, alice, "abandoned", total_chunks=1)
        assert client.put(
            f"/media/uploads/{abandoned['upload_id']}/chunks/0", headers=alice, content=b"orphan"
        ).status_code == 201
        old = utc_iso(datetime.now(timezone.utc) - timedelta(days=2))
        with database.transaction(immediate=True) as connection:
            connection.execute(
                "UPDATE upload_sessions SET updated_at = ? WHERE upload_id = ?", (old, abandoned["upload_id"])
            )
        assert cleanup_abandoned_uploads(database, settings) == 1
        assert client.get(f"/media/uploads/{abandoned['upload_id']}", headers=alice).status_code == 404

        expected_ids = []
        for index, taken in enumerate(
            ["2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", "2026-02-01T00:00:00Z"]
        ):
            media, _, _ = complete_one_chunk_media(client, alice, f"page-{index}", taken=taken)
            expected_ids.append((taken, media["media_id"]))
        complete_one_chunk_media(client, bob, "bob-only", taken="2027-01-01T00:00:00Z")

        first_page = client.get("/media?limit=2", headers=alice).json()
        assert [item["media_id"] for item in first_page["items"]] == [
            media_id for _, media_id in sorted(expected_ids, reverse=True)[:2]
        ]
        assert first_page["next_cursor"]
        second_page = client.get(
            "/media", headers=alice, params={"limit": 2, "cursor": first_page["next_cursor"]}
        ).json()
        assert [item["media_id"] for item in second_page["items"]] == [
            media_id for _, media_id in sorted(expected_ids, reverse=True)[2:]
        ]
        assert len(client.get("/media", headers=bob).json()["items"]) == 1


def test_protocol_and_chunk_validation_errors(tmp_path: Path) -> None:
    settings = make_settings(tmp_path, quota_chunk_limit=4)
    app = create_app(settings)
    with TestClient(app) as client:
        add_user(app.state.database, "alice", "a-secure-password", 1_000_000)
        headers = bearer(login(client, "alice", "a-secure-password")["access_token"])
        unsupported = upload_metadata("unsupported", total_chunks=1)
        unsupported["encryption_version"] = 99
        response = client.post("/media/uploads", headers=headers, json=unsupported)
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "unsupported_protocol_version"

        upload = create_upload(client, headers, "limits", total_chunks=1)
        invalid_index = client.put(
            f"/media/uploads/{upload['upload_id']}/chunks/1", headers=headers, content=b"x"
        )
        assert invalid_index.status_code == 422
        too_large = client.put(
            f"/media/uploads/{upload['upload_id']}/chunks/0", headers=headers, content=b"12345"
        )
        assert too_large.status_code == 413
        assert too_large.json()["error"]["code"] == "chunk_too_large"
        incoming_files = list((settings.media_root / ".incoming").glob("*.part"))
        assert incoming_files == []
