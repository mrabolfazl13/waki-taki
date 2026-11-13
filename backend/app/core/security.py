"""JWT issuance/verification and password hashing (passlib[bcrypt])."""
import uuid
from datetime import datetime, timedelta, timezone

import jwt
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

ACCESS = "access"
REFRESH = "refresh"


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return pwd_context.verify(password, password_hash)
    except Exception:
        return False


def create_token(secret: str, algorithm: str, user_id: str, role: str, kind: str, ttl: timedelta) -> tuple[str, str]:
    """Returns (token, jti)."""
    jti = uuid.uuid4().hex
    now = datetime.now(timezone.utc)
    payload = {
        "sub": user_id,
        "role": role,
        "typ": kind,
        "jti": jti,
        "iat": now,
        "exp": now + ttl,
    }
    return jwt.encode(payload, secret, algorithm=algorithm), jti


def create_access_token(secret: str, algorithm: str, user_id: str, role: str, minutes: int = 15) -> str:
    token, _ = create_token(secret, algorithm, user_id, role, ACCESS, timedelta(minutes=minutes))
    return token


def create_refresh_token(secret: str, algorithm: str, user_id: str, role: str, days: int = 30) -> tuple[str, str]:
    return create_token(secret, algorithm, user_id, role, REFRESH, timedelta(days=days))


class TokenError(Exception):
    pass


def decode_token(secret: str, algorithm: str, token: str, expected_type: str) -> dict:
    try:
        payload = jwt.decode(token, secret, algorithms=[algorithm])
    except jwt.ExpiredSignatureError as e:
        raise TokenError("token_expired") from e
    except jwt.InvalidTokenError as e:
        raise TokenError("token_invalid") from e
    if payload.get("typ") != expected_type:
        raise TokenError("token_invalid")
    return payload
