"""Admin endpoints (role-gated) + audit log."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..deps import get_admin_user, get_db, get_redis, record_audit
from ..models import AuditLog, Channel, Device, Membership, Role, SecurityEvent, Setting, User
from ..schemas import AdminRoleIn, AdminSettingsIn, AdminSettingsOut, UserOut

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


# ---------- Dashboard endpoints (memberships, devices, config, settings) ----------

# Operational toggles: key -> default value. Only these keys are editable via
# PUT /admin/settings; unknown keys are rejected. Documented in docs/API_NOTES.md.
SETTING_DEFAULTS: dict[str, bool] = {
    "registration_enabled": True,
    "maintenance_mode": False,
    "invite_creation_enabled": True,
}


@router.get("/memberships")
async def list_memberships(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
    limit: int = Query(default=100, le=500),
    offset: int = Query(default=0, ge=0),
) -> list[dict]:
    """Channel memberships with channel + user summary (read-only)."""
    rows = await session.execute(
        select(Membership, Channel.name, User.display_name)
        .join_from(Membership, Channel, Membership.channel_id == Channel.id)
        .join_from(Membership, User, Membership.user_id == User.id)
        .where(Channel.deleted_at.is_(None))
        .offset(offset)
        .limit(limit)
    )
    return [
        {
            "channel_id": m.channel_id,
            "channel_name": channel_name,
            "user_id": m.user_id,
            "user_display_name": display_name,
            "role": m.role.value,
            "joined_at": m.joined_at.isoformat() if m.joined_at else None,
        }
        for m, channel_name, display_name in rows
    ]


@router.get("/devices")
async def list_devices(
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
    limit: int = Query(default=100, le=500),
    offset: int = Query(default=0, ge=0),
) -> list[dict]:
    """Registered devices: public metadata only — never key material."""
    rows = await session.scalars(select(Device).offset(offset).limit(limit))
    return [
        {
            "id": d.id,
            "user_id": d.user_id,
            "name": d.name,
            "registered_at": d.registered_at.isoformat() if d.registered_at else None,
            "revoked_at": d.revoked_at.isoformat() if d.revoked_at else None,
        }
        for d in rows
    ]


@router.get("/config")
async def get_config(admin: User = Depends(get_admin_user)) -> dict:
    """Current non-sensitive operational configuration. Never includes secrets."""
    s = get_settings()
    return {
        "auth_rate_limit": s.auth_rate_limit,
        "auth_rate_window_seconds": s.auth_rate_window_seconds,
        "invite_join_rate_limit": s.invite_join_rate_limit,
        "invite_create_rate_limit": s.invite_create_rate_limit,
        "floor_lease_seconds": s.floor_lease_seconds,
        "floor_request_timeout_seconds": s.floor_request_timeout_seconds,
        "floor_rate_limit": s.floor_rate_limit,
        "floor_rate_window_seconds": s.floor_rate_window_seconds,
        "floor_queue_depth": s.floor_queue_depth,
        "max_channel_members": s.max_channel_members,
        "invite_ttl_hours": s.invite_ttl_hours,
        "invite_max_uses": s.invite_max_uses,
        "envelope_ttl_hours": s.envelope_ttl_hours,
        "media_replay_window": s.media_replay_window,
        "access_token_minutes": s.access_token_minutes,
        "refresh_token_days": s.refresh_token_days,
    }


@router.get("/settings", response_model=AdminSettingsOut)
async def get_settings_endpoint(
    admin: User = Depends(get_admin_user), session: AsyncSession = Depends(get_db)
) -> AdminSettingsOut:
    """Effective operational settings (defaults merged with persisted overrides)."""
    effective = dict(SETTING_DEFAULTS)
    rows = await session.scalars(select(Setting).where(Setting.key.in_(SETTING_DEFAULTS)))
    for row in rows:
        if isinstance(row.value, dict) and "value" in row.value:
            effective[row.key] = bool(row.value["value"])
    return AdminSettingsOut(**effective)


@router.put("/settings", response_model=AdminSettingsOut)
async def put_settings(
    body: AdminSettingsIn,
    admin: User = Depends(get_admin_user),
    session: AsyncSession = Depends(get_db),
) -> AdminSettingsOut:
    """Update operational toggles (admin only). Writes an audit_log entry."""
    changes = body.model_dump(exclude_none=True)
    if not changes:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "no settings provided")
    for key, value in changes.items():
        setting = await session.get(Setting, key)
        if setting is None:
            setting = Setting(key=key, value={"value": value})
            session.add(setting)
        else:
            setting.value = {"value": value}
        setting.updated_by = admin.id
    await session.commit()
    await record_audit(session, admin.id, "settings.update", "settings", {"changes": changes})
    return await get_settings_endpoint(admin, session)
