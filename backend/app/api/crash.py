"""Crash report ingest: a public, rate-limited endpoint that records uncaught
Android/Java exceptions as high-severity security events so the dashboard can
surface field crashes (the app has no third-party crash SDK)."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..core.ratelimit import check_rate_limit
from ..deps import get_db, get_redis, record_security_event

router = APIRouter(tags=["ops"])
settings = get_settings()


class CrashIn(BaseModel):
    platform: str = Field(min_length=1, max_length=32)  # e.g. "android"
    version: str | None = Field(default=None, max_length=32)
    stack: str = Field(min_length=1, max_length=8000)


@router.post("/crash-reports", status_code=202)
async def submit_crash_report(
    body: CrashIn, request: Request, session: AsyncSession = Depends(get_db), redis=Depends(get_redis)
) -> dict:
    client = request.client.host if request.client else "unknown"
    if not await check_rate_limit(redis, f"crash:{client}", 20, 3600):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "rate_limited")
    head = body.stack[:2000]
    await record_security_event(
        session,
        "app.crash",
        severity="critical",
        detail={
            "platform": body.platform,
            "version": body.version,
            "head": head,
        },
    )
    return {"ok": True}
