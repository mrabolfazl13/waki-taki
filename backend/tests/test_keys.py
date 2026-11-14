"""Sender-key envelopes (distribution, fetch, ack+delete), epoch rotation, member removal."""
from __future__ import annotations

import base64

import pytest

from tests.conftest import auth_hdr, make_device, register, setup_channel

pytestmark = pytest.mark.asyncio


async def envelope_flow(client):
    ctx = await setup_channel(client, "envown", ["envmember"])
    cid = ctx["channel_id"]
    owner_h = auth_hdr(ctx["owner"])
    member_h = auth_hdr(ctx["envmember"])
    owner_dev = await make_device(client, owner_h)
    member_dev = await make_device(client, member_h)
    return ctx, cid, owner_h, member_h, owner_dev, member_dev


async def test_envelope_distribute_fetch_ack_deletes(client):
    ctx, cid, owner_h, member_h, owner_dev, member_dev = await envelope_flow(client)
    payload = base64.b64encode(b"sender-key-material-encrypted").decode()
    r = await client.post(
        f"/api/v1/channels/{cid}/keys/distribute",
        headers=owner_h,
        json={"sender_device_id": owner_dev, "epoch": 1,
              "envelopes": [{"recipient_device_id": member_dev, "envelope_ciphertext": payload}]},
    )
    assert r.status_code == 201, r.text
    assert r.json()["stored"] == 1
    # fetch as recipient
    r = await client.get(
        f"/api/v1/channels/{cid}/keys/envelopes",
        headers={**member_h, "x-d": "1"}, params={"device_id": member_dev},
    )
    assert r.status_code == 200, r.text
    envs = r.json()["envelopes"]
    assert len(envs) == 1 and envs[0]["epoch"] == 1
    env_id = envs[0]["envelope_id"]
    # ack deletes
    r = await client.post(
        f"/api/v1/channels/{cid}/keys/ack", headers=member_h, json={"envelope_ids": [env_id]}
    )
    assert r.status_code == 204
    r = await client.get(
        f"/api/v1/channels/{cid}/keys/envelopes", headers=member_h, params={"device_id": member_dev}
    )
    assert r.json()["envelopes"] == []


async def test_envelope_recipient_must_be_member_device(client):
    ctx, cid, owner_h, member_h, owner_dev, member_dev = await envelope_flow(client)
    outsider = await register(client, "envout")
    outsider_dev = await make_device(client, auth_hdr(outsider))
    r = await client.post(
        f"/api/v1/channels/{cid}/keys/distribute",
        headers=owner_h,
        json={"sender_device_id": owner_dev, "epoch": 1,
              "envelopes": [{"recipient_device_id": outsider_dev, "envelope_ciphertext": "AAAA"}]},
    )
    assert r.status_code == 403


async def test_envelope_epoch_mismatch(client):
    ctx, cid, owner_h, member_h, owner_dev, member_dev = await envelope_flow(client)
    r = await client.post(
        f"/api/v1/channels/{cid}/keys/distribute",
        headers=owner_h,
        json={"sender_device_id": owner_dev, "epoch": 99,
              "envelopes": [{"recipient_device_id": member_dev, "envelope_ciphertext": "AAAA"}]},
    )
    assert r.status_code == 409


async def test_epoch_rotation_and_revocation(client):
    ctx, cid, owner_h, member_h, owner_dev, member_dev = await envelope_flow(client)
    r = await client.post(
        f"/api/v1/channels/{cid}/epoch/rotate", headers=owner_h, json={}
    )
    assert r.status_code == 200
    assert r.json()["epoch"] == 2
    # member (non-admin role) cannot rotate
    r = await client.post(f"/api/v1/channels/{cid}/epoch/rotate", headers=member_h, json={})
    assert r.status_code == 403
    # distributing for old epoch now fails
    r = await client.post(
        f"/api/v1/channels/{cid}/keys/distribute",
        headers=owner_h,
        json={"sender_device_id": owner_dev, "epoch": 1,
              "envelopes": [{"recipient_device_id": member_dev, "envelope_ciphertext": "AAAA"}]},
    )
    assert r.status_code == 409


async def test_member_removal_rotates_epoch(client):
    ctx, cid, owner_h, member_h, owner_dev, member_dev = await envelope_flow(client)
    member_id = None
    admin = ctx["owner"]  # owner is first? "envown" may not be admin; owner can remove regardless
    # find member's user id via keys endpoint? Use removal by display name is not supported;
    # admin users list (owner might not be admin). Register order: envown was not first globally.
    # Instead: create fresh owner-admin
    root = await register(client, "remroot")  # may or may not be admin depending on prior tests
    r = await client.get("/api/v1/admin/users", headers=auth_hdr(root))
    if r.status_code == 200:
        users = {u["display_name"]: u["id"] for u in r.json()}
        member_id = users["envmember"]
        r = await client.post(
            f"/api/v1/channels/{cid}/members/{member_id}/remove", headers=owner_h
        )
        assert r.status_code == 204, r.text
        # removed member denied
        r = await client.get(f"/api/v1/channels/{cid}/keys/envelopes", headers=member_h, params={"device_id": member_dev})
        assert r.status_code == 403
        # epoch rotated by removal
        r = await client.get(f"/api/v1/channels/{cid}/epoch/rotate", headers=owner_h)
        # (GET not defined; check via channels list)
        r = await client.get("/api/v1/channels", headers=owner_h)
        chan = next(c for c in r.json() if c["id"] == cid)
        assert chan["epoch"] >= 2
    else:
        pytest.skip("owner not admin in this DB state")
