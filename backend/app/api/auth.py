"""Auth endpoints: register, login, refresh (rotation), logout."""
from __future__ import annotations

import hashlib
import hmac
import secrets
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..core.flags import effective_flags
from ..core.ratelimit import check_rate_limit
from ..core.security import (
    TokenError,
    create_access_token,
    create_refresh_token,
    decode_token,
    hash_password,
    verify_password,
)
from ..deps import get_db, get_redis, record_security_event
from ..models import RefreshToken, Role, SignupInvite, User, utcnow
from ..schemas import LoginIn, RefreshIn, RegisterIn, SignupInviteCreateIn, SignupInviteOut, TokenPair

router = APIRouter(prefix="/auth", tags=["auth"])
settings = get_settings()


def _invite_code_hash(code: str) -> str:
    key = (settings.invite_secret or settings.jwt_secret).encode()
    return hmac.new(key, code.encode(), hashlib.sha256).hexdigest()


def _new_invite_code() -> str:
    alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no I/L/O/0/1: unambiguous
    body = "".join(secrets.choice(alphabet) for _ in range(8))
    return f"WT-{body[:4]}-{body[4:]}"


async def _consume_signup_invite(session: AsyncSession, code: str) -> SignupInvite:
    from datetime import UTC, datetime

    invite = await session.scalar(select(SignupInvite).where(SignupInvite.code_hash == _invite_code_hash(code)))
    if invite is None:
        await record_security_event(
            session, "signup.invite_invalid", severity="warning", detail={"code_prefix": code[:3]}
        )
        raise HTTPException(status.HTTP_403_FORBIDDEN, "invalid_invite_code")
    now = datetime.now(UTC)
    if invite.expires_at.replace(tzinfo=UTC) < now:
        raise HTTPException(status.HTTP_410_GONE, "invite_expired")
    if invite.used_by is not None:
        raise HTTPException(status.HTTP_410_GONE, "invite_already_used")
    return invite


def _pair(user: User) -> tuple[str, str, str]:
    access = create_access_token(
        settings.jwt_secret, settings.jwt_algorithm, user.id, user.role.value,
        settings.access_token_minutes,
    )
    refresh, jti = create_refresh_token(
        settings.jwt_secret, settings.jwt_algorithm, user.id, user.role.value,
        settings.refresh_token_days,
    )
    return access, refresh, jti


async def _store_refresh(session: AsyncSession, jti: str, user_id: str) -> None:
    session.add(
        RefreshToken(
            jti=jti,
            user_id=user_id,
            expires_at=datetime.now(UTC) + timedelta(days=settings.refresh_token_days),
        )
    )
    await session.commit()


@router.post("/register", response_model=TokenPair, status_code=201)
async def register(
    body: RegisterIn,
    request: Request,
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> TokenPair:
    if not await check_rate_limit(redis, f"auth:{request.client.host if request.client else "na"}", settings.auth_rate_limit, settings.auth_rate_window_seconds):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "rate_limited")
    flags = await effective_flags(session)
    if flags["maintenance_mode"] or not flags["registration_enabled"]:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "registration_disabled")
    existing = await session.scalar(select(User).where(User.display_name == body.display_name))
    if existing is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "display_name_taken")
    # Bootstrap: first account on an empty system becomes admin (and is exempt
    # from the invite gate so the system can be provisioned at all).
    any_user = await session.scalar(select(User.id).limit(1))
    # Invite gate: every later signup needs a valid single-use code.
    invite: SignupInvite | None = None
    if settings.signup_invite_required and any_user is not None:
        if not body.invite_code:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "invite_required")
        invite = await _consume_signup_invite(session, body.invite_code)
    role = Role.ADMIN if (any_user is None and settings.bootstrap_admin_enabled) else Role.USER
    user = User(
        display_name=body.display_name,
        password_hash=hash_password(body.password) if body.password else None,
        role=role,
    )
    session.add(user)
    if invite is not None:
        await session.flush()  # assign user.id before linking the invite
        invite.used_by = user.id
        invite.used_at = utcnow()
    await session.commit()
    await record_security_event(session, "account.created", user_id=user.id)
    access, refresh, jti = _pair(user)
    await _store_refresh(session, jti, user.id)
    return TokenPair(access_token=access, refresh_token=refresh)


@router.post("/login", response_model=TokenPair)
async def login(
    body: LoginIn,
    request: Request,
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> TokenPair:
    if not await check_rate_limit(redis, f"auth:{request.client.host if request.client else "na"}", settings.auth_rate_limit, settings.auth_rate_window_seconds):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "rate_limited")
    user = await session.scalar(select(User).where(User.display_name == body.display_name))
    if user is None or user.password_hash is None or not verify_password(body.password, user.password_hash):
        await record_security_event(
            session, "auth.login_failed", severity="warning",
            detail={"display_name": body.display_name},
        )
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid_credentials")
    if user.disabled:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "account_disabled")
    access, refresh, jti = _pair(user)
    await _store_refresh(session, jti, user.id)
    return TokenPair(access_token=access, refresh_token=refresh)


@router.post("/refresh", response_model=TokenPair)
async def refresh(body: RefreshIn, session: AsyncSession = Depends(get_db)) -> TokenPair:
    try:
        payload = decode_token(settings.jwt_secret, settings.jwt_algorithm, body.refresh_token, "refresh")
    except TokenError as e:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(e)) from e
    jti = payload["jti"]
    stored = await session.get(RefreshToken, jti)
    if stored is None or stored.revoked:
        await record_security_event(
            session, "auth.refresh_reuse", severity="warning", user_id=payload.get("sub"),
        )
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "refresh_invalid")
    stored.revoked = True  # rotation: single use
    user = await session.get(User, payload["sub"])
    if user is None or user.disabled:
        await session.commit()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "account_unavailable")
    access, new_refresh, new_jti = _pair(user)
    session.add(
        RefreshToken(
            jti=new_jti,
            user_id=user.id,
            expires_at=utcnow() + timedelta(days=settings.refresh_token_days),
        )
    )
    await session.commit()
    return TokenPair(access_token=access, refresh_token=new_refresh)


@router.post("/logout", status_code=204)
async def logout(body: RefreshIn, session: AsyncSession = Depends(get_db)) -> None:
    try:
        payload = decode_token(settings.jwt_secret, settings.jwt_algorithm, body.refresh_token, "refresh")
    except TokenError:
        return
    stored = await session.get(RefreshToken, payload["jti"])
    if stored is not None:
        stored.revoked = True
        await session.commit()
    return
