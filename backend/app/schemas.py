"""Pydantic request/response schemas."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator


class RegisterIn(BaseModel):
    display_name: str = Field(min_length=1, max_length=64)
    password: str | None = Field(default=None, min_length=8, max_length=128)

    @field_validator("display_name")
    @classmethod
    def strip(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("display_name must not be blank")
        return v


class LoginIn(BaseModel):
    display_name: str
    password: str


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


class RefreshIn(BaseModel):
    refresh_token: str


class DeviceIn(BaseModel):
    identity_pubkey: str = Field(min_length=16, max_length=512)
    signing_pubkey: str = Field(min_length=16, max_length=512)
    signed_prekey: str = Field(min_length=16, max_length=512)
    name: str | None = Field(default=None, max_length=64)
    one_time_prekeys: list[str] = Field(default_factory=list, max_length=100)


class DeviceOut(BaseModel):
    id: str
    name: str | None
    registered_at: str | None = None


class ChannelIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)

    @field_validator("name")
    @classmethod
    def strip(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("name must not be blank")
        return v


class ChannelOut(BaseModel):
    id: str
    name: str
    epoch: int
    member_count: int = 0
    is_owner: bool = False


class JoinIn(BaseModel):
    invite_code: str = Field(min_length=8, max_length=128)


class InviteOut(BaseModel):
    invite_code: str
    qr_payload: str


class InviteValidateIn(BaseModel):
    invite_code: str = Field(min_length=8, max_length=128)


class InviteValidateOut(BaseModel):
    valid: bool
    channel_id: str | None = None
    channel_name: str | None = None
    reason: str | None = None


class KeyDistributeIn(BaseModel):
    sender_device_id: str = Field(min_length=8, max_length=64)
    epoch: int = Field(ge=1)
    envelopes: list[dict] = Field(min_length=1, max_length=40)

    @field_validator("envelopes")
    @classmethod
    def check_envelopes(cls, v: list[dict]) -> list[dict]:
        for e in v:
            if "recipient_device_id" not in e or "envelope_ciphertext" not in e:
                raise ValueError("each envelope needs recipient_device_id and envelope_ciphertext")
        return v


class EnvelopeAckIn(BaseModel):
    envelope_ids: list[int] = Field(min_length=1, max_length=500)


class RotateIn(BaseModel):
    epoch: int | None = Field(default=None, ge=1)


class AdminRoleIn(BaseModel):
    user_id: str
    role: Literal["user", "admin"]


class UserOut(BaseModel):
    id: str
    display_name: str
    role: str
    disabled: bool
