"""Admin gating, audit log, security events, health."""
from __future__ import annotations

import pytest

from tests.conftest import auth_hdr, register

pytestmark = pytest.mark.asyncio


async def test_health_no_auth(client):
    r = await client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body == {"status": "ok"}


async def test_admin_endpoints_gated(client):
    await register(client, "gateseedadmin")  # consumes bootstrap admin slot
    t = await register(client, "plainuser")  # not admin (not first)
    for path in (
        "/api/v1/admin/users", "/api/v1/admin/channels", "/api/v1/admin/health",
        "/api/v1/admin/audit", "/api/v1/admin/security-events",
    ):
        r = await client.get(path, headers=auth_hdr(t))
        assert r.status_code == 403, path
    r = await client.post("/api/v1/health/details")  # noqa
    # health/details requires admin
    r = await client.get("/health/details")
    assert r.status_code in (401, 403)


async def test_admin_flows(client):
    admin = await register(client, "adminmain")  # first in this test = admin
    h = auth_hdr(admin)
    t2 = await register(client, "victim")
    r = await client.get("/api/v1/admin/users", headers=h)
    assert r.status_code == 200
    users = {u["display_name"]: u["id"] for u in r.json()}
    # disable
    r = await client.post(f"/api/v1/admin/users/{users['victim']}/disable", headers=h)
    assert r.status_code == 200 and r.json()["disabled"] is True
    # disabled user cannot authenticate via token decode path
    r = await client.get("/api/v1/channels", headers=auth_hdr(t2))
    assert r.status_code == 401
    # roles
    r = await client.post("/api/v1/admin/roles", headers=h, json={"user_id": users["victim"], "role": "admin"})
    assert r.status_code == 200
    # audit recorded
    r = await client.get("/api/v1/admin/audit", headers=h)
    assert r.status_code == 200
    actions = [a["action"] for a in r.json()]
    assert "user.disable" in actions and "user.role" in actions
    # security events
    r = await client.get("/api/v1/admin/security-events", headers=h)
    assert r.status_code == 200
    types = [e["type"] for e in r.json()]
    assert any(t.startswith(("account.", "auth.")) for t in types)
