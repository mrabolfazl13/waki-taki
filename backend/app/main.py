"""FastAPI application entrypoint."""
from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import admin, auth, channels, devices, health, keys, users
from .core.config import get_settings
from .core.logging import setup_logging
from .db import make_engine, make_redis, make_sessionmaker
from .deps import init_state
from .models import Base
from .realtime.hub import Hub

settings = get_settings()
setup_logging(settings.log_level)
log = logging.getLogger("securetalk.main")


def _assert_prod_settings() -> None:
    """Fail closed when a production deployment keeps developer defaults."""
    if settings.environment.lower() not in {"prod", "production"}:
        return
    placeholder_secrets = {
        "dev-secret-change-me",
        "change_me_generate_with_secrets_token_urlsafe",
        "change_me",
        "",
    }
    problems: list[str] = []
    if settings.jwt_secret in placeholder_secrets or len(settings.jwt_secret) < 32:
        problems.append("JWT_SECRET must be a strong random value (>= 32 chars)")
    if settings.bootstrap_admin_enabled:
        problems.append("BOOTSTRAP_ADMIN_ENABLED must be false (provision admins explicitly)")
    if settings.auto_create_tables:
        problems.append("AUTO_CREATE_TABLES must be false (use Alembic migrations)")
    if problems:
        raise RuntimeError("unsafe production configuration: " + "; ".join(problems))


def create_app() -> FastAPI:
    from . import deps as _deps

    # Reuse externally initialised state (tests) when present.
    engine = _deps._engine if _deps._engine is not None else make_engine(settings.database_url)
    session_factory = (
        _deps._session_factory if _deps._session_factory is not None else make_sessionmaker(engine)
    )
    redis = _deps._redis if _deps._redis is not None else make_redis(settings.redis_url)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        _assert_prod_settings()
        if settings.auto_create_tables:
            async with engine.begin() as conn:
                await conn.run_sync(Base.metadata.create_all)
        init_state(engine, session_factory, redis)
        hub = Hub(redis, session_factory)
        await hub.start()
        app.state.hub = hub
        # Envelope retention: key envelopes are ephemeral (ARCHITECTURE §7, 24 h).
        async def _envelope_sweeper() -> None:
            from datetime import timedelta

            from sqlalchemy import delete

            from .models import KeyEnvelope, utcnow

            while True:
                await asyncio.sleep(3600)
                try:
                    async with session_factory() as session:
                        await session.execute(
                            delete(KeyEnvelope).where(
                                KeyEnvelope.created_at
                                < utcnow() - timedelta(hours=settings.envelope_ttl_hours)
                            )
                        )
                        await session.commit()
                except Exception:
                    log.exception("envelope sweep failed")

        sweeper = asyncio.create_task(_envelope_sweeper())
        yield
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        await hub.stop()
        await redis.close()
        await engine.dispose()

    app = FastAPI(title=settings.app_name, lifespan=lifespan)
    origins = [o.strip() for o in settings.allowed_origins.split(",") if o.strip()]
    if origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type"],
        )
    app.include_router(health.router)
    app.include_router(auth.router, prefix="/api/v1")
    app.include_router(devices.router, prefix="/api/v1")
    app.include_router(users.router, prefix="/api/v1")
    app.include_router(channels.router, prefix="/api/v1")
    app.include_router(channels.invites_router, prefix="/api/v1")
    app.include_router(keys.router, prefix="/api/v1")
    app.include_router(admin.router, prefix="/api/v1")

    from .realtime.ws import router as ws_router

    app.include_router(ws_router)
    return app


app = create_app()
