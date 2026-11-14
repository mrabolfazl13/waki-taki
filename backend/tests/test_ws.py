"""WebSocket hub: floor control, presence, signaling relay, binary media relay, replay rejection."""
from __future__ import annotations

import asyncio
import base64
import os
import struct

import fakeredis.aioredis
import pytest
from starlette.testclient import TestClient


def _register(tc, name, password="password123"):
    r = tc.post("/api/v1/auth/register", json={"display_name": name, "password": password})
    assert r.status_code == 201, r.text
    return r.json()


def _make_device(tc, headers):
    r = tc.post(
        "/api/v1/devices",
        headers=headers,
        json={
            "identity_pubkey": base64.b64encode(b"" * 32).decode(),
            "signing_pubkey": base64.b64encode(b"" * 32).decode(),
            "signed_prekey": base64.b64encode(b"" * 32).decode(),
            "name": "test-device",
        },
    )
    assert r.status_code == 201, r.text
    return r.json()["id"]

MEDIA_HEADER = struct.Struct(">I16sI")


@pytest.fixture
def ws():
    if os.path.exists("./test_ws.db"):
        os.remove("./test_ws.db")
    os.environ["ST_DATABASE_URL"] = "sqlite+aiosqlite:///./test_ws.db"

    from app.core.config import get_settings
    from app.db import Base, make_engine, make_sessionmaker
    from app.deps import init_state
    from app.main import create_app

    settings = get_settings()
    engine = make_engine(settings.database_url)
    session_factory = make_sessionmaker(engine)
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    init_state(engine, session_factory, redis)
    app = create_app()

    async def _tables():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_tables())
    with TestClient(app) as tc:
        yield tc
    asyncio.run(engine.dispose())
    if os.path.exists("./test_ws.db"):
        os.remove("./test_ws.db")


def _join_channel_nonadmin(tc: TestClient):
    """Channel created by a NON-admin user (3rd account)."""
    _register(tc, "wsown")
    owner = _register(tc, "nawn")
    member = _register(tc, "nmem")
    h = {"Authorization": f"Bearer {owner['access_token']}"}
    r = tc.post("/api/v1/channels", headers=h, json={"name": "nchan"})
    cid = r.json()["id"]
    r = tc.post(f"/api/v1/channels/{cid}/invites", headers=h)
    invite = r.json()["invite_code"]
    r = tc.post(
        f"/api/v1/channels/{cid}/join",
        headers={"Authorization": f"Bearer {member['access_token']}"},
        json={"invite_code": invite},
    )
    assert r.status_code == 200
    owner_dev = _make_device(tc, h)
    member_dev = _make_device(tc, {"Authorization": f"Bearer {member['access_token']}"})
    return {"owner": owner, "member": member, "cid": cid, "owner_dev": owner_dev, "member_dev": member_dev}


def _join_channel(tc: TestClient):
    owner = _register(tc, "wsown")
    member = _register(tc, "wsmember")
    h = {"Authorization": f"Bearer {owner['access_token']}"}
    r = tc.post("/api/v1/channels", headers=h, json={"name": "wschan"})
    cid = r.json()["id"]
    r = tc.post(f"/api/v1/channels/{cid}/invites", headers=h)
    invite = r.json()["invite_code"]
    r = tc.post(
        f"/api/v1/channels/{cid}/join",
        headers={"Authorization": f"Bearer {member['access_token']}"},
        json={"invite_code": invite},
    )
    assert r.status_code == 200
    owner_dev = _make_device(tc, h)
    member_dev = _make_device(tc, {"Authorization": f"Bearer {member['access_token']}"})
    return {"owner": owner, "member": member, "cid": cid, "owner_dev": owner_dev, "member_dev": member_dev}


def _recv_until(w, want_type, limit=10):
    """Receive messages until one of want_type arrives; return it."""
    for _ in range(limit):
        m = w.receive_json()
        if m.get("type") in want_type:
            return m
    raise AssertionError(f"never received {want_type}")


def _join_two(ws_conn_owner, ws_conn_member, ctx):
    cid, odev, mdev = ctx["cid"], ctx["owner_dev"], ctx["member_dev"]
    ws_conn_owner.send_json({"type": "join", "data": {"channel_id": cid, "device_id": odev}})
    ws_conn_member.send_json({"type": "join", "data": {"channel_id": cid, "device_id": mdev}})
    j1 = ws_conn_owner.receive_json()
    j2 = ws_conn_member.receive_json()
    assert j1["type"] == "joined" and j2["type"] == "joined"


