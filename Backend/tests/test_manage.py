from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import admin
from app.admin import AdminError
from app.main import create_app
from tests.test_api import add_user, bearer, login, make_settings


def test_reset_password_replaces_hash_and_revokes_every_session(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))
    with TestClient(app) as client:
        database = app.state.database
        add_user(database, "alice", "old-secure-password", 1_000_000)
        phone = login(client, "alice", "old-secure-password")
        tablet = login(client, "alice", "old-secure-password")

        user_id = admin.find_user_id(database, "ALICE")
        assert user_id is not None
        assert admin.set_user_password(database, user_id, "new-secure-password", actor="cli") == 2

        old = client.post("/auth/login", json={"username": "alice", "password": "old-secure-password"})
        assert old.status_code == 401
        for session in (phone, tablet):
            assert client.get("/users/me", headers=bearer(session["access_token"])).status_code == 401
            refreshed = client.post("/auth/refresh", json={"refresh_token": session["refresh_token"]})
            assert refreshed.status_code == 401

        fresh = login(client, "alice", "new-secure-password")
        assert client.get("/users/me", headers=bearer(fresh["access_token"])).status_code == 200


def test_reset_password_rejects_unknown_user_and_short_password(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path))
    with TestClient(app):
        database = app.state.database
        assert admin.find_user_id(database, "nobody") is None
        user_id = add_user(database, "bob", "bob-secure-password", 1_000)
        with pytest.raises(AdminError) as short:
            admin.set_user_password(database, user_id, "short", actor="cli")
        assert short.value.code == "password_too_short"
