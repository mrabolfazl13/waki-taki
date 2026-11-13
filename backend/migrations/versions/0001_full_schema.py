"""full schema

Revision ID: 0001_full_schema
Revises:
Create Date: 2026-11-14
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0001_full_schema"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("display_name", sa.String(64), nullable=False),
        sa.Column("password_hash", sa.String(255), nullable=True),
        sa.Column("role", sa.Enum("user", "admin", name="user_role"), nullable=False),
        sa.Column("disabled", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "devices",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("user_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("identity_pubkey", sa.Text, nullable=False),
        sa.Column("signing_pubkey", sa.Text, nullable=False),
        sa.Column("signed_prekey", sa.Text, nullable=False),
        sa.Column("name", sa.String(64), nullable=True),
        sa.Column("registered_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_devices_user_id", "devices", ["user_id"])
    op.create_table(
        "one_time_prekeys",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("device_id", sa.String(32), sa.ForeignKey("devices.id", ondelete="CASCADE"), nullable=False),
        sa.Column("key_id", sa.Integer, nullable=False),
        sa.Column("pubkey", sa.Text, nullable=False),
        sa.Column("used", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.UniqueConstraint("device_id", "key_id", name="uq_otpk_device_key"),
    )
    op.create_table(
        "channels",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("name", sa.String(64), nullable=False),
        sa.Column("owner_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("epoch", sa.BigInteger, nullable=False, server_default="1"),
    )
    op.create_index("ix_channels_owner_id", "channels", ["owner_id"])
    op.create_table(
        "memberships",
        sa.Column("channel_id", sa.String(32), sa.ForeignKey("channels.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("user_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("role", sa.Enum("member", "admin", name="member_role"), nullable=False),
        sa.Column("joined_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_memberships_user_id", "memberships", ["user_id"])
    op.create_table(
        "invites",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("channel_id", sa.String(32), sa.ForeignKey("channels.id", ondelete="CASCADE"), nullable=False),
        sa.Column("code_hash", sa.String(128), nullable=False),
        sa.Column("created_by", sa.String(32), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("max_uses", sa.Integer, nullable=False, server_default="1"),
    )
    op.create_index("ix_invites_channel_id", "invites", ["channel_id"])
    op.create_index("ix_invites_code_hash", "invites", ["code_hash"])
    op.create_table(
        "key_envelopes",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("channel_id", sa.String(32), sa.ForeignKey("channels.id", ondelete="CASCADE"), nullable=False),
        sa.Column("sender_device_id", sa.String(32), nullable=False),
        sa.Column("recipient_device_id", sa.String(32), nullable=False),
        sa.Column("epoch", sa.BigInteger, nullable=False),
        sa.Column("envelope", sa.LargeBinary, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_key_envelopes_channel_id", "key_envelopes", ["channel_id"])
    op.create_index("ix_key_envelopes_recipient_device_id", "key_envelopes", ["recipient_device_id"])
    op.create_table(
        "security_events",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("type", sa.String(64), nullable=False),
        sa.Column("severity", sa.String(16), nullable=False, server_default="info"),
        sa.Column("channel_id", sa.String(32), nullable=True),
        sa.Column("user_id", sa.String(32), nullable=True),
        sa.Column("detail", sa.JSON, nullable=False, server_default="{}"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "audit_log",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("actor_admin_id", sa.String(32), nullable=False),
        sa.Column("action", sa.String(64), nullable=False),
        sa.Column("target", sa.String(128), nullable=False),
        sa.Column("detail", sa.JSON, nullable=False, server_default="{}"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "revocations",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("device_id", sa.String(32), nullable=True),
        sa.Column("channel_id", sa.String(32), nullable=True),
        sa.Column("epoch", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "refresh_tokens",
        sa.Column("jti", sa.String(64), primary_key=True),
        sa.Column("user_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked", sa.Boolean, nullable=False, server_default=sa.false()),
    )
    op.create_index("ix_refresh_tokens_user_id", "refresh_tokens", ["user_id"])


def downgrade() -> None:
    for t in (
        "refresh_tokens", "revocations", "audit_log", "security_events",
        "key_envelopes", "invites", "memberships", "channels",
        "one_time_prekeys", "devices", "users",
    ):
        op.drop_table(t)
    sa.Enum(name="user_role").drop(op.get_bind(), checkfirst=True)
    sa.Enum(name="member_role").drop(op.get_bind(), checkfirst=True)
