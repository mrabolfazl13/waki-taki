"""Metrics endpoint + counters."""
from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.api import metrics as metrics_mod


@pytest.fixture(autouse=True)
def _reset_counters():
    metrics_mod._requests.clear()
    metrics_mod._media_frames = metrics_mod._media_bytes = 0
    metrics_mod._floor_grants = metrics_mod._floor_denials = 0
    yield
    metrics_mod._requests.clear()
    metrics_mod._media_frames = metrics_mod._media_bytes = 0
    metrics_mod._floor_grants = metrics_mod._floor_denials = 0


@pytest.mark.asyncio
async def test_metrics_endpoint_basic(client: AsyncClient):
    await client.get("/health")  # seed a route counter
    resp = await client.get("/metrics")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/plain")
    body = resp.text
    assert "st_uptime_seconds" in body
    assert "st_realtime_devices 0" in body
    assert "st_media_frames_total 0" in body
    # route counters rendered with labels
    assert 'st_requests_total{method="GET",path="/health",code="200"} 1' in body


def test_metrics_observers_increment():
    metrics_mod._requests.clear()
    metrics_mod.observe_request("GET", "/health", 200)
    metrics_mod.observe_request("GET", "/health", 200)
    metrics_mod.observe_media(3, 300)
    metrics_mod.observe_floor(True)
    metrics_mod.observe_floor(False)
    out = metrics_mod._render(2, 1)
    assert 'st_requests_total{method="GET",path="/health",code="200"} 2' in out
    assert "st_media_frames_total 3" in out
    assert "st_media_bytes_total 300" in out
    assert "st_floor_grants_total 1" in out
    assert "st_floor_denials_total 1" in out
    assert "st_realtime_devices 2" in out
    assert "st_realtime_users 1" in out
