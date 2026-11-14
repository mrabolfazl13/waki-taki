"""admin settings table

Revision ID: 0002_admin_settings
Revises: 0001_full_schema
Create Date: 2026-11-14
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.types import JSON

revision = "0002_admin_settings"
down_revision = "0001_full_schema"
branch_labels = None
depends_on = None


def _json_type() -> sa.types.TypeEngine:
    return JSON().with_variant(JSONB(), "postgresql")


def upgrade() -> None:
    op.create_table(
        "settings",
        sa.Column("key", sa.String(64), primary_key=True),
        sa.Column("value", _json_type(), nullable=False),
        sa.Column("updated_by", sa.String(32), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("settings")
