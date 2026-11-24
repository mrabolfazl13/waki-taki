#!/usr/bin/env python3
"""Verify two API replicas share realtime state through Redis pub/sub.

Prereqs: two API processes with the same DATABASE_URL / REDIS_URL / SECRET_KEY,
e.g.

    (cd backend && python serve_local.py --port 8011)
    (cd backend && python serve_local.py --port 8012)
    python scripts/multiinstance_check.py --a http://127.0.0.1:8011 --b http://127.0.0.1:8012

Checks:
  1. a token minted by instance A is accepted by instance B (shared secret)
  2. presence is shared (each joined session sees both devices)
  3. floor control is shared (a request on B is denied while A holds the floor)
  4. media published on A is relayed to a subscriber connected to B
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import random
import struct
import sys
import time
import uuid

import httpx
import websockets

HDR = struct.Struct(">I16sI")


def _clean_env() -> None:
    for key in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
        os.environ.pop(key, None)


async def _register(c: httpx.AsyncClient, name: str) -> dict:
    r = await c.post("/api/v1/auth/register", json={"display_name": name, "password": "password123"})
    r.raise_for_status()
    return r.json()


async def _device(c: httpx.AsyncClient, token: str) -> str:
    r = await c.post(
        "/api/v1/devices",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "identity_pubkey": base64.b64encode(uuid.uuid4().bytes).decode(),
            "signing_pubkey": base64.b64encode(uuid.uuid4().bytes).decode(),
            "signed_prekey": base64.b64encode(uuid.uuid4().bytes).decode(),
            "name": "multi",
        },
    )
    r.raise_for_status()
    return r.json()["id"]


class Client:
    def __init__(self, ws) -> None:
        self.ws = ws
        self.texts: asyncio.Queue = asyncio.Queue()
        self.frames: asyncio.Queue = asyncio.Queue()

    async def reader(self) -> None:
        try:
            async for raw in self.ws:
                if isinstance(raw, bytes):
                    await self.frames.put(raw)
                else:
                    await self.texts.put(json.loads(raw))
        except Exception:
            pass

    async def until(self, types: set[str], timeout: float = 10.0) -> dict | None:
        loop = asyncio.get_running_loop()
        end = loop.time() + timeout
        while loop.time() < end:
            try:
                msg = await asyncio.wait_for(self.texts.get(), timeout=end - loop.time())
            except (TimeoutError, asyncio.TimeoutError):
                return None
            if msg.get("type") in types:
                return msg
        return None


async def run(a: str, b: str, timeout: float) -> dict:
    _clean_env()
    tag = f"{int(time.time())}_{random.randint(1000, 9999)}"
    result: dict = {"ok": False}
    async with httpx.AsyncClient(base_url=a, timeout=30.0) as ca, httpx.AsyncClient(base_url=b, timeout=30.0) as cb:
        owner = await _register(ca, f"multi_owner_{tag}")
        memo = await _register(ca, f"multi_memo_{tag}")
        oh = {"Authorization": f"Bearer {owner['access_token']}"}
        r = await ca.post("/api/v1/channels", headers=oh, json={"name": f"multi-{tag}"})
        r.raise_for_status()
        cid = r.json()["id"]
        r = await ca.post(f"/api/v1/channels/{cid}/invites", headers=oh)
        invite = r.json()["invite_code"]
        r = await cb.post(
            f"/api/v1/channels/{cid}/join",
            headers={"Authorization": f"Bearer {memo['access_token']}"},
            json={"invite_code": invite},
        )
        r.raise_for_status()
        owner_dev = await _device(ca, owner["access_token"])
        memo_dev = await _device(cb, memo["access_token"])

        ws_a = await websockets.connect(f"{a.replace('http', 'ws')}/ws?token={owner['access_token']}")
        ws_b = await websockets.connect(f"{b.replace('http', 'ws')}/ws?token={memo['access_token']}")
        ca_ = Client(ws_a)
        cb_ = Client(ws_b)
        asyncio.create_task(ca_.reader())
        asyncio.create_task(cb_.reader())
        try:
            await ws_a.send(json.dumps({"type": "join", "data": {"channel_id": cid, "device_id": owner_dev}}))
            j1 = await ca_.until({"joined"})
            await ws_b.send(json.dumps({"type": "join", "data": {"channel_id": cid, "device_id": memo_dev}}))
            j2 = await cb_.until({"joined"})
            result["token_accepted_on_other_instance"] = bool(j1 and j2)
            present = sorted(j2["data"]["present"]) if j2 else []
            result["presence_shared"] = owner_dev in present and memo_dev in present

            await ws_a.send(json.dumps({"type": "floor.request", "data": {"channel_id": cid}}))
            g1 = await ca_.until({"floor.granted", "floor.denied"})
            await ws_b.send(json.dumps({"type": "floor.request", "data": {"channel_id": cid}}))
            d2 = await cb_.until({"floor.granted", "floor.denied"})
            result["floor_shared"] = bool(
                g1 and g1["type"] == "floor.granted" and d2 and d2["type"] == "floor.denied"
            )

            frame = HDR.pack(1, bytes.fromhex(owner_dev), 1) + b"cross-instance-audio"
            await ws_a.send(frame)
            got = None
            try:
                got = await asyncio.wait_for(cb_.frames.get(), timeout=timeout)
            except (TimeoutError, asyncio.TimeoutError):
                pass
            result["media_crossed_instances"] = got == frame
        finally:
            await ws_a.close()
            await ws_b.close()

    result["ok"] = all(
        result.get(k) for k in ("token_accepted_on_other_instance", "presence_shared", "floor_shared", "media_crossed_instances")
    )
    return result


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--a", default="http://127.0.0.1:8000")
    p.add_argument("--b", default="http://127.0.0.1:8001")
    p.add_argument("--timeout", type=float, default=10.0)
    args = p.parse_args()
    try:
        res = asyncio.run(run(args.a.rstrip("/"), args.b.rstrip("/"), args.timeout))
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, indent=2))
        return 1
    print(json.dumps(res, indent=2))
    return 0 if res["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
