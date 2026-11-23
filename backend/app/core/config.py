from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration.

    Environment variables are read with several accepted names so that CI
    (DATABASE_URL/REDIS_URL/SECRET_KEY), deployment (.env.example) and the
    legacy ST_-prefixed style all work. Plain names take precedence.
    """

    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    app_name: str = "SecureTalk"
    database_url: str = Field(
        default="sqlite+aiosqlite:///./securetalk.db",
        # ST_-prefixed names take precedence (tests use them for isolation);
        # plain names (CI/deploy) apply when no ST_ override is set.
        validation_alias=AliasChoices("ST_DATABASE_URL", "DATABASE_URL"),
    )
    redis_url: str = Field(
        default="redis://localhost:6379/0",
        validation_alias=AliasChoices("ST_REDIS_URL", "REDIS_URL"),
    )
    jwt_secret: str = Field(
        default="dev-secret-change-me",
        validation_alias=AliasChoices("ST_JWT_SECRET", "JWT_SECRET", "SECRET_KEY"),
    )
    jwt_algorithm: str = "HS256"

    # Deployment environment. Production refuses to start with placeholder
    # secrets / insecure developer conveniences (see main._assert_prod_settings).
    environment: str = Field(
        default="dev", validation_alias=AliasChoices("ST_ENVIRONMENT", "ENVIRONMENT")
    )

    # HMAC key for invite-code hashing. Must be shared by every API instance;
    # falls back to jwt_secret so a single shared secret is enough by default.
    invite_secret: str | None = Field(
        default=None, validation_alias=AliasChoices("ST_INVITE_SECRET", "INVITE_SECRET")
    )

    # Comma-separated browser origins allowed by CORS (empty = same-origin only).
    allowed_origins: str = Field(
        default="", validation_alias=AliasChoices("ST_ALLOWED_ORIGINS", "ALLOWED_ORIGINS")
    )
    access_token_minutes: int = 15
    refresh_token_days: int = 30

    # Create tables on startup (dev/test convenience; production uses Alembic).
    auto_create_tables: bool = True

    # Floor control (ARCHITECTURE.md §4)
    floor_lease_seconds: int = 60
    floor_request_timeout_seconds: int = 3
    floor_rate_limit: int = 10
    floor_rate_window_seconds: int = 10
    floor_queue_depth: int = 20

    max_channel_members: int = 20
    invite_ttl_hours: int = 72
    invite_max_uses: int = 20
    envelope_ttl_hours: int = 24
    media_replay_window: int = 128

    # Bootstrap: the first account created on an empty system becomes admin.
    bootstrap_admin_enabled: bool = True

    # Rate limits
    auth_rate_limit: int = 20
    auth_rate_window_seconds: int = 60
    invite_join_rate_limit: int = 30
    invite_create_rate_limit: int = 10

    log_level: str = "INFO"


def get_settings() -> Settings:
    return Settings()
