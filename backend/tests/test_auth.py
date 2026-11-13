"""Auth: register, login, refresh rotation, logout, rate limiting."""
from __future__ import annotations

import pytest

from tests.conftest import auth_hdr, register

pytestmark = pytest.mark.asyncio


async def test_register_and_login(client):
    t = await register(client, "alice")
    assert t["access_token"] and t["refresh_token"]
    r = await client.post(
        "/api/v1/auth/login", json={"display_name": "alice", "password": "password123"}
    )
    assert r.status_code == 200
    assert "access_token" in r.json()


async def test_login_wrong_password(client):
    await register(client, "bob")
    r = await client.post(
        "/api/v1/auth/login", json={"display_name": "bob", "password": "wrongpass"}
    )
    assert r.status_code == 401


async def test_duplicate_display_name(client):
    await register(client, "carol")
    r = await client.post("/api/v1/auth/register", json={"display_name": "carol"})
    assert r.status_code == 409


async def test_first_account_is_admin(client):
    t = await register(client, "root1")
    r = await client.get("/api/v1/admin/users", headers=auth_hdr(t))
    assert r.status_code == 200
    t2 = await register(client, "root2")
    r = await client.get("/api/v1/admin/users", headers=auth_hdr(t2))
    assert r.status_code == 403


async def test_refresh_rotation_invalidates_old(client):
    t = await register(client, "dave")
    old = t["refresh_token"]
    r = await client.post("/api/v1/auth/refresh", json={"refresh_token": old})
    assert r.status_code == 200
    # old refresh token is single-use: reuse must fail
    r2 = await client.post("/api/v1/auth/refresh", json={"refresh_token": old})
    assert r2.status_code == 401
    # new one works
    new = r.json()["refresh_token"]
    r3 = await client.post("/api/v1/auth/refresh", json={"refresh_token": new})
    assert r3.status_code == 200


async def test_logout_revokes_refresh(client):
    t = await register(client, "erin")
    r = await client.post("/api/v1/auth/logout", json={"refresh_token": t["refresh_token"]})
    assert r.status_code == 204
    r = await client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]})
    assert r.status_code == 401


async def test_auth_rate_limited(client, app_env):
    from app.api import auth as auth_module

    original = auth_module.settings.auth_rate_limit
    auth_module.settings.auth_rate_limit = 5  # shrink window for the test
    try:
        for i in range(25):
            r = await client.post("/api/v1/auth/register", json={"display_name": f"rl{i}"})
            if r.status_code == 429:
                return
        pytest.fail("auth rate limit never triggered")
    finally:
        auth_module.settings.auth_rate_limit = original
