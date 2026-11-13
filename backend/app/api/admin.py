"""Admin endpoints (role-gated) + audit log."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..deps import get_admin_user, get_db, get_redis, record_audit
from ..models import AuditLog, Channel, Membership, Role, SecurityEvent, User
from ..schemas import AdminRoleIn, UserOut

router = APIRouter(prefix="/admin", tags=["admin"])


@router.get("/users", response_model=list[UserOut])
async def list_users(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
    limit: int = Query(default=100, le=500),
    offset: int = Query(default=0, ge=0),
) -> list[UserOut]:
    rows = await session.scalars(select(User).offset(offset).limit(limit))
    return [
        UserOut(id=u.id, display_name=u.display_name, role=u.role.value, disabled=u.disabled)
        for u in rows
    ]


@router.post("/users/{user_id}/disable", response_model=UserOut)
async def disable_user(
    user_id: str,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
) -> UserOut:
    user = await session.get(User, user_id)
    if user is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user_not_found")
    user.disabled = True
    await session.commit()
    await record_audit(session, admin.id, "user.disable", f"user:{user_id}")
    return UserOut(id=user.id, display_name=user.display_name, role=user.role.value, disabled=user.disabled)


@router.post("/roles", response_model=UserOut)
async def set_role(
    body: AdminRoleIn,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
) -> UserOut:
    user = await session.get(User, body.user_id)
    if user is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user_not_found")
    user.role = Role(body.role)
    await session.commit()
    await record_audit(session, admin.id, "user.role", f"user:{body.user_id}", {"role": body.role})
    return UserOut(id=user.id, display_name=user.display_name, role=user.role.value, disabled=user.disabled)


@router.get("/channels")
async def list_all_channels(
    admin: User = Depends(get_admin_user), session: AsyncSession = Depends(get_db)
) -> list[dict]:
    rows = await session.execute(
        select(Channel, func.count().label("members"))
        .join_from(Channel, Membership)
        .where(Channel.deleted_at.is_(None))
        .group_by(Channel.id)
    )
    return [
        {
            "id": c.id,
            "name": c.name,
            "owner_id": c.owner_id,
            "epoch": c.epoch,
            "member_count": count,
            "created_at": c.created_at.isoformat() if c.created_at else None,
        }
        for c, count in rows
    ]


@router.get("/health")
async def admin_health(
    admin: User = Depends(get_admin_user), redis=Depends(get_redis)
) -> dict:
    redis_ok = True
    try:
        await redis.ping()
    except Exception:
        redis_ok = False
    return {"status": "ok", "redis": redis_ok}


@router.get("/audit")
async def get_audit(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
    limit: int = Query(default=100, le=500),
) -> list[dict]:
    rows = await session.scalars(
        select(AuditLog).order_by(AuditLog.id.desc()).limit(limit)
    )
    return [
        {
            "id": r.id,
            "actor_admin_id": r.actor_admin_id,
            "action": r.action,
            "target": r.target,
            "detail": r.detail,
            "created_at": r.created_at.isoformat() if r.created_at else None,
        }
        for r in rows
    ]


@router.get("/security-events")
async def get_security_events(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
    limit: int = Query(default=100, le=500),
) -> list[dict]:
    rows = await session.scalars(
        select(SecurityEvent).order_by(SecurityEvent.id.desc()).limit(limit)
    )
    return [
        {
            "id": r.id,
            "type": r.type,
            "severity": r.severity,
            "channel_id": r.channel_id,
            "user_id": r.user_id,
            "detail": r.detail,
            "created_at": r.created_at.isoformat() if r.created_at else None,
        }
        for r in rows
    ]
