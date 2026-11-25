"""Prometheus text-exposition metrics endpoint (dependency-free).

Exposes minimal but useful operational metrics at ``GET /metrics``:
- process uptime and per-route request counters
- realtime hub gauges (connected devices / users)
- media relay and floor-grant counters

Intentionally no third-party ``prometheus_client`` dependency: the text
format (version 0.0.4) is small and stable. Auth is intentionally absent so
scrapers can pull it; the endpoint leaks only aggregate counters and is safe
to firewall at the edge if desired.
"""
from __future__ import annotations

import time

from fastapi import APIRouter, Request, Response

router = APIRouter(tags=["ops"])

_STARTED_AT = time.time()
_requests: dict[str, int] = {}
_media_frames = 0
_media_bytes = 0
_floor_grants = 0
_floor_denials = 0

CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"


def observe_request(method: str, path: str, status: int) -> None:
    key = f'st_requests_total{{method="{method}",path="{path}",code="{status}"}}'
    _requests[key] = _requests.get(key, 0) + 1


def observe_media(frames: int, total_bytes: int) -> None:
    global _media_frames, _media_bytes
    _media_frames += frames
    _media_bytes += total_bytes


def observe_floor(granted: bool) -> None:
    global _floor_grants, _floor_denials
    if granted:
        _floor_grants += 1
    else:
        _floor_denials += 1


def _render(hub_conns: int, hub_users: int) -> str:
    lines = [
        "# HELP st_uptime_seconds Process uptime in seconds.",
        "# TYPE st_uptime_seconds gauge",
        f"st_uptime_seconds {time.time() - _STARTED_AT:.1f}",
        "# HELP st_realtime_devices Current realtime connections by device.",
        "# TYPE st_realtime_devices gauge",
        f"st_realtime_devices {hub_conns}",
        "# HELP st_realtime_users Current realtime connections by user.",
        "# TYPE st_realtime_users gauge",
        f"st_realtime_users {hub_users}",
        "# HELP st_media_frames_total Media frames relayed since process start.",
        "# TYPE st_media_frames_total counter",
        f"st_media_frames_total {_media_frames}",
        "# HELP st_media_bytes_total Media bytes relayed since process start.",
        "# TYPE st_media_bytes_total counter",
        f"st_media_bytes_total {_media_bytes}",
        "# HELP st_floor_grants_total Floor grants since process start.",
        "# TYPE st_floor_grants_total counter",
        f"st_floor_grants_total {_floor_grants}",
        "# HELP st_floor_denials_total Floor denials since process start.",
        "# TYPE st_floor_denials_total counter",
        f"st_floor_denials_total {_floor_denials}",
    ]
    for key, count in sorted(_requests.items()):
        lines.append(f"# TYPE {key.split('{')[0]} counter")
        lines.append(f"{key} {count}")
    return "\n".join(lines) + "\n"


@router.get("/metrics")
async def metrics(request: Request) -> Response:
    hub = getattr(request.app.state, "hub", None)
    conns = getattr(hub, "conns", {}) if hub else {}
    users = {c.user_id for c in conns.values()} if conns else set()
    return Response(content=_render(len(conns), len(users)), media_type=CONTENT_TYPE)
