"""Regression tests for review fixes: enforced flags, member roster, enable, invite HMAC."""
from __future__ import annotations

import importlib

import pytest

from app.api import channels as channels_module
from tests.conftest import auth_hdr, make_device, register, setup_channel

pytestmark = pytest.mark.asyncio


async def test_invite_hash_is_shared_not_process_local():
    """A second API process must hash invite codes identically."""
    first = channels_module._hash_code("same-invite-code")
    importlib.reload(channels_module)
    assert channels_module._hash_code("same-invite-code") == first


async def test_admin_flags_are_enforced(client):
    admin = await register(client, "flagadmin")
    h = auth_hdr(admin)
    ch = await setup_channel(client, owner_name="flagowner")

    r = await client.put("/api/v1/admin/settings", headers=h, json={"maintenance_mode": True})
    assert r.status_code == 200
    r = await client.post(
        "/api/v1/auth/register",
        json={"display_name": "blocked_during_maintenance", "password": "password123"},
    )
    assert r.status_code == 503
    r = await client.post(f"/api/v1/channels/{ch['channel_id']}/invites", headers=auth_hdr(ch["owner"]))
    assert r.status_code == 503

    await client.put(
        "/api/v1/admin/settings",
        headers=h,
        json={"maintenance_mode": False, "invite_creation_enabled": False},
    )
    r = await client.post(f"/api/v1/channels/{ch['channel_id']}/invites", headers=auth_hdr(ch["owner"]))
    assert r.status_code == 503

    await client.put("/api/v1/admin/settings", headers=h, json={"invite_creation_enabled": True})
    r = await client.post(f"/api/v1/channels/{ch['channel_id']}/invites", headers=auth_hdr(ch["owner"]))
    assert r.status_code == 200


async def test_member_roster_and_enable_user(client):
    admin = await register(client, "rosteradmin")
    h = auth_hdr(admin)
    ch = await setup_channel(client, owner_name="rosterowner", member_names=("rosterjoiner",))
    member = ch["rosterjoiner"]
    mh = auth_hdr(member)
    users = (await client.get("/api/v1/admin/users", headers=h)).json()
    member_uid = next(u["id"] for u in users if u["display_name"] == "rosterjoiner")

    did = await make_device(client, mh, "b" * 32)
    r = await client.get(f"/api/v1/channels/{ch['channel_id']}/members", headers=auth_hdr(ch["owner"]))
    assert r.status_code == 200
    row = next(m for m in r.json() if m["user_id"] == member_uid)
    assert row["device_id"] == did
    assert "pubkey" not in r.text and "prekey" not in r.text

    # Anonymous / non-member cannot read the roster.
    assert (await client.get(f"/api/v1/channels/{ch['channel_id']}/members")).status_code == 401

    uid = member_uid
    r = await client.post(f"/api/v1/admin/users/{uid}/disable", headers=h)
    assert r.status_code == 200 and r.json()["disabled"] is True
    r = await client.post(f"/api/v1/admin/users/{uid}/enable", headers=h)
    assert r.status_code == 200 and r.json()["disabled"] is False


async def test_envelope_size_and_fields_are_validated(client):
    import base64

    ch = await setup_channel(client, owner_name="envowner", member_names=("envmember",))
    oh = auth_hdr(ch["owner"])
    owner_dev = await make_device(client, oh, "c" * 32)
    member_dev = await make_device(client, auth_hdr(ch["envmember"]), "d" * 32)
    url = f"/api/v1/channels/{ch['channel_id']}/keys/distribute"

    # Missing / mistyped fields -> 422 (never a 500).
    r = await client.post(url, headers=oh, json={
        "sender_device_id": owner_dev, "epoch": 1, "envelopes": [{"envelope_ciphertext": "AAAA"}],
    })
    assert r.status_code == 422
    r = await client.post(url, headers=oh, json={
        "sender_device_id": owner_dev, "epoch": 1,
        "envelopes": [{"recipient_device_id": member_dev, "envelope_ciphertext": 123}],
    })
    assert r.status_code == 422

    # Oversized ciphertext is rejected before it reaches the database.
    big = base64.b64encode(b"x" * 9000).decode()
    r = await client.post(url, headers=oh, json={
        "sender_device_id": owner_dev, "epoch": 1,
        "envelopes": [{"recipient_device_id": member_dev, "envelope_ciphertext": big}],
    })
    assert r.status_code == 413

    # A normal envelope still works.
    ok = base64.b64encode(b"y" * 64).decode()
    r = await client.post(url, headers=oh, json={
        "sender_device_id": owner_dev, "epoch": 1,
        "envelopes": [{"recipient_device_id": member_dev, "envelope_ciphertext": ok}],
    })
    assert r.status_code == 201 and r.json()["stored"] == 1


async def test_device_limit_and_prekey_validation(client):
    import base64

    from app.core.config import get_settings

    tokens = await register(client, "devlimit")
    h = auth_hdr(tokens)
    base = {
        "identity_pubkey": base64.b64encode(b"i" * 32).decode(),
        "signing_pubkey": base64.b64encode(b"s" * 32).decode(),
        "signed_prekey": base64.b64encode(b"p" * 32).decode(),
        "name": "dev",
    }
    # Malformed / out-of-range one-time prekeys are rejected.
    bad = dict(base, one_time_prekeys=["not base64 !!"])
    assert (await client.post("/api/v1/devices", headers=h, json=bad)).status_code == 422
    too_short = dict(base, one_time_prekeys=[base64.b64encode(b"x" * 4).decode()])
    assert (await client.post("/api/v1/devices", headers=h, json=too_short)).status_code == 422

    limit = get_settings().max_devices_per_user
    for _ in range(limit):
        r = await client.post("/api/v1/devices", headers=h, json=base)
        assert r.status_code == 201
    r = await client.post("/api/v1/devices", headers=h, json=base)
    assert r.status_code == 409 and r.json()["detail"] == "device_limit_reached"

    # Revoking one frees a slot.
    devices = (await client.get("/api/v1/devices", headers=h)).json()
    assert (await client.delete(f"/api/v1/devices/{devices[0]['id']}", headers=h)).status_code == 204
    assert (await client.post("/api/v1/devices", headers=h, json=base)).status_code == 201


async def test_orm_enums_match_migrated_enum_values():
    """Regression: the Alembic migration creates enums with lowercase values.

    SQLAlchemy's Enum() stores Python enum *names* by default ("USER"), which a
    migrated PostgreSQL rejects with `invalid input value for enum user_role`.
    """
    from app.models import MemberRole, Membership, Role, User

    assert [m.value for m in Role] == ["user", "admin"]
    assert User.__table__.c.role.type.enums == ["user", "admin"]
    assert Membership.__table__.c.role.type.enums == ["member", "admin"]
    assert [m.value for m in MemberRole] == ["member", "admin"]
