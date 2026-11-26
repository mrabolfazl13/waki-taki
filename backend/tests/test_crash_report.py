"""Crash report ingest endpoint."""
from __future__ import annotations

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_crash_report_accepted_and_listed(client: AsyncClient):
    r = await client.post(
        "/api/v1/crash-reports",
        json={"platform": "android", "version": "0.1.0", "stack": "java.lang.RuntimeException: boom\n at a.b.c"},
    )
    assert r.status_code == 202 and r.json()["ok"] is True
    # needs admin to read it back
    admin = await client.post("/api/v1/auth/register", json={"display_name": "cadmin", "password": "password123"})
    h = {"Authorization": f"Bearer {admin.json()['access_token']}"}
    r = await client.get("/api/v1/admin/security-events", headers=h)
    assert r.status_code == 200
    evs = [e for e in r.json() if e["type"] == "app.crash"]
    assert evs and evs[0]["severity"] == "critical"


@pytest.mark.asyncio
async def test_crash_report_validation(client: AsyncClient):
    r = await client.post("/api/v1/crash-reports", json={"platform": "", "stack": "x"})
    assert r.status_code == 422
