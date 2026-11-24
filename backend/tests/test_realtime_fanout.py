"""Cross-instance realtime fan-out over Redis pub/sub.

Covers the multi-instance media path that used to be "CI only": two Hub
instances share one (fake) Redis, and a frame published by one must be
delivered by the other, while the publishing instance must ignore its own
echo.  This is the base64-text wire format introduced to fix KNOWN_ISSUES #1.
"""
from __future__ import annotations

import asyncio
import base64
import struct
import uuid

import fakeredis.aioredis
import pytest

from app.realtime import hub as hub_module
from app.realtime.hub import MEDIA_HEADER, Hub

pytestmark = pytest.mark.asyncio

CID = "channel-1"
DEV = "a" * 32


class FakeWS:
    def __init__(self) -> None:
        self.texts: list[str] = []
        self.frames: list[bytes] = []

    async def send_text(self, payload: str) -> None:
        self.texts.append(payload)

    async def send_bytes(self, payload: bytes) -> None:
        self.frames.append(payload)


def _wire(origin: str, frame: bytes) -> str:
    return base64.b64encode(origin.encode() + b"\x00" + frame).decode("ascii")


async def _wait_for(predicate, timeout: float = 3.0) -> bool:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if predicate():
            return True
        await asyncio.sleep(0.02)
    return predicate()


async def test_remote_media_is_fanned_out_to_second_instance():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    hub_a = Hub(redis, session_factory=None)
    hub_b = Hub(redis, session_factory=None)
    conn_a = FakeWS()
    conn_b = FakeWS()
    hub_a.conns["dev-a"] = hub_module.Conn(ws=conn_a, user_id="u1", role="user", device_id="dev-a", channel_id=CID)
    hub_b.conns["dev-b"] = hub_module.Conn(ws=conn_b, user_id="u2", role="user", device_id="dev-b", channel_id=CID)
    await hub_a.start()
    await hub_b.start()
    await asyncio.sleep(0.3)  # let both listeners psubscribe
    try:
        frame = MEDIA_HEADER.pack(1, bytes.fromhex(DEV), 7) + b"opus-frame"
        # Instance A publishes as if it were instance B.
        await redis.publish(f"media:{CID}", _wire("other-instance", frame))
        assert await _wait_for(lambda: conn_a.frames and conn_b.frames)
        assert conn_a.frames == [frame]
        assert conn_b.frames == [frame]
    finally:
        await hub_a.stop()
        await hub_b.stop()
        await redis.aclose()


async def test_instance_ignores_its_own_published_frame():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    hub = Hub(redis, session_factory=None)
    conn = FakeWS()
    hub.conns["dev-a"] = hub_module.Conn(ws=conn, user_id="u1", role="user", device_id="dev-a", channel_id=CID)
    await hub.start()
    await asyncio.sleep(0.3)  # let the listener psubscribe
    try:
        frame = MEDIA_HEADER.pack(1, bytes.fromhex(DEV), 1) + b"echo"
        await redis.publish(f"media:{CID}", _wire(hub_module.INSTANCE_ID, frame))
        # Give the listener a chance to (wrongly) deliver it.
        await asyncio.sleep(0.4)
        assert conn.frames == []
    finally:
        await hub.stop()
        await redis.aclose()


async def test_malformed_media_payload_is_ignored():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    hub = Hub(redis, session_factory=None)
    conn = FakeWS()
    hub.conns["dev-a"] = hub_module.Conn(ws=conn, user_id="u1", role="user", device_id="dev-a", channel_id=CID)
    await hub.start()
    await asyncio.sleep(0.3)  # let the listener psubscribe
    try:
        await redis.publish(f"media:{CID}", "not-valid-base64!!!")
        await asyncio.sleep(0.3)
        assert conn.frames == []
        # The listener must still be alive after a bad payload.
        frame = MEDIA_HEADER.pack(1, bytes.fromhex(DEV), 2) + b"ok"
        await redis.publish(f"media:{CID}", _wire(str(uuid.uuid4()), frame))
        assert await _wait_for(lambda: conn.frames == [frame])
    finally:
        await hub.stop()
        await redis.aclose()


async def test_frame_header_roundtrip():
    """Sanity: wire bytes survive base64 + instance prefix unchanged."""
    frame = MEDIA_HEADER.pack(9, bytes.fromhex(DEV), 42) + b"ciphertext"
    raw = base64.b64decode(_wire("x", frame))
    origin, _, payload = raw.partition(b"\x00")
    assert origin == b"x"
    epoch, sender, seq = struct.unpack(">I16sI", payload[:24])
    assert (epoch, sender.hex(), seq) == (9, DEV, 42)
    assert payload[24:] == b"ciphertext"
