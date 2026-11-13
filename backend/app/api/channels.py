"""Channels, membership, invites (20-member cap server-enforced), user key discovery."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..core.ratelimit import check_rate_limit
from ..deps import get_current_user, get_db, get_redis, record_security_event
from ..models import Channel, Invite, Membership, MemberRole, Role, User, utcnow
from ..schemas import (
    ChannelIn,
    ChannelOut,
    InviteOut,
    InviteValidateIn,
    InviteValidateOut,
    JoinIn,
)

router = APIRouter(prefix="/channels", tags=["channels"])
settings = get_settings()

_invite_secret = secrets.token_bytes(32)  # per-process HMAC key for code hashing


def _hash_code(code: str) -> str:
    return hmac.new(_invite_secret, code.encode(), hashlib.sha256).hexdigest()


def _qr_payload(code: str) -> str:
    return base64.urlsafe_b64encode(f"securetalk://join?c={code}".encode()).decode()


async def _member_count(session: AsyncSession, channel_id: str) -> int:
    return int(
        await session.scalar(
            select(func.count()).select_from(Membership).where(Membership.channel_id == channel_id)
        )
    )


@router.post("", response_model=ChannelOut, status_code=201)
async def create_channel(
    body: ChannelIn,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> ChannelOut:
    channel = Channel(name=body.name, owner_id=user.id, epoch=1)
    session.add(channel)
    await session.flush()
    session.add(
        Membership(channel_id=channel.id, user_id=user.id, role=MemberRole.ADMIN)
    )
    await session.commit()
    return ChannelOut(id=channel.id, name=channel.name, epoch=channel.epoch, member_count=1, is_owner=True)


@router.get("", response_model=list[ChannelOut])
async def list_channels(
    user: User = Depends(get_current_user), session: AsyncSession = Depends(get_db)
) -> list[ChannelOut]:
    rows = await session.execute(
        select(Channel, Membership)
        .join(Membership, Membership.channel_id == Channel.id)
        .where(Membership.user_id == user.id, Channel.deleted_at.is_(None))
    )
    out: list[ChannelOut] = []
    for channel, membership in rows:
        out.append(
            ChannelOut(
                id=channel.id,
                name=channel.name,
                epoch=channel.epoch,
                member_count=await _member_count(session, channel.id),
                is_owner=channel.owner_id == user.id,
            )
        )
    return out


@router.delete("/{channel_id}", status_code=204)
async def delete_channel(
    channel_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> None:
    channel = await session.get(Channel, channel_id)
    if channel is None or channel.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "channel_not_found")
    if channel.owner_id != user.id and user.role != Role.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "owner_required")
    channel.deleted_at = utcnow()
    await session.commit()
    await record_security_event(session, "channel.deleted", channel_id=channel_id, user_id=user.id)
    return None


@router.post("/{channel_id}/join", response_model=ChannelOut)
async def join_channel(
    channel_id: str,
    body: JoinIn,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> ChannelOut:
    if not await check_rate_limit(redis, f"invite_join:{user.id}", settings.invite_join_rate_limit, settings.auth_rate_window_seconds):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "rate_limited")
    channel = await session.get(Channel, channel_id)
    if channel is None or channel.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "channel_not_found")
    existing = await session.scalar(
        select(Membership).where(
            Membership.channel_id == channel_id, Membership.user_id == user.id
        )
    )
    if existing is not None:
        count = await _member_count(session, channel_id)
        return ChannelOut(
            id=channel.id, name=channel.name, epoch=channel.epoch,
            member_count=count, is_owner=channel.owner_id == user.id,
        )
    # Hash comparison (timing-safe) against stored code hashes.
    invite = None
    candidates = await session.scalars(
        select(Invite).where(
            Invite.channel_id == channel_id, Invite.expires_at > utcnow()
        )
    )
    for cand in candidates:
        if cand.used_count >= cand.max_uses:
            continue
        if hmac.compare_digest(cand.code_hash, _hash_code(body.invite_code)):
            invite = cand
            break
    if invite is None:
        await record_security_event(
            session, "invite.invalid_use", severity="warning",
            channel_id=channel_id, user_id=user.id,
        )
        raise HTTPException(status.HTTP_403_FORBIDDEN, "invalid_invite")
    count = await _member_count(session, channel_id)
    if count >= settings.max_channel_members:
        raise HTTPException(status.HTTP_409_CONFLICT, "channel_full")
    invite.used_count += 1
    session.add(Membership(channel_id=channel_id, user_id=user.id))
    await session.commit()
    await record_security_event(session, "channel.joined", channel_id=channel_id, user_id=user.id)
    await redis.publish(
        f"events:{channel_id}",
        json.dumps({"channel_id": channel_id, "event": "member_joined", "user_id": user.id}),
    )
    return ChannelOut(
        id=channel.id, name=channel.name, epoch=channel.epoch,
        member_count=count + 1, is_owner=False,
    )


@router.post("/{channel_id}/leave", status_code=204)
async def leave_channel(
    channel_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> None:
    membership = await session.scalar(
        select(Membership).where(Membership.channel_id == channel_id, Membership.user_id == user.id)
    )
    if membership is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not_a_member")
    await session.delete(membership)
    await session.commit()
    await redis.publish(
        f"events:{channel_id}",
        json.dumps({"channel_id": channel_id, "event": "member_left", "user_id": user.id}),
    )
    return None


@router.post("/{channel_id}/invites", response_model=InviteOut)
async def create_invite(
    channel_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> InviteOut:
    membership = await session.scalar(
        select(Membership).where(Membership.channel_id == channel_id, Membership.user_id == user.id)
    )
    channel = await session.get(Channel, channel_id)
    if channel is None or channel.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "channel_not_found")
    if membership is None and user.role != Role.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "not_a_member")
    if not await check_rate_limit(redis, f"invite_create:{user.id}", settings.invite_create_rate_limit, settings.auth_rate_window_seconds):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "rate_limited")
    code = secrets.token_urlsafe(24)
    session.add(
        Invite(
            channel_id=channel_id,
            code_hash=_hash_code(code),
            created_by=user.id,
            expires_at=utcnow() + timedelta(hours=settings.invite_ttl_hours),
            max_uses=max(1, settings.max_channel_members - await _member_count(session, channel_id)),
        )
    )
    await session.commit()
    return InviteOut(invite_code=code, qr_payload=_qr_payload(code))


@router.post("/invites/validate", response_model=InviteValidateOut)
async def validate_invite(
    body: InviteValidateIn,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> InviteValidateOut:
    candidates = await session.scalars(select(Invite).where(Invite.expires_at > utcnow()))
    for cand in candidates:
        if cand.used_count >= cand.max_uses:
            continue
        if hmac.compare_digest(cand.code_hash, _hash_code(body.invite_code)):
            channel = await session.get(Channel, cand.channel_id)
            if channel is None or channel.deleted_at is not None:
                break
            full = await _member_count(session, channel.id) >= settings.max_channel_members
            return InviteValidateOut(
                valid=not full,
                channel_id=channel.id,
                channel_name=channel.name,
                reason="channel_full" if full else None,
            )
    return InviteValidateOut(valid=False, reason="invalid_or_expired")


invites_router = APIRouter(prefix="/invites", tags=["invites"])


@invites_router.post("/validate", response_model=InviteValidateOut)
async def validate_invite_top(
    body: InviteValidateIn,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> InviteValidateOut:
    return await validate_invite(body, user=user, session=session)
