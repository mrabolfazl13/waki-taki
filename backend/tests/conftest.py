"""Shared fixtures: aiosqlite DB + fakeredis + httpx ASGI client."""
from __future__ import annotations

import asyncio
import base64
import os
import struct

import fakeredis.aioredis
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import async_sessionmaker

os.environ["ST_DATABASE_URL"] = "sqlite+aiosqlite:///./test.db"
os.environ["ST_REDIS_URL"] = "redis://fakeredis"
os.environ["ST_AUTO_CREATE_TABLES"] = "true"
os.environ["ST_JWT_SECRET"] = "test-secret-0123456789abcdef0123456789abcdef"
os.environ["ST_AUTH_RATE_LIMIT"] = "1000"
os.environ["ST_INVITE_JOIN_RATE_LIMIT"] = "1000"
os.environ["ST_INVITE_CREATE_RATE_LIMIT"] = "1000"

from app.core.config import get_settings  # noqa: E402

settings = get_settings()

from app.db import Base, make_engine, make_sessionmaker  # noqa: E402
from app.deps import init_state  # noqa: E402
from app.main import create_app  # noqa: E402


@pytest_asyncio.fixture
async def app_env():
    if os.path.exists("./test.db"):
        os.remove("./test.db")
    engine = make_engine(settings.database_url)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    session_factory = make_sessionmaker(engine)
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    # Init state BEFORE create_app so it reuses these instances.
    init_state(engine, session_factory, redis)
    app = create_app()
    from app.realtime.hub import Hub

    hub = Hub(redis, session_factory)
    await hub.start()
    app.state.hub = hub
    app.state.session_factory = session_factory
    yield {"app": app, "redis": redis, "session_factory": session_factory}
    await hub.stop()
    await redis.flushall()
    await redis.close()
    await engine.dispose()
    if os.path.exists("./test.db"):
        os.remove("./test.db")


@pytest_asyncio.fixture
async def client(app_env):
    async with AsyncClient(
        transport=ASGITransport(app=app_env["app"]), base_url="http://test"
    ) as c:
        yield c


# ---------- helpers ----------

async def register(client, name: str, password: str = "password123") -> dict:
    r = await client.post("/api/v1/auth/register", json={"display_name": name, "password": password})
    assert r.status_code == 201, r.text
    return r.json()


def auth_hdr(tokens: dict) -> dict:
    return {"Authorization": f"Bearer {tokens['access_token']}"}


async def make_device(client, headers, device_hex: str | None = None) -> str:
    did = device_hex or ("a" * 32)
    r = await client.post(
        "/api/v1/devices",
        headers=headers,
        json={
            "identity_pubkey": base64.b64encode(b"\x01" * 32).decode(),
            "signing_pubkey": base64.b64encode(b"\x02" * 32).decode(),
            "signed_prekey": base64.b64encode(b"\x03" * 32).decode(),
            "name": "test-device",
        },
    )
    assert r.status_code == 201, r.text
    device_id = r.json()["id"]
    return device_id


async def setup_channel(client, owner_name="alice", member_names=()) -> dict:
    """Owner creates channel + invite; members join. Returns ids/codes."""
    owner = await register(client, owner_name)
    h = auth_hdr(owner)
    r = await client.post("/api/v1/channels", headers=h, json={"name": f"chan-{owner_name}"})
    assert r.status_code == 201, r.text
    channel_id = r.json()["id"]
    r = await client.post(f"/api/v1/channels/{channel_id}/invites", headers=h)
    invite = r.json()["invite_code"]
    members = {"owner": owner, "channel_id": channel_id, "invite": invite}
    for name in member_names:
        t = await register(client, name)
        r = await client.post(
            f"/api/v1/channels/{channel_id}/join",
            headers=auth_hdr(t),
            json={"invite_code": invite},
        )
        assert r.status_code == 200, r.text
        members[name] = t
    return members
