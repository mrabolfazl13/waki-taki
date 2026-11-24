"""Device registration (public keys only) and key discovery."""
from __future__ import annotations

import base64

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.config import get_settings
from ..deps import get_current_user, get_db, get_redis, record_security_event
from ..models import Device, OneTimePrekey, User
from ..schemas import DeviceIn, DeviceOut

router = APIRouter(prefix="/devices", tags=["devices"])
settings = get_settings()


@router.post("", status_code=201)
async def register_device(
    body: DeviceIn,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> dict:
    active_devices = await session.scalar(
        select(func.count(Device.id)).where(Device.user_id == user.id, Device.revoked_at.is_(None))
    )
    if (active_devices or 0) >= settings.max_devices_per_user:
        raise HTTPException(status.HTTP_409_CONFLICT, "device_limit_reached")
    # Prekeys are opaque public values but must be base64 and bounded, otherwise
    # a client could store arbitrarily large blobs.
    decoded_prekeys: list[str] = []
    for idx, pubkey in enumerate(body.one_time_prekeys):
        try:
            raw = base64.b64decode(pubkey, validate=True)
        except Exception as e:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_CONTENT, f"one_time_prekey_not_base64:{idx}"
            ) from e
        if not (16 <= len(raw) <= 512):
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_CONTENT, f"one_time_prekey_bad_length:{idx}"
            )
        decoded_prekeys.append(pubkey)
    device = Device(
        user_id=user.id,
        identity_pubkey=body.identity_pubkey,
        signing_pubkey=body.signing_pubkey,
        signed_prekey=body.signed_prekey,
        name=body.name,
    )
    session.add(device)
    await session.flush()
    for idx, pubkey in enumerate(decoded_prekeys):
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
