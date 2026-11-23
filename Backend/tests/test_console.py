from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import activity, admin
from app.console import create_console_app
from app.main import create_app
from tests.test_api import add_user, bearer, complete_one_chunk_media, login, make_settings

ADMIN_PASSWORD = "console-admin-password"
CSRF = {"X-Encrage-Console": "1"}


@pytest.fixture()
def apps(tmp_path: Path):
    # Nothing listens on port 9, so the console reports the API as down instead of probing a real server.
    settings = dataclasses.replace(make_settings(tmp_path), console_api_url="http://127.0.0.1:9")
    api_app, console_app = create_app(settings), create_console_app(settings)
    with TestClient(api_app) as api, TestClient(console_app) as console:
        yield api, console, api_app.state.database, settings


def sign_in(console: TestClient, database) -> None:
    admin.set_console_admin(database, "root", ADMIN_PASSWORD)
    response = console.post("/api/login", headers=CSRF, json={"username": "root", "password": ADMIN_PASSWORD})
    assert response.status_code == 200, response.text
    cookie = response.headers["set-cookie"].lower()
    assert "httponly" in cookie and "samesite=strict" in cookie


def test_setup_login_throttle_and_csrf(apps) -> None:
    _, console, database, _ = apps
    assert console.get("/api/session").json()["setup_required"] is True
    assert console.get("/api/users").status_code == 401
    admin.set_console_admin(database, "root", ADMIN_PASSWORD)
    assert console.get("/api/session").json()["setup_required"] is False

    assert console.post("/api/login", json={"username": "root", "password": ADMIN_PASSWORD}).status_code == 403
    cross = console.post(
        "/api/login",
        headers={**CSRF, "Origin": "http://evil.example"},
        json={"username": "root", "password": ADMIN_PASSWORD},
    )
    assert cross.status_code == 403

    for _ in range(5):
        bad = console.post("/api/login", headers=CSRF, json={"username": "root", "password": "wrong-password!"})
        assert bad.status_code == 401
    locked = console.post("/api/login", headers=CSRF, json={"username": "root", "password": ADMIN_PASSWORD})
    assert locked.status_code == 429

    console.app.state.throttle.success("testclient")
    sign_in(console, database)
    assert console.get("/api/session").json()["authenticated"] is True
    # App users are not console admins.
    add_user(database, "alice", "alice-secure-password", 1_000)
    assert console.post("/api/logout", headers=CSRF).status_code == 204
    app_user = console.post("/api/login", headers=CSRF, json={"username": "alice", "password": "alice-secure-password"})
    assert app_user.status_code == 401
    assert console.get("/api/users").status_code == 401


def test_resetting_console_admin_password_signs_it_out(apps) -> None:
    _, console, database, _ = apps
    sign_in(console, database)
    assert admin.set_console_admin(database, "root", "another-admin-password") is False
    assert console.get("/api/users").status_code == 401


