"""Operational feature flags persisted in the ``settings`` table.

These are the toggles exposed by GET/PUT /api/v1/admin/settings.  Keeping the
accessor here (rather than in the admin router) lets other routers enforce the
flags without creating a circular import.
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Setting

DEFAULTS: dict[str, bool] = {
    "registration_enabled": True,
    "maintenance_mode": False,
    "invite_creation_enabled": True,
}


async def effective_flags(session: AsyncSession) -> dict[str, bool]:
    """Return defaults merged with persisted admin overrides."""
    values = dict(DEFAULTS)
    rows = await session.scalars(select(Setting).where(Setting.key.in_(DEFAULTS)))
    for row in rows:
        if isinstance(row.value, dict) and "value" in row.value:
            values[row.key] = bool(row.value["value"])
    return values


async def flag_enabled(session: AsyncSession, key: str) -> bool:
    return (await effective_flags(session))[key]
