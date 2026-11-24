"""Async SQLAlchemy engine/session and Redis client factory."""
from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


def make_engine(url: str):
    if "sqlite" in url:
        from sqlalchemy import event
        from sqlalchemy.pool import StaticPool

        kwargs = {"echo": False, "future": True}
        if ":memory:" in url:
            # One shared connection, otherwise every pooled connection would
            # see a different empty in-memory database.
            kwargs["poolclass"] = StaticPool
            kwargs["connect_args"] = {"check_same_thread": False}
        engine = create_async_engine(url, **kwargs)

        @event.listens_for(engine.sync_engine, "connect")
        def _sqlite_pragmas(dbapi_conn, _record):
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA journal_mode=MEMORY")
            cur.execute("PRAGMA synchronous=OFF")
            cur.close()

        return engine
    return create_async_engine(url, echo=False, future=True)


def make_sessionmaker(engine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False)


def make_redis(redis_url: str):
    import redis.asyncio as aioredis

    # RESP2 keeps compatibility with older Redis servers (5.x); Redis 7 supports it too.
    return aioredis.from_url(redis_url, decode_responses=True, protocol=2)