def test_user_lifecycle_through_console(apps) -> None:
    api, console, database, settings = apps
    sign_in(console, database)

    weak = console.post("/api/users", headers=CSRF, json={"username": "carol", "password": "short", "quota_bytes": 10})
    assert weak.status_code == 422
    created = console.post(
        "/api/users", headers=CSRF,
        json={"username": "carol", "password": "carol-secure-password", "quota_bytes": 1_000_000},
    )
    assert created.status_code == 201, created.text
    duplicate = console.post(
        "/api/users", headers=CSRF, json={"username": "CAROL", "password": "carol-secure-password", "quota_bytes": 1}
    )
    assert duplicate.status_code == 409
    user_id = created.json()["user_id"]

    headers = bearer(login(api, "carol", "carol-secure-password")["access_token"])
    complete_one_chunk_media(api, headers, "photo-1", taken="2026-01-02T03:04:05Z")
    listed = next(u for u in console.get("/api/users").json()["users"] if u["user_id"] == user_id)
    used = listed["space_used_bytes"]
    assert used > 0 and listed["media_count"] == 1

    below = console.put(f"/api/users/{user_id}/quota", headers=CSRF, json={"quota_bytes": used - 1})
    assert below.status_code == 409 and below.json()["error"]["code"] == "quota_below_usage"
    assert console.put(f"/api/users/{user_id}/quota", headers=CSRF, json={"quota_bytes": 5_000_000}).status_code == 200
    assert api.get("/users/me/storage", headers=headers).json()["space_allocated_bytes"] == 5_000_000

    assert console.put(f"/api/users/{user_id}/active", headers=CSRF, json={"active": False}).status_code == 200
    assert api.get("/users/me", headers=headers).status_code == 401
    assert api.post("/auth/login", json={"username": "carol", "password": "carol-secure-password"}).status_code == 401
    assert console.put(f"/api/users/{user_id}/active", headers=CSRF, json={"active": True}).status_code == 200

    reset = console.post(f"/api/users/{user_id}/password", headers=CSRF, json={"password": "carol-new-password"})
    assert reset.status_code == 200
    headers = bearer(login(api, "carol", "carol-new-password")["access_token"])
    assert console.post(f"/api/users/{user_id}/revoke-sessions", headers=CSRF).json()["sessions_revoked"] == 1
    assert api.get("/users/me", headers=headers).status_code == 401

    user_dir = settings.media_root / user_id
    assert user_dir.is_dir()
    mismatch = console.request("DELETE", f"/api/users/{user_id}", headers=CSRF, json={"confirm_username": "carl"})
    assert mismatch.status_code == 422
    deleted = console.request("DELETE", f"/api/users/{user_id}", headers=CSRF, json={"confirm_username": "Carol"})
    assert deleted.status_code == 200, deleted.text
    assert deleted.json()["media_count"] == 1 and deleted.json()["files_removed"] is True
    assert not user_dir.exists()
    assert console.get("/api/users").json()["users"] == []
    with database.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM media").fetchone()[0] == 0
        assert connection.execute("SELECT COUNT(*) FROM file_delete_queue").fetchone()[0] == 0

    types = [e["event_type"] for e in console.get("/api/activity").json()["events"]]
    for expected in (
        activity.USER_CREATED, activity.UPLOAD_COMPLETED, activity.QUOTA_CHANGED, activity.USER_DISABLED,
        activity.PASSWORD_RESET, activity.SESSIONS_REVOKED, activity.USER_DELETED, activity.LOGIN,
    ):
        assert expected in types
    bad_id = console.request("DELETE", "/api/users/not-a-uuid", headers=CSRF, json={"confirm_username": "x"})
    assert bad_id.status_code == 404


def test_overview_reports_health_and_usage(apps) -> None:
    api, console, database, _ = apps
    sign_in(console, database)
    add_user(database, "dave", "dave-secure-password", 10_000_000)
    assert api.post("/auth/login", json={"username": "dave", "password": "wrong"}).status_code == 401
    assert api.post("/auth/login", json={"username": "nobody", "password": "wrong"}).status_code == 401
    headers = bearer(login(api, "dave", "dave-secure-password")["access_token"])
    media, _, _ = complete_one_chunk_media(api, headers, "photo-2", taken="2026-01-02T03:04:05Z")
    assert api.delete(f"/media/{media['media_id']}", headers=headers).status_code == 204

    assert console.get("/api/overview?days=14").status_code == 422
    body = console.get("/api/overview?days=7").json()
    usage, health = body["usage"], body["health"]
    assert len(usage["daily"]) == 7
    assert usage["totals"]["uploads"] == 0  # the upload was deleted again, so no media row remains
    assert usage["totals"]["deletions"] == 1
    assert usage["totals"]["logins"] == 1
    assert usage["totals"]["failed_logins"] == 2
    assert usage["totals"]["active_users"] == 1
    assert health["api"]["status"] == "down" and health["status"] == "critical"
    assert health["database"]["schema_version"] == health["database"]["expected_schema_version"]
    assert health["totals"]["users"] == 1

    failed = console.get("/api/activity?event_type=login_failed").json()["events"]
    assert {e["username"] for e in failed} == {"dave", None}  # unknown usernames are never stored
    assert console.post("/api/health/integrity", headers=CSRF).json()["ok"] is True


def test_console_routes_are_not_on_the_user_api(apps) -> None:
    api, _, _, _ = apps
    assert api.get("/api/users").status_code == 404
    assert api.get("/api/overview").status_code == 404


def test_console_page_and_security_headers(apps) -> None:
    _, console, _, _ = apps
    page = console.get("/")
    assert page.status_code == 200 and "Encrage Console" in page.text
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
    assert console.get("/static/console.js").status_code == 200
    icon = console.get("/static/icon.svg")
    assert icon.status_code == 200 and icon.headers["content-type"].startswith("image/svg+xml")
    assert console.get("/static/index.html").status_code == 404
    assert console.get("/docs").status_code == 404


def test_old_events_are_pruned(apps) -> None:
    _, _, database, settings = apps
    with database.transaction() as connection:
        activity.record_event(connection, activity.LOGIN, actor="user")
        connection.execute("UPDATE activity_events SET occurred_at = '2000-01-01T00:00:00Z'")
        activity.record_event(connection, activity.LOGOUT, actor="user")
    assert activity.prune_events(database, settings.activity_retention_days) == 1
