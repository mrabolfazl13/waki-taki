"""Async SQLAlchemy engine/session and Redis client factory."""
from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from .core.config import get_settings


class Base(DeclarativeBase):
    pass


def make_engine(url: str):
    return create_async_engine(url, echo=False, future=True)


def make_sessionmaker(engine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False)


def make_redis(redis_url: str):
    import redis.asyncio as aioredis

    return aioredis.from_url(redis_url, decode_responses=True)
