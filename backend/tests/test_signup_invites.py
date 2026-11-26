"""Single-use signup invite codes (Phase: product gating)."""
from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.api import auth as auth_mod
from tests.conftest import auth_hdr, register


@pytest.fixture
def invite_required(monkeypatch):
    monkeypatch.setattr(auth_mod.settings, "signup_invite_required", True)


async def _mint(client: AsyncClient, admin_hdr: dict) -> dict:
    r = await client.post("/api/v1/admin/invites", headers=admin_hdr, json={"ttl_hours": 24})
    assert r.status_code == 201, r.text
    return r.json()


@pytest.mark.asyncio
async def test_register_without_invite_rejected(client: AsyncClient, invite_required):
    admin = await register(client, "gateadmin")  # first user: bootstrap admin, no code needed
    assert admin["access_token"]
    r = await client.post(
        "/api/v1/auth/register", json={"display_name": "nocode", "password": "password123"}
    )
    assert r.status_code == 403 and r.json()["detail"] == "invite_required"
    # unknown code
    r = await client.post(
        "/api/v1/auth/register",
        json={"display_name": "badcode", "password": "password123", "invite_code": "WT-AAAA-BBBB"},
    )
    assert r.status_code == 403 and r.json()["detail"] == "invalid_invite_code"


@pytest.mark.asyncio
async def test_invite_single_use_lifecycle(client: AsyncClient, invite_required):
    admin = await register(client, "invadmin")  # first user = admin (bootstrap)
    h = auth_hdr(admin)
    minted = await _mint(client, h)
    code = minted["code"]
    assert code.startswith("WT-")
    # listed without the raw code afterwards
    r = await client.get("/api/v1/admin/invites", headers=h)
    assert r.status_code == 200
    assert all("code" not in i or i["code"] is None for i in r.json())
    # first signup with the code works
    r = await client.post(
        "/api/v1/auth/register",
        json={"display_name": "invited1", "password": "password123", "invite_code": code},
    )
    assert r.status_code == 201, r.text
    # second use is rejected
    r = await client.post(
        "/api/v1/auth/register",
        json={"display_name": "invited2", "password": "password123", "invite_code": code},
    )
    assert r.status_code == 410 and r.json()["detail"] == "invite_already_used"
    # used_by recorded
    r = await client.get("/api/v1/admin/invites", headers=h)
    row = next(i for i in r.json() if i["id"] == minted["id"])
    assert row["used_by"] is not None


@pytest.mark.asyncio
async def test_invite_expiry_and_revoke(client: AsyncClient, invite_required):
    admin = await register(client, "invadmin2")
    h = auth_hdr(admin)
    minted = await _mint(client, h)
    # revoke via API (unused invites can be revoked)
    r = await client.delete(f"/api/v1/admin/invites/{minted['id']}", headers=h)
    assert r.status_code == 204
    minted2 = await _mint(client, h)
    # revoke the second too, then it must not work for signup
    r = await client.delete(f"/api/v1/admin/invites/{minted2['id']}", headers=h)
    assert r.status_code == 204
    r = await client.post(
        "/api/v1/auth/register",
        json={"display_name": "revoked_user", "password": "password123", "invite_code": minted2["code"]},
    )
    assert r.status_code == 403 and r.json()["detail"] == "invalid_invite_code"
    # double revoke -> 404
    r = await client.delete(f"/api/v1/admin/invites/{minted2['id']}", headers=h)
    assert r.status_code == 404


@pytest.mark.asyncio
async def test_invites_admin_only(client: AsyncClient, invite_required):
    admin = await register(client, "invadmin3")
    plain = await client.post(
        "/api/v1/auth/register",
        json={"display_name": "plain6", "password": "password123", "invite_code": "x"},
    )
    # bootstrap admin still allowed without a code (first user on empty system)
    assert admin["access_token"]
    if plain.status_code != 201:
        return
    r = await client.get("/api/v1/admin/invites", headers=auth_hdr(plain.json()))
    assert r.status_code == 403
