from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration. Env vars use the ST_ prefix (e.g. ST_DATABASE_URL)."""

    model_config = SettingsConfigDict(env_prefix="ST_", env_file=".env", extra="ignore")

    app_name: str = "SecureTalk"
    database_url: str = "sqlite+aiosqlite:///./securetalk.db"
    redis_url: str = "redis://localhost:6379/0"
    jwt_secret: str = "dev-secret-change-me"
    jwt_algorithm: str = "HS256"
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
