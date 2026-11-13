"""Public key discovery (never private material)."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..deps import get_current_user, get_db
from ..models import Device, OneTimePrekey, User

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/{user_id}/keys")
async def user_keys(
    user_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> dict:
    target = await session.get(User, user_id)
    if target is None or target.disabled:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user_not_found")
    devices = await session.scalars(
        select(Device).where(Device.user_id == user_id, Device.revoked_at.is_(None))
    )
    out = []
    for d in devices:
        otpks = await session.scalars(
            select(OneTimePrekey).where(
                OneTimePrekey.device_id == d.id, OneTimePrekey.used.is_(False)
            )
        )
        out.append(
            {
                "device_id": d.id,
                "identity_pubkey": d.identity_pubkey,
                "signing_pubkey": d.signing_pubkey,
                "signed_prekey": d.signed_prekey,
                "one_time_prekeys": [{"key_id": k.key_id, "pubkey": k.pubkey} for k in otpks],
            }
        )
    return {"user_id": user_id, "devices": out}