class TestFloorControl:
    def test_single_speaker_and_denial(self, ws):
        ctx = _join_channel(ws)
        cid, odev, mdev = ctx["cid"], ctx["owner_dev"], ctx["member_dev"]
        with ws.websocket_connect(f"/ws?token={ctx['owner']['access_token']}") as w1, \
             ws.websocket_connect(f"/ws?token={ctx['member']['access_token']}") as w2:
            _join_two(w1, w2, ctx)
            w1.send_json({"type": "floor.request", "data": {"channel_id": cid}})
            m = _recv_until(w1, {"floor.granted", "floor.denied"})
            assert m["type"] == "floor.granted"
            w2.send_json({"type": "floor.request", "data": {"channel_id": cid}})
            m = _recv_until(w2, {"floor.granted", "floor.denied"})
            assert m["type"] == "floor.denied" and m["data"]["reason"] == "busy"
            w1.send_json({"type": "floor.release", "data": {"channel_id": cid}})
            m = _recv_until(w2, {"floor.released"})
            assert m["data"]["previous_holder"] == odev
            w2.send_json({"type": "floor.request", "data": {"channel_id": cid}})
            m = _recv_until(w2, {"floor.granted", "floor.denied"})
            assert m["type"] == "floor.granted"

    def test_admin_override_denied_for_non_admin(self, ws):
        ctx = _join_channel_nonadmin(ws)
        cid = ctx["cid"]
        with ws.websocket_connect(f"/ws?token={ctx['owner']['access_token']}") as w1, \
             ws.websocket_connect(f"/ws?token={ctx['member']['access_token']}") as w2:
            _join_two(w1, w2, ctx)
            w2.send_json({"type": "floor.request", "data": {"channel_id": cid}})
            _recv_until(w2, {"floor.granted", "floor.denied"})
            w1.send_json({"type": "floor.override", "data": {"channel_id": cid}})
            m = _recv_until(w1, {"error"})
            assert m["data"]["code"] == "override_not_allowed"

    def test_admin_override_granted_for_admin(self, ws):
        # 'wsown' is the first account in this DB -> bootstrap admin
        ctx = _join_channel(ws)
        cid, odev = ctx["cid"], ctx["owner_dev"]
        with ws.websocket_connect(f"/ws?token={ctx['owner']['access_token']}") as w1, \
             ws.websocket_connect(f"/ws?token={ctx['member']['access_token']}") as w2:
            _join_two(w1, w2, ctx)
            w2.send_json({"type": "floor.request", "data": {"channel_id": cid}})
            _recv_until(w2, {"floor.granted", "floor.denied"})
            w1.send_json({"type": "floor.override", "data": {"channel_id": cid}})
            m = _recv_until(w1, {"floor.granted"})
            assert m["data"]["holder_device_id"] == odev

    def test_unauthorized_join_rejected(self, ws):
        ctx = _join_channel(ws)
        outsider = _register(ws, "wsout")
        with ws.websocket_connect(f"/ws?token={outsider['access_token']}") as w:
            w.send_json({"type": "join", "data": {"channel_id": ctx["cid"], "device_id": ctx["owner_dev"]}})
            m = w.receive_json()
            assert m["type"] == "error" and m["data"]["code"] == "device_not_owned"

    def test_floor_rate_limit(self, ws):
        ctx = _join_channel(ws)
        cid = ctx["cid"]
        with ws.websocket_connect(f"/ws?token={ctx['owner']['access_token']}") as w1, \
             ws.websocket_connect(f"/ws?token={ctx['member']['access_token']}") as w2:
            _join_two(w1, w2, ctx)
            reasons = []
            for i in range(12):
                w2.send_json({"type": "floor.request", "data": {"channel_id": cid}})
                m = _recv_until(w2, {"floor.granted", "floor.denied"})
                if m["type"] == "floor.denied":
                    reasons.append(m["data"]["reason"])
                else:
                    # release so subsequent requests are not just "busy"
                    w2.send_json({"type": "floor.release", "data": {"channel_id": cid}})
                    _recv_until(w2, {"floor.released"})
            assert "rate_limited" in reasons


class TestMediaRelay:
    def test_relay_fanout_and_replay_rejection(self, ws):
        ctx = _join_channel(ws)
        cid, odev, mdev = ctx["cid"], ctx["owner_dev"], ctx["member_dev"]
        with ws.websocket_connect(f"/ws?token={ctx['owner']['access_token']}") as w1, \
             ws.websocket_connect(f"/ws?token={ctx['member']['access_token']}") as w2:
            _join_two(w1, w2, ctx)
            w1.send_json({"type": "floor.request", "data": {"channel_id": cid}})
            m = _recv_until(w1, {"floor.granted", "floor.denied"})
            assert m["type"] == "floor.granted"
            while True:  # drain presence/floor broadcasts until floor shows our speaker
                m = w2.receive_json()
                if m["type"] == "floor.event" and m["data"]["state"] == "speaking":
                    break
            w1.send_bytes(MEDIA_HEADER.pack(1, bytes.fromhex(odev), 1) + b"frame1")
            frame = w2.receive_bytes()
            epoch, sender, seq = MEDIA_HEADER.unpack_from(frame, 0)
            assert epoch == 1 and sender == bytes.fromhex(odev) and seq == 1
            assert frame.endswith(b"frame1")
            w1.send_bytes(MEDIA_HEADER.pack(1, bytes.fromhex(odev), 1) + b"frame1")
            m = w1.receive_json()
            assert m["type"] == "error" and m["data"]["code"] == "replay_detected"
            w2.send_bytes(MEDIA_HEADER.pack(1, bytes.fromhex(mdev), 1) + b"sneaky")
            m = w2.receive_json()
            assert m["type"] == "error" and m["data"]["code"] == "floor_not_held"

    def test_signaling_relay(self, ws):
        ctx = _join_channel(ws)
        cid, odev, mdev = ctx["cid"], ctx["owner_dev"], ctx["member_dev"]
        with ws.websocket_connect(f"/ws?token={ctx['owner']['access_token']}") as w1, \
             ws.websocket_connect(f"/ws?token={ctx['member']['access_token']}") as w2:
            _join_two(w1, w2, ctx)
            w1.send_json({"type": "signaling.offer", "data": {"target_device_id": mdev, "sdp": "v=0..."}})
            m = _recv_until(w2, {"signaling.offer"})
            assert m["type"] == "signaling.offer"
            assert m["data"]["from_device_id"] == odev and m["data"]["sdp"] == "v=0..."

    def test_invalid_token_rejected(self, ws):
        with ws.websocket_connect("/ws?token=garbage") as w:
            m = w.receive_json()
            assert m["type"] == "error" and m["data"]["code"] == "invalid_token"
