#!/usr/bin/env python3
"""Capacity / latency smoke test for the SecureTalk realtime path.

Creates a channel with N members (default 20 = the enforced cap), opens one
WebSocket per member, then measures:
  * floor-control grant latency (press-to-speak path)
  * binary media relay latency between two members
  * that the 21st member is rejected by the 20-member cap

Run against a live API (dev launcher is fine):

    backend/.venv/Scripts/python.exe backend/run_live.py   # or uvicorn run_live:app
    backend/.venv/Scripts/python.exe scripts/loadtest_ws.py --base http://127.0.0.1:8000

Exit code 1 if any measured latency or correctness check fails.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import random
import statistics
import struct
import sys
import time
import uuid

import httpx
import websockets

MEDIA_HEADER = struct.Struct(">I16sI")


def _clean_env() -> None:
    # A local HTTP proxy in the environment breaks 127.0.0.1 testing.
    for key in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
        os.environ.pop(key, None)


def _pct(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, round((p / 100) * (len(ordered) - 1))))
    return round(ordered[idx], 2)


async def _register(client: httpx.AsyncClient, name: str) -> dict:
    # Registration is rate-limited per IP; a capacity run creates N+2 accounts.
    for attempt in range(8):
        r = await client.post("/api/v1/auth/register", json={"display_name": name, "password": "password123"})
        if r.status_code != 429:
            r.raise_for_status()
            return r.json()
        wait = 10 if attempt < 6 else 15
        print(f"rate limited creating {name}; waiting {wait}s", file=sys.stderr)
        await asyncio.sleep(wait)
    raise RuntimeError("registration rate-limited for too long; raise AUTH_RATE_LIMIT on the server")


async def _device(client: httpx.AsyncClient, token: str, name: str) -> str:
    headers = {"Authorization": f"Bearer {token}"}
    r = await client.post(
        "/api/v1/devices",
        headers=headers,
        json={
            "identity_pubkey": base64.b64encode(uuid.uuid4().bytes).decode(),
            "signing_pubkey": base64.b64encode(uuid.uuid4().bytes).decode(),
            "signed_prekey": base64.b64encode(uuid.uuid4().bytes).decode(),
            "name": name,
        },
    )
    r.raise_for_status()
    return r.json()["id"]


class Peer:
    def __init__(self, name: str, token: str, device_id: str, ws) -> None:
        self.name = name
        self.token = token
        self.device_id = device_id
        self.ws = ws
        self.messages: asyncio.Queue = asyncio.Queue()
        self.frames: asyncio.Queue = asyncio.Queue()

    async def reader(self) -> None:
        try:
            async for raw in self.ws:
                if isinstance(raw, bytes):
                    await self.frames.put(raw)
                else:
                    await self.messages.put(json.loads(raw))
        except Exception:
            pass

    async def wait_json(self, types: set[str], timeout: float = 10.0) -> dict | None:
        end = asyncio.get_running_loop().time() + timeout
        while True:
            left = end - asyncio.get_running_loop().time()
            if left <= 0:
                return None
            try:
                msg = await asyncio.wait_for(self.messages.get(), timeout=left)
            except TimeoutError:
                return None
            if msg.get("type") in types:
                return msg

    async def wait_frame(self, timeout: float = 10.0) -> bytes | None:
        try:
            return await asyncio.wait_for(self.frames.get(), timeout=timeout)
        except TimeoutError:
            return None


async def run(args: argparse.Namespace) -> dict:
    _clean_env()
    tag = f"{int(time.time())}_{random.randint(1000, 9999)}"
    base = args.base.rstrip("/")
    async with httpx.AsyncClient(base_url=base, timeout=30.0) as client:
        # 1. Accounts
        owner = await _register(client, f"load_owner_{tag}")
        owner_h = {"Authorization": f"Bearer {owner['access_token']}"}
        members = []
        for i in range(args.members - 1):
            tokens = await _register(client, f"load_m{i}_{tag}")
            members.append(tokens)

        # 2. Channel + invites
        r = await client.post("/api/v1/channels", headers=owner_h, json={"name": f"load-{tag}"})
        r.raise_for_status()
        channel = r.json()
        cid = channel["id"]
        r = await client.post(f"/api/v1/channels/{cid}/invites", headers=owner_h)
        r.raise_for_status()
        invite = r.json()["invite_code"]
        for tokens in members:
            r = await client.post(
                f"/api/v1/channels/{cid}/join",
                headers={"Authorization": f"Bearer {tokens['access_token']}"},
                json={"invite_code": invite},
            )
            r.raise_for_status()

        # 3. Devices + WebSocket joins
        participants = [("owner", owner)]
        participants += [(f"m{i}", t) for i, t in enumerate(members)]
        peers: list[Peer] = []
        try:
            for name, tokens in participants:
                did = await _device(client, tokens["access_token"], f"load-{name}")
                ws = await websockets.connect(f"{args.ws_base}/ws?token={tokens['access_token']}", max_size=None)
                peer = Peer(name, tokens["access_token"], did, ws)
                peers.append(peer)
                asyncio.create_task(peer.reader())
                await ws.send(json.dumps({"type": "join", "data": {"channel_id": cid, "device_id": did}}))
                joined = await peer.wait_json({"joined", "error"}, timeout=10)
                if joined is None or joined["type"] != "joined":
                    raise RuntimeError(f"join failed for {name}: {joined}")
            await asyncio.sleep(0.5)  # presence settles

            # 4. Floor + media rounds
            grant_ms: list[float] = []
            relay_ms: list[float] = []
            delivered = 0
            seq = 0
            epoch = 1
            for rnd in range(args.rounds):
                holder = peers[rnd % len(peers)]
                listener = peers[(rnd + 1) % len(peers)]
                # Drain stale frames so latency is measured on a fresh frame.
                while not listener.frames.empty():
                    listener.frames.get_nowait()
                t0 = time.perf_counter()
                await holder.ws.send(json.dumps({"type": "floor.request", "data": {"channel_id": cid}}))
                granted = await holder.wait_json({"floor.granted", "floor.denied"}, timeout=10)
                if granted is None or granted["type"] != "floor.granted":
                    # Someone else holds the floor: wait for our turn.
                    await holder.wait_json({"floor.granted"}, timeout=15)
                grant_ms.append((time.perf_counter() - t0) * 1000)

                seq += 1
                frame = MEDIA_HEADER.pack(epoch, bytes.fromhex(holder.device_id), seq) + f"audio-{rnd}".encode()
                t1 = time.perf_counter()
                await holder.ws.send(frame)
                got = await listener.wait_frame(timeout=10)
                if got is None:
                    raise RuntimeError(f"media not relayed in round {rnd}")
                relay_ms.append((time.perf_counter() - t1) * 1000)
                delivered += 1
                await holder.ws.send(json.dumps({"type": "floor.release", "data": {"channel_id": cid}}))
                await holder.wait_json({"floor.released"}, timeout=10)
                await asyncio.sleep(args.round_gap)
        finally:
            for peer in peers:
                try:
                    await peer.ws.close()
                except Exception:
                    pass

        # 5. 21st member must be rejected (409 channel_full)
        extra = await _register(client, f"load_extra_{tag}")
        code_status = None
        r = await client.post(f"/api/v1/channels/{cid}/invites", headers=owner_h)
        if r.status_code == 200:
            invite = r.json()["invite_code"]
            r = await client.post(
                f"/api/v1/channels/{cid}/join",
                headers={"Authorization": f"Bearer {extra['access_token']}"},
                json={"invite_code": invite},
            )
            code_status = r.status_code

    return {
        "channel_id": cid,
        "members": args.members,
        "rounds": args.rounds,
        "floor_grant_ms": {"p50": _pct(grant_ms, 50), "p95": _pct(grant_ms, 95), "max": _pct(grant_ms, 100)},
        "media_relay_ms": {"p50": _pct(relay_ms, 50), "p95": _pct(relay_ms, 95), "max": _pct(relay_ms, 100)},
        "media_frames_delivered": delivered,
        "cap_21st_member_status": code_status,
        "mean_grant_ms": round(statistics.mean(grant_ms), 2) if grant_ms else None,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8000", help="REST base URL")
    parser.add_argument("--ws-base", default=None, help="WebSocket base URL (defaults to --base with ws scheme)")
    parser.add_argument("--members", type=int, default=20)
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--round-gap", type=float, default=0.05, help="pause between rounds (seconds)")
    parser.add_argument("--max-p95-grant-ms", type=float, default=500.0)
    parser.add_argument("--max-p95-relay-ms", type=float, default=300.0)
    args = parser.parse_args()
    if not (2 <= args.members <= 20):
        parser.error("--members must be between 2 and 20 (server cap)")
    if args.ws_base is None:
        args.ws_base = args.base.replace("http://", "ws://").replace("https://", "wss://")

    try:
        summary = asyncio.run(run(args))
    except Exception as exc:  # noqa: BLE001 - CLI tool
        print(json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, indent=2))
        return 1
    p95_grant = summary["floor_grant_ms"]["p95"] or 0
    p95_relay = summary["media_relay_ms"]["p95"] or 0
    ok = (
        summary["cap_21st_member_status"] == 409
        and summary["media_frames_delivered"] == args.rounds
        and p95_grant <= args.max_p95_grant_ms
        and p95_relay <= args.max_p95_relay_ms
    )
    summary["ok"] = ok
    print(json.dumps(summary, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
