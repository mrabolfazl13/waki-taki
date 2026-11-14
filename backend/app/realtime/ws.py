"""WebSocket route (mounted at /ws and /api/v1/ws)."""
from __future__ import annotations

from fastapi import APIRouter, Query, WebSocket

from ..core.config import get_settings
from .hub import Hub

settings = get_settings()
router = APIRouter()


def _get_hub(ws: WebSocket) -> Hub:
    return ws.app.state.hub


@router.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str = Query(default="")):
    hub: Hub = _get_hub(ws)
    await hub.serve(ws, token)


@router.websocket("/api/v1/ws")
async def ws_endpoint_v1(ws: WebSocket, token: str = Query(default="")):
    hub: Hub = _get_hub(ws)
    await hub.serve(ws, token)
