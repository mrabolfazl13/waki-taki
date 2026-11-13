"""Redis-authoritative floor control (ARCHITECTURE.md §4).

State in Redis hash `floor:{channel_id}`:
  holder, holder_device_id, expires_at_ms, epoch
Queue: list `floorq:{channel_id}` of device ids (max 20).
"""
from __future__ import annotations

import time
from dataclasses import dataclass

from redis.exceptions import WatchError

from ..core.config import get_settings
from ..core.ratelimit import check_rate_limit

settings = get_settings()

FLOOR_KEY = "floor:{channel_id}"
QUEUE_KEY = "floorq:{channel_id}"


@dataclass
class FloorResult:
    granted: bool
    reason: str | None = None
    holder_device_id: str | None = None
    queue_position: int | None = None
    lease_expires_ms: int | None = None


def _now_ms() -> int:
    return int(time.time() * 1000)


def floor_key(channel_id: str) -> str:
    return FLOOR_KEY.format(channel_id=channel_id)


def queue_key(channel_id: str) -> str:
    return QUEUE_KEY.format(channel_id=channel_id)


async def _active_holder(pipe, key: str) -> str | None:
    """Return holder device id if an unexpired lease exists (pipe in watch mode)."""
    state = await pipe.hgetall(key)
    holder = state.get("holder_device_id")
    if not holder:
        return None
    expires = int(state.get("expires_at_ms", "0"))
    if expires <= _now_ms():
        return None
    return holder


async def request_floor(
    redis, channel_id: str, device_id: str, *, is_admin: bool = False, override: bool = False
) -> FloorResult:
    """Request or override the floor. Redis CAS via WATCH/MULTI (receive-order arbitration)."""
    # Rate limit only normal requests; admin override bypasses.
    if not override and not await check_rate_limit(
        redis, f"floor:{device_id}", settings.floor_rate_limit, settings.floor_rate_window_seconds
    ):
        return FloorResult(granted=False, reason="rate_limited")

    key = floor_key(channel_id)
    if override and is_admin:
        lease_expires = _now_ms() + settings.floor_lease_seconds * 1000
        await redis.hset(
            key,
            mapping={
                "holder_device_id": device_id,
                "expires_at_ms": str(lease_expires),
                "granted_at_ms": str(_now_ms()),
            },
        )
        await redis.lrem(queue_key(channel_id), 0, device_id)
        return FloorResult(granted=True, holder_device_id=device_id, lease_expires_ms=lease_expires)

    async with redis.pipeline(transaction=True) as pipe:
        while True:
            try:
                await pipe.watch(key)
                holder = await _active_holder(pipe, key)
                if holder == device_id:
                    # renew lease (audio activity heartbeat)
                    lease_expires = _now_ms() + settings.floor_lease_seconds * 1000
                    pipe.multi()
                    await pipe.hset(key, "expires_at_ms", str(lease_expires))
                    await pipe.execute()
                    return FloorResult(granted=True, holder_device_id=device_id, lease_expires_ms=lease_expires)
                if holder is not None:
                    qpos = int(await redis.llen(queue_key(channel_id))) + 1
                    if qpos > settings.floor_queue_depth:
                        return FloorResult(granted=False, reason="queue_full", holder_device_id=holder)
                    await redis.lpush(queue_key(channel_id), device_id)
                    return FloorResult(
                        granted=False, reason="busy", holder_device_id=holder, queue_position=qpos
                    )
                lease_expires = _now_ms() + settings.floor_lease_seconds * 1000
                pipe.multi()
                await pipe.hset(
                    key,
                    mapping={
                        "holder_device_id": device_id,
                        "expires_at_ms": str(lease_expires),
                        "granted_at_ms": str(_now_ms()),
                    },
                )
                await pipe.execute()
                return FloorResult(granted=True, holder_device_id=device_id, lease_expires_ms=lease_expires)
            except WatchError:
                continue  # concurrent request lost: retry (receive order preserved by Redis CAS)


async def release_floor(redis, channel_id: str, device_id: str) -> str | None:
    """Release if holder. Returns next granted device id from queue, if any."""
    key = floor_key(channel_id)
    holder = await redis.hget(key, "holder_device_id")
    if not holder or holder != device_id:
        return None
    await redis.delete(key)
    nxt = await redis.rpop(queue_key(channel_id))
    if nxt:
        lease_expires = _now_ms() + settings.floor_lease_seconds * 1000
        await redis.hset(
            key,
            mapping={
                "holder_device_id": nxt,
                "expires_at_ms": str(lease_expires),
                "granted_at_ms": str(_now_ms()),
            },
        )
        return nxt
    return None


async def floor_state(redis, channel_id: str) -> dict:
    state = await redis.hgetall(floor_key(channel_id))
    holder = state.get("holder_device_id")
    if holder and int(state.get("expires_at_ms", "0")) <= _now_ms():
        holder = None
    return {"state": "speaking" if holder else "idle", "holder_device_id": holder}


async def clear_channel(redis, channel_id: str) -> None:
    await redis.delete(floor_key(channel_id), queue_key(channel_id))
