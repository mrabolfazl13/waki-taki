"""Devices, key discovery, channels, 20-cap, invites."""
from __future__ import annotations

import base64

import pytest

from tests.conftest import auth_hdr, make_device, register, setup_channel

pytestmark = pytest.mark.asyncio


async def test_device_register_list_delete(client):
    t = await register(client, "devuser")
    h = auth_hdr(t)
    device_id = await make_device(client, h)
    r = await client.get("/api/v1/devices", headers=h)
    assert r.status_code == 200 and len(r.json()) == 1
    r = await client.delete(f"/api/v1/devices/{device_id}", headers=h)
    assert r.status_code == 204
    r = await client.get("/api/v1/devices", headers=h)
    assert r.json() == []


async def test_device_requires_auth(client):
    r = await client.post("/api/v1/devices", json={"identity_pubkey": "x" * 20, "signing_pubkey": "x" * 20, "signed_prekey": "x" * 20})
    assert r.status_code == 401


async def test_key_discovery(client):
    admin = await register(client, "keyadmin")  # first -> admin
    t = await register(client, "keytarget")
    h = auth_hdr(t)
    await make_device(client, h)
    t2 = await register(client, "keylooker")
    r = await client.get("/api/v1/admin/users", headers=auth_hdr(admin))
    assert r.status_code == 200
    users = {u["display_name"]: u["id"] for u in r.json()}
    r = await client.get(f"/api/v1/users/{users['keytarget']}/keys", headers=auth_hdr(admin))
    assert r.status_code == 200
    devices = r.json()["devices"]
    assert len(devices) == 1
    assert devices[0]["identity_pubkey"]  # public material present
    assert "private" not in str(devices[0]).lower()


async def test_channel_create_list_delete(client):
    t = await register(client, "owner")
    h = auth_hdr(t)
    r = await client.post("/api/v1/channels", headers=h, json={"name": "ops"})
    assert r.status_code == 201
    assert r.json()["is_owner"] and r.json()["epoch"] == 1
    cid = r.json()["id"]
    r = await client.get("/api/v1/channels", headers=h)
    assert len(r.json()) == 1
    r = await client.delete(f"/api/v1/channels/{cid}", headers=h)
    assert r.status_code == 204
    r = await client.get("/api/v1/channels", headers=h)
    assert r.json() == []


async def test_invite_flow_and_validation(client):
    ctx = await setup_channel(client, "invown", ["invmember"])
    member = ctx["invmember"]
    r = await client.post(
        "/api/v1/invites/validate", headers=auth_hdr(member), json={"invite_code": ctx["invite"]}
    )
    assert r.status_code == 200
    body = r.json()
    assert body["valid"] and body["channel_id"] == ctx["channel_id"]
    r = await client.post(
        "/api/v1/invites/validate", headers=auth_hdr(member), json={"invite_code": "not-a-real-code!!"}
    )
    assert r.status_code == 200 and r.json()["valid"] is False


async def test_join_with_bad_invite_rejected(client):
    owner = await register(client, "badinvown")
    h = auth_hdr(owner)
    r = await client.post("/api/v1/channels", headers=h, json={"name": "sec"})
    cid = r.json()["id"]
    outsider = await register(client, "badinvout")
    r = await client.post(
        f"/api/v1/channels/{cid}/join", headers=auth_hdr(outsider), json={"invite_code": "wrong-code-123"}
    )
    assert r.status_code == 403


async def test_unauthorized_channel_access(client):
    ctx = await setup_channel(client, "unaown")
    outsider = await register(client, "unaout")
    cid = ctx["channel_id"]
    # non-member cannot list invites / join-info; channel delete by non-owner
    r = await client.delete(f"/api/v1/channels/{cid}", headers=auth_hdr(outsider))
    assert r.status_code in (403, 404)
    # non-member cannot distribute keys
    r = await client.post(
        f"/api/v1/channels/{cid}/keys/distribute",
        headers=auth_hdr(outsider),
        json={"sender_device_id": "a" * 32, "epoch": 1, "envelopes": [{"recipient_device_id": "b" * 32, "envelope_ciphertext": "AAAA"}]},
    )
    assert r.status_code == 403


async def test_twenty_member_cap_enforced(client):
    ctx = await setup_channel(client, "capown")
    cid, invite = ctx["channel_id"], ctx["invite"]
    # owner + 19 members = 20 OK
    for i in range(19):
        t = await register(client, f"cap{i}")
        r = await client.post(
            f"/api/v1/channels/{cid}/join", headers=auth_hdr(t), json={"invite_code": invite}
        )
        assert r.status_code == 200, (i, r.text)
    # fresh invite (old one exhausted its uses)
    r = await client.post(f"/api/v1/channels/{cid}/invites", headers=auth_hdr(ctx["owner"]))
    assert r.status_code == 200, r.text
    invite2 = r.json()["invite_code"]
    # 21st member must be rejected
    t21 = await register(client, "cap21")
    r = await client.post(
        f"/api/v1/channels/{cid}/join", headers=auth_hdr(t21), json={"invite_code": invite2}
    )
    assert r.status_code == 409
    assert r.json()["detail"] == "channel_full"
