"""single-use signup invite codes

Revision ID: 0003_signup_invites
Revises: 0002_admin_settings
Create Date: 2026-11-26
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003_signup_invites"
down_revision = "0002_admin_settings"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "signup_invites",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("code_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("created_by", sa.String(32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_by", sa.String(32), nullable=True),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("signup_invites")
