"""Device registration (public keys only) and key discovery."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..deps import get_current_user, get_db, get_redis, record_security_event
from ..models import Device, OneTimePrekey, User
from ..schemas import DeviceIn, DeviceOut

router = APIRouter(prefix="/devices", tags=["devices"])


@router.post("", status_code=201)
async def register_device(
    body: DeviceIn,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> dict:
    device = Device(
        user_id=user.id,
        identity_pubkey=body.identity_pubkey,
        signing_pubkey=body.signing_pubkey,
        signed_prekey=body.signed_prekey,
        name=body.name,
    )
    session.add(device)
    await session.flush()
    for idx, pubkey in enumerate(body.one_time_prekeys):
        session.add(OneTimePrekey(device_id=device.id, key_id=idx, pubkey=pubkey))
    await session.commit()
    await record_security_event(session, "device.registered", user_id=user.id, detail={"device_id": device.id})
    return {"id": device.id}


@router.get("", response_model=list[DeviceOut])
async def list_devices(
    user: User = Depends(get_current_user), session: AsyncSession = Depends(get_db)
) -> list[DeviceOut]:
    rows = await session.scalars(
        select(Device).where(Device.user_id == user.id, Device.revoked_at.is_(None))
    )
    return [
        DeviceOut(
            id=d.id,
            name=d.name,
            registered_at=d.registered_at.isoformat() if d.registered_at else None,
        )
        for d in rows
    ]


@router.delete("/{device_id}", status_code=204)
async def delete_device(
    device_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
    redis=Depends(get_redis),
) -> None:
    device = await session.get(Device, device_id)
    if device is None or device.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device_not_found")
    await session.execute(delete(OneTimePrekey).where(OneTimePrekey.device_id == device.id))
    await session.delete(device)
    await session.commit()
    # Kick any live WebSocket for this device: a revoked device must not keep
    # sending media or holding the floor until it happens to reconnect.
    import json

    from ..models import Membership

    channel_ids = list(
        await session.scalars(select(Membership.channel_id).where(Membership.user_id == user.id))
    )
    for channel_id in channel_ids:
        await redis.publish(
            f"events:{channel_id}",
            json.dumps(
                {"channel_id": channel_id, "event": "device_revoked", "device_id": device_id,
                 "user_id": user.id}
            ),
        )
    await record_security_event(
        session, "device.revoked", severity="warning", user_id=user.id, detail={"device_id": device_id}
    )
