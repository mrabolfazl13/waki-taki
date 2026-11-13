"""Health endpoints (no sensitive info)."""
from __future__ import annotations

from fastapi import APIRouter, Depends

from ..deps import get_admin_user, get_redis
from ..models import User

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@router.get("/health/details")
async def health_details(admin: User = Depends(get_admin_user), redis=Depends(get_redis)) -> dict:
    redis_ok = True
    try:
        await redis.ping()
    except Exception:
        redis_ok = False
    return {"status": "ok", "components": {"redis": "ok" if redis_ok else "down"}}
