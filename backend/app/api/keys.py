"""Sender-key envelope distribution (ephemeral), epoch rotation, member removal."""
from __future__ import annotations

import json

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..deps import (
    get_current_user,
    get_db,
    get_membership,
    get_redis,
    record_security_event,
)
from ..models import Channel, MemberRole, Membership, Revocation, Role, User
from ..schemas import EnvelopeAckIn, KeyDistributeIn, RotateIn

router = APIRouter(prefix="/channels/{channel_id}", tags=["keys"])
settings = get_settings()


async def _channel(session: AsyncSession, channel_id: str) -> Channel:
    channel = await session.get(Channel, channel_id)
    if channel is None or channel.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "channel_not_found")
    return channel


@router.post("/keys/distribute", status_code=201)
async def distribute_envelopes(
    channel_id: str,
    body: KeyDistributeIn,
    user: User = Depends(get_current_user),
    membership: Membership = Depends(get_membership),
    session: AsyncSession = Depends(get_db),
) -> dict:
    channel = await _channel(session, channel_id)
    if body.epoch != channel.epoch:
        raise HTTPException(status.HTTP_409_CONFLICT, "epoch_mismatch")
    import base64

    from ..models import Device, KeyEnvelope

    sender_device = await session.get(Device, body.sender_device_id)
    if sender_device is None or sender_device.user_id != user.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "sender_device_not_owned")
    recipient_ids = [e["recipient_device_id"] for e in body.envelopes]
    recipient_devices = {
        d.id: d
        for d in await session.scalars(select(Device).where(Device.id.in_(recipient_ids)))
    }
    # Recipients must be devices of channel members.
    member_user_ids = {
        row for row in await session.scalars(
            select(Membership.user_id).where(Membership.channel_id == channel_id)
        )
    }
    accepted = []
    for env in body.envelopes:
        dev = recipient_devices.get(env["recipient_device_id"])
        if dev is None or dev.user_id not in member_user_ids:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "recipient_not_channel_member")
        try:
            payload = base64.b64decode(env["envelope_ciphertext"], validate=True)
        except Exception as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "envelope_not_base64") from e
        session.add(
            KeyEnvelope(
                channel_id=channel_id,
                sender_device_id=body.sender_device_id,
                recipient_device_id=dev.id,
                epoch=body.epoch,
                envelope=payload,
            )
        )
        accepted.append(dev.id)
    await session.commit()
    return {"stored": len(accepted), "recipient_device_ids": accepted}


@router.get("/keys/envelopes")
async def fetch_envelopes(
    channel_id: str,
    device_id: str,
    user: User = Depends(get_current_user),
    membership: Membership = Depends(get_membership),
    session: AsyncSession = Depends(get_db),
) -> dict:
    import base64

    from ..models import Device, KeyEnvelope

    device = await session.get(Device, device_id)
    if device is None or device.user_id != user.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "device_not_owned")
    rows = await session.scalars(
        select(KeyEnvelope).where(
            KeyEnvelope.channel_id == channel_id,
            KeyEnvelope.recipient_device_id == device_id,
        )
    )
    envelopes = [
        {
            "envelope_id": r.id,
            "sender_device_id": r.sender_device_id,
            "epoch": r.epoch,
            "envelope_ciphertext": base64.b64encode(r.envelope).decode(),
        }
        for r in rows
    ]
    return {"envelopes": envelopes}


@router.post("/keys/ack", status_code=204)
async def ack_envelopes(
    channel_id: str,
    body: EnvelopeAckIn,
    user: User = Depends(get_current_user),
    membership: Membership = Depends(get_membership),
    session: AsyncSession = Depends(get_db),
) -> None:
    from ..models import Device, KeyEnvelope

    rows = await session.scalars(
        select(KeyEnvelope).where(
            KeyEnvelope.id.in_(body.envelope_ids), KeyEnvelope.channel_id == channel_id
        )
    )
    deleted = 0
    for row in rows:
        device = await session.get(Device, row.recipient_device_id)
        if device is None or device.user_id != user.id:
            continue  # never delete someone else's envelope
        await session.delete(row)
        deleted += 1
    await session.commit()
    if deleted != len(body.envelope_ids):
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"deleted_{deleted}_of_{len(body.envelope_ids)}")


@router.post("/epoch/rotate")
async def rotate_epoch(
    channel_id: str,
    body: RotateIn | None = None,
    user: User = Depends(get_current_user),
    membership: Membership = Depends(get_membership),
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> dict:
    channel = await _channel(session, channel_id)
    allowed = (
        channel.owner_id == user.id
        or user.role == Role.ADMIN
        or membership.role == MemberRole.ADMIN
    )
    if not allowed:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "epoch_rotate_not_allowed")
    new_epoch = body.epoch if (body and body.epoch) else channel.epoch + 1
    if new_epoch < channel.epoch:
        raise HTTPException(status.HTTP_409_CONFLICT, "epoch_regress")
    channel.epoch = new_epoch
    session.add(Revocation(channel_id=channel_id, epoch=new_epoch))
    await session.commit()
    await record_security_event(
        session, "channel.epoch_rotated", severity="warning",
        channel_id=channel_id, user_id=user.id, detail={"epoch": new_epoch},
    )
    await redis.publish(
        f"events:{channel_id}",
        json.dumps({"channel_id": channel_id, "event": "epoch_rotated", "epoch": new_epoch}),
    )
    return {"channel_id": channel_id, "epoch": new_epoch}


@router.post("/members/{user_id}/remove", status_code=204)
async def remove_member(
    channel_id: str,
    user_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> None:
    channel = await _channel(session, channel_id)
    if channel.owner_id != user.id and user.role != Role.ADMIN:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "owner_required")
    membership = await session.scalar(
        select(Membership).where(
            Membership.channel_id == channel_id, Membership.user_id == user_id
        )
    )
    if membership is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "member_not_found")
    await session.delete(membership)
    # Membership change -> epoch rotation + revocation record for removed member.
    channel.epoch += 1
    session.add(Revocation(channel_id=channel_id, epoch=channel.epoch))
    await session.commit()
    await record_security_event(
        session, "channel.member_removed", severity="warning",
        channel_id=channel_id, user_id=user_id, detail={"actor": user.id},
    )
    await redis.publish(
        f"events:{channel_id}",
        json.dumps({"channel_id": channel_id, "event": "member_removed",
                    "user_id": user_id, "epoch": channel.epoch}),
    )
