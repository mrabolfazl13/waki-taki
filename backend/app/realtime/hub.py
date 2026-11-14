"""WebSocket hub: signaling relay, presence, floor control, binary media relay.

Text frames: {"type": "...", "data": {...}} per ARCHITECTURE.md §6.
Binary media frame: epoch(4) | sender_device_id(16) | seq(4) | ciphertext+tag.
Backend reads ONLY the plaintext header (never stores/decrypts media).
"""
from __future__ import annotations

import asyncio
import json
import logging
import struct
import uuid
from dataclasses import dataclass, field

from fastapi import WebSocket, WebSocketDisconnect
from sqlalchemy import select

from ..core.config import get_settings
from ..core.security import TokenError, decode_token
from ..floor_control import service as floor
from ..models import Channel, Device, Membership, User

settings = get_settings()
log = logging.getLogger("securetalk.hub")

INSTANCE_ID = uuid.uuid4().hex

MEDIA_HEADER = struct.Struct(">I16sI")  # epoch, sender device id (16 bytes), seq


@dataclass
class Conn:
    ws: WebSocket
    user_id: str
    role: str
    device_id: str | None = None
    channel_id: str | None = None
    tasks: list = field(default_factory=list)


class Hub:
    def __init__(self, redis, session_factory):
        self.redis = redis
        self.session_factory = session_factory
        self.conns: dict[str, Conn] = {}  # device_id -> Conn
        self._pubsub_task: asyncio.Task | None = None
        self._last_seq_key = "mediaseq:{channel_id}:{device_id}"

    # ---------- lifecycle ----------

    async def start(self) -> None:
        if self._pubsub_task is None:
            self._pubsub_task = asyncio.create_task(self._pubsub_loop())

    async def stop(self) -> None:
        if self._pubsub_task is not None:
            self._pubsub_task.cancel()
            self._pubsub_task = None

    async def _pubsub_loop(self) -> None:
        while True:
            pubsub = None
            try:
                # Binary media frames must survive as bytes: never decode on pubsub.
                pubsub = self.redis.pubsub(decode_responses=False)
                await pubsub.psubscribe("media:*", "events:*")
                async for message in pubsub.listen():
                    if message["type"] != "pmessage":
                        continue
                    channel = message["channel"]
                    if isinstance(channel, (bytes, bytearray)):
                        channel = bytes(channel).decode(errors="replace")
                    payload = message["data"]
                    if channel.startswith("media:"):
                        await self._on_remote_media(channel.split(":", 1)[1], payload)
                    else:
                        if isinstance(payload, (bytes, bytearray)):
                            payload = bytes(payload).decode(errors="replace")
                        await self._on_channel_event(channel.split(":", 1)[1], payload)
            except asyncio.CancelledError:
                break
            except Exception:
                log.exception("pubsub loop error")
                await asyncio.sleep(1)
            finally:
                if pubsub is not None:
                    try:
                        await pubsub.close()
                    except Exception:
                        pass

    # ---------- channel events (join/leave/epoch from REST) ----------

    async def publish_event(self, channel_id: str, event: dict) -> None:
        await self.redis.publish(f"events:{channel_id}", json.dumps(event))

    async def _on_channel_event(self, channel_id: str, payload: str) -> None:
        for conn in list(self.conns.values()):
            if conn.channel_id == channel_id:
                await safe_send(conn.ws, {"type": "channel.event", "data": json.loads(payload)})

    async def _on_remote_media(self, channel_id: str, payload) -> None:
        # payload: b"instance_id\x00" + frame
        if not isinstance(payload, (bytes, bytearray)):
            return
        raw = bytes(payload)
        origin, _, frame = raw.partition(b"\x00")
        if origin.decode(errors="replace") == INSTANCE_ID:
            return  # already fanned out locally
        await self._fanout_media(channel_id, frame, exclude_device=None)

    # ---------- media relay ----------

    async def _fanout_media(self, channel_id: str, frame: bytes, exclude_device: str | None) -> None:
        for conn in list(self.conns.values()):
            if conn.channel_id == channel_id and conn.device_id != exclude_device:
                try:
                    await conn.ws.send_bytes(frame)
                except Exception:
                    log.exception("media fanout send failed")

    async def handle_media(self, conn: Conn, data: bytes) -> None:
        if len(data) < MEDIA_HEADER.size:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "media_frame_too_short"}})
            return
        if conn.channel_id is None or conn.device_id is None:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "not_joined"}})
            return
        epoch, sender_raw, seq = MEDIA_HEADER.unpack_from(data, 0)
        sender_hex = sender_raw.hex()
        if sender_hex != conn.device_id:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "sender_mismatch"}})
            return
        channel_id = conn.channel_id

        # Floor ownership: only the current floor holder may transmit.
        state = await floor.floor_state(self.redis, channel_id)
        if state["holder_device_id"] != conn.device_id:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "floor_not_held"}})
            return

        # Replay window (sliding, per sender, Redis-authoritative).
        seq_key = self._last_seq_key.format(channel_id=channel_id, device_id=conn.device_id)
        window = settings.media_replay_window
        if await self.redis.zscore(seq_key, seq) is not None:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "replay_detected"}})
            await self._record_security(channel_id, conn.user_id, "media.replay")
            return
        max_row = await self.redis.zrevrange(seq_key, 0, 0, withscores=True)
        max_seq = int(max_row[0][1]) if max_row else 0
        if seq <= max_seq - window:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "seq_too_old"}})
            return
        await self.redis.zadd(seq_key, {seq: seq})
        await self.redis.zremrangebyscore(seq_key, 0, seq - window)
        await self.redis.expire(seq_key, 600)

        await self._fanout_media(channel_id, data, exclude_device=conn.device_id)
        # Cross-instance fan-out (publisher identity prefix avoids echo).
        await self.redis.publish(
            f"media:{channel_id}", INSTANCE_ID.encode() + b"\x00" + data
        )

    async def _record_security(self, channel_id: str, user_id: str, type: str) -> None:
        try:
            async with self.session_factory() as session:
                from ..models import SecurityEvent

                session.add(SecurityEvent(type=type, severity="warning", channel_id=channel_id, user_id=user_id, detail={}))
                await session.commit()
        except Exception:
            log.exception("failed to record security event")

    # ---------- connection handling ----------

    async def serve(self, ws: WebSocket, token: str) -> None:
        await ws.accept()
        try:
            payload = decode_token(settings.jwt_secret, settings.jwt_algorithm, token, "access")
        except TokenError:
            await safe_send(ws, {"type": "error", "data": {"code": "invalid_token"}})
            await ws.close(code=4401)
            return
        async with self.session_factory() as session:
            user = await session.get(User, payload["sub"])
        if user is None or user.disabled:
            await safe_send(ws, {"type": "error", "data": {"code": "account_unavailable"}})
            await ws.close(code=4401)
            return
        conn = Conn(ws=ws, user_id=user.id, role=user.role.value)
        try:
            while True:
                raw = await ws.receive()
                if raw["type"] == "websocket.disconnect":
                    break
                if "bytes" in raw and raw["bytes"] is not None:
                    await self.handle_media(conn, raw["bytes"])
                    continue
                text = raw.get("text")
                if text is None:
                    continue
                try:
                    msg = json.loads(text)
                    mtype, data = msg["type"], msg.get("data") or {}
                except Exception:
                    await safe_send(ws, {"type": "error", "data": {"code": "bad_json"}})
                    continue
                await self.handle_text(conn, mtype, data)
        except WebSocketDisconnect:
            pass
        finally:
            await self._cleanup(conn)

    async def _cleanup(self, conn: Conn) -> None:
        if conn.device_id and self.conns.get(conn.device_id) is conn:
            del self.conns[conn.device_id]
        if conn.channel_id and conn.device_id:
            await self.redis.srem(f"presence:{conn.channel_id}", conn.device_id)
            await self.publish_event(
                conn.channel_id,
                {"channel_id": conn.channel_id, "device_id": conn.device_id, "state": "offline"},
            )
            prev = await floor.release_floor(self.redis, conn.channel_id, conn.device_id)
            if prev:
                await self._notify_grant(conn.channel_id, prev)
            else:
                await self._broadcast_floor_state(conn.channel_id)

    async def handle_text(self, conn: Conn, mtype: str, data: dict) -> None:
        if mtype == "ping":
            await safe_send(conn.ws, {"type": "pong", "data": {}})
            return
        if mtype == "join":
            await self._handle_join(conn, data)
            return
        if conn.channel_id is None:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "not_joined"}})
            return
        channel_id = conn.channel_id
        if mtype.startswith("signaling."):
            await self._relay_signaling(conn, mtype, data)
        elif mtype == "floor.request":
            await self._floor_request(conn, channel_id, data, override=False)
        elif mtype == "floor.override":
            await self._floor_request(conn, channel_id, data, override=True)
        elif mtype == "floor.release":
            await self._floor_release(conn, channel_id)
        else:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "unknown_type", "type": mtype}})

    # ---------- join / presence ----------

    async def _handle_join(self, conn: Conn, data: dict) -> None:
        channel_id = str(data.get("channel_id", ""))
        device_id = str(data.get("device_id", ""))
        if not channel_id or not device_id:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "join_requires_ids"}})
            return
        async with self.session_factory() as session:
            device = await session.get(Device, device_id)
            if device is None or device.user_id != conn.user_id or device.revoked_at is not None:
                await safe_send(conn.ws, {"type": "error", "data": {"code": "device_not_owned"}})
                return
            channel = await session.get(Channel, channel_id)
            membership = await session.scalar(
                select(Membership).where(
                    Membership.channel_id == channel_id, Membership.user_id == conn.user_id
                )
            )
        if channel is None or channel.deleted_at is not None or membership is None:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "not_a_member"}})
            await self._record_security(channel_id, conn.user_id, "ws.unauthorized_join")
            return
        conn.device_id = device_id
        conn.channel_id = channel_id
        self.conns[device_id] = conn
        await self.redis.sadd(f"presence:{channel_id}", device_id)
        await self.redis.hset(
            f"ws:{device_id}", mapping={"instance": INSTANCE_ID, "user_id": conn.user_id}
        )
        await self.publish_event(
            channel_id, {"channel_id": channel_id, "device_id": device_id, "state": "online"}
        )
        members = sorted(await self.redis.smembers(f"presence:{channel_id}"))
        await safe_send(
            conn.ws,
            {
                "type": "joined",
                "data": {"channel_id": channel_id, "device_id": device_id, "epoch": channel.epoch,
                         "present": members},
            },
        )
        await self._broadcast_floor_state(channel_id)

    # ---------- signaling ----------

    async def _relay_signaling(self, conn: Conn, mtype: str, data: dict) -> None:
        target_device = str(data.get("target_device_id", ""))
        target = self.conns.get(target_device)
        # Verify target is in the same channel.
        if target is None or target.channel_id != conn.channel_id:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "target_unavailable"}})
            return
        out = {
            "type": mtype.replace("signaling.", "signaling."),
            "data": {
                "channel_id": conn.channel_id,
                "from_device_id": conn.device_id,
                "sdp": data.get("sdp"),
                "ice": data.get("ice"),
            },
        }
        await safe_send(target.ws, out)

    # ---------- floor ----------

    async def _floor_request(self, conn: Conn, channel_id: str, data: dict, override: bool) -> None:
        device_id = conn.device_id or str(data.get("device_id", ""))
        if not device_id:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "no_device"}})
            return
        is_admin = conn.role == "admin"
        if override and not is_admin:
            await safe_send(conn.ws, {"type": "error", "data": {"code": "override_not_allowed"}})
            return
        result = await floor.request_floor(
            self.redis, channel_id, device_id, is_admin=is_admin, override=override
        )
        if result.granted:
            await safe_send(
                conn.ws,
                {"type": "floor.granted", "data": {
                    "channel_id": channel_id, "holder_device_id": device_id,
                    "lease_expires_ms": result.lease_expires_ms,
                }},
            )
            await self._broadcast_floor_state(channel_id, exclude=device_id)
        else:
            await safe_send(
                conn.ws,
                {"type": "floor.denied", "data": {
                    "channel_id": channel_id, "reason": result.reason,
                    "holder_device_id": result.holder_device_id,
                    "queue_position": result.queue_position,
                }},
            )

    async def _floor_release(self, conn: Conn, channel_id: str) -> None:
        device_id = conn.device_id
        if not device_id:
            return
        previous = await floor.floor_state(self.redis, channel_id)
        nxt = await floor.release_floor(self.redis, channel_id, device_id)
        released_msg = {
            "type": "floor.released",
            "data": {
                "channel_id": channel_id, "previous_holder": previous.get("holder_device_id"),
            },
        }
        await safe_send(conn.ws, released_msg)
        for c in list(self.conns.values()):
            if c.channel_id == channel_id and c.device_id != device_id:
                await safe_send(c.ws, released_msg)
        if nxt:
            await self._notify_grant(channel_id, nxt)
        else:
            await self._broadcast_floor_state(channel_id)

    async def _notify_grant(self, channel_id: str, device_id: str) -> None:
        lease = await self.redis.hget(floor.floor_key(channel_id), "expires_at_ms")
        target = self.conns.get(device_id)
        if target is not None:
            await safe_send(
                target.ws,
                {"type": "floor.granted", "data": {
                    "channel_id": channel_id, "holder_device_id": device_id,
                    "lease_expires_ms": int(lease) if lease else None,
                }},
            )
        await self._broadcast_floor_state(channel_id)

    async def _broadcast_floor_state(self, channel_id: str, exclude: str | None = None) -> None:
        state = await floor.floor_state(self.redis, channel_id)
        msg = {
            "type": "floor.event",
            "data": {
                "channel_id": channel_id,
                "state": state["state"],
                "holder_device_id": state["holder_device_id"],
            },
        }
        for conn in list(self.conns.values()):
            if conn.channel_id == channel_id and conn.device_id != exclude:
                await safe_send(conn.ws, msg)


async def safe_send(ws: WebSocket, payload: dict) -> None:
    try:
        await ws.send_text(json.dumps(payload))
    except Exception:
        pass
