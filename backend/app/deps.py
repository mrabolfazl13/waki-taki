"""FastAPI dependencies: DB session, auth, admin gating, redis, event recording."""
from __future__ import annotations

import logging
from typing import Any, AsyncIterator

from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .core.config import get_settings
from .core.security import TokenError, decode_token
from .db import make_redis, make_sessionmaker
from .models import Membership, Role, User, SecurityEvent, AuditLog

log = logging.getLogger("securetalk.deps")

_settings = get_settings()
_engine = None
_session_factory = None
_redis = None


def init_state(engine, session_factory, redis) -> None:
    """Called from app lifespan (and tests) to wire concrete instances."""
    global _engine, _session_factory, _redis
    _engine = engine
    _session_factory = session_factory
    _redis = redis


async def get_db() -> AsyncIterator[AsyncSession]:
    if _session_factory is None:  # pragma: no cover
        raise RuntimeError("app state not initialised")
    async with _session_factory() as session:
        yield session


def get_redis() -> Any:
    if _redis is None:  # pragma: no cover
        raise RuntimeError("app state not initialised")
    return _redis


def _bearer(authorization: str | None, token_q: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    if token_q:
        return token_q
    raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing credentials")


async def get_current_user(
    request: Request,
    session: AsyncSession = Depends(get_db),
    authorization: str | None = Header(default=None),
    token: str | None = None,
) -> User:
    raw = _bearer(authorization, request.query_params.get("token"))
    try:
        payload = decode_token(
            _settings.jwt_secret, _settings.jwt_algorithm, raw, "access"
        )
    except TokenError as e:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(e)) from e
    user = await session.get(User, payload["sub"])
    if user is None or user.disabled:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "account_unavailable")
    return user


async def get_admin_user(user: User = Depends(get_current_user)) -> User:
    if user.role != Role.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "admin_required")
    return user


async def get_membership(
    channel_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> Membership:
    membership = await session.scalar(
        select(Membership).where(
            Membership.channel_id == channel_id, Membership.user_id == user.id
        )
    )
    if membership is None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "not_a_member")
    return membership


async def require_channel_owner(
    channel_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> User:
    from .models import Channel

    channel = await session.get(Channel, channel_id)
    if channel is None or channel.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "channel_not_found")
    if channel.owner_id != user.id and user.role != Role.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "owner_required")
    return user


async def record_security_event(
    session: AsyncSession,
    type: str,
    severity: str = "info",
    channel_id: str | None = None,
    user_id: str | None = None,
    detail: dict | None = None,
) -> None:
    session.add(
        SecurityEvent(
            type=type,
            severity=severity,
            channel_id=channel_id,
            user_id=user_id,
            detail=detail or {},
        )
    )
    await session.commit()


async def record_audit(
    session: AsyncSession, actor_admin_id: str, action: str, target: str, detail: dict | None = None
) -> None:
    session.add(
        AuditLog(actor_admin_id=actor_admin_id, action=action, target=target, detail=detail or {})
    )
    await session.commit()
