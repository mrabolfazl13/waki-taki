"""Admin dashboard endpoints: memberships, devices, config, settings."""
from __future__ import annotations

import pytest

from tests.conftest import auth_hdr, make_device, register, setup_channel

pytestmark = pytest.mark.asyncio

PATHS = (
    "/api/v1/admin/memberships",
    "/api/v1/admin/devices",
    "/api/v1/admin/config",
    "/api/v1/admin/settings",
)


async def test_dashboard_endpoints_gated(client):
    await register(client, "dashseedadmin")  # bootstrap admin
    user = await register(client, "plainmember")
    for path in PATHS:
        r = await client.get(path, headers=auth_hdr(user))
        assert r.status_code == 403, path
    r = await client.put("/api/v1/admin/settings", headers=auth_hdr(user), json={"maintenance_mode": True})
    assert r.status_code == 403
    # anonymous too
    r = await client.get("/api/v1/admin/config")
    assert r.status_code == 401


async def test_admin_memberships_and_devices(client):
    admin = await register(client, "dashadmin")  # admin
    h = auth_hdr(admin)
    # device for admin + channel with a member
    await make_device(client, h)
    ch = await setup_channel(client, owner_name="mowner", member_names=("mjoiner",))
    r = await client.get("/api/v1/admin/memberships", headers=h)
    assert r.status_code == 200
    rows = r.json()
    assert isinstance(rows, list) and rows
    row = next(m for m in rows if m["user_display_name"] == "mjoiner")
    assert row["channel_name"] == "chan-mowner"
    assert row["role"] == "member"
    assert row["joined_at"]
    # summary fields present, but no key material anywhere
    assert set(row) == {"channel_id", "channel_name", "user_id", "user_display_name", "role", "joined_at"}
    joined_user_ids = {m["user_id"] for m in rows}
    assert all(m["user_display_name"] for m in rows if m["user_id"] in joined_user_ids)

    r = await client.get("/api/v1/admin/devices", headers=h)
    assert r.status_code == 200
    devices = r.json()
    assert devices
    d = devices[0]
    assert set(d) == {"id", "user_id", "name", "registered_at", "revoked_at"}
    assert d["revoked_at"] is None
    blob = r.text.lower()
    assert "pubkey" not in blob and "prekey" not in blob  # never key material
    # pagination params accepted
    r = await client.get("/api/v1/admin/devices", headers=h, params={"limit": 1, "offset": 0})
    assert r.status_code == 200 and len(r.json()) == 1
    r = await client.get("/api/v1/admin/devices", headers=h, params={"limit": 1000})
    assert r.status_code == 422


async def test_admin_config_no_secrets(client):
    admin = await register(client, "cfgadmin")
    h = auth_hdr(admin)
    r = await client.get("/api/v1/admin/config", headers=h)
    assert r.status_code == 200
    cfg = r.json()
    assert cfg["max_channel_members"] == 20
    assert cfg["floor_lease_seconds"] == 60
    assert cfg["access_token_minutes"] == 15
    assert cfg["refresh_token_days"] == 30
    text = r.text.lower()
    assert "secret" not in text and "password" not in text and "database_url" not in text


async def test_admin_settings_flow(client):
    admin = await register(client, "setadmin")
    h = auth_hdr(admin)
    # defaults
    r = await client.get("/api/v1/admin/settings", headers=h)
    assert r.status_code == 200
    assert r.json() == {
        "registration_enabled": True,
        "maintenance_mode": False,
        "invite_creation_enabled": True,
    }
    # valid update
    r = await client.put("/api/v1/admin/settings", headers=h, json={"maintenance_mode": True})
    assert r.status_code == 200
    assert r.json()["maintenance_mode"] is True
    assert r.json()["registration_enabled"] is True  # untouched default preserved
    # persisted
    r = await client.get("/api/v1/admin/settings", headers=h)
    assert r.json()["maintenance_mode"] is True
    # audit entry written
    r = await client.get("/api/v1/admin/audit", headers=h)
    assert any(a["action"] == "settings.update" for a in r.json())
    # validation: unknown key
    r = await client.put("/api/v1/admin/settings", headers=h, json={"bogus_key": True})
    assert r.status_code == 422
    # validation: wrong type
    r = await client.put("/api/v1/admin/settings", headers=h, json={"maintenance_mode": "yes"})
    assert r.status_code == 422
    # validation: empty body
    r = await client.put("/api/v1/admin/settings", headers=h, json={})
    assert r.status_code == 422
