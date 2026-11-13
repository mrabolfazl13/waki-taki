"""Redis-backed fixed-window rate limiter (works with redis-py async or test stubs)."""
import time

RATELIMIT_KEY = "ratelimit:{bucket}"


async def check_rate_limit(redis, bucket: str, limit: int, window_seconds: int) -> bool:
    """Returns True if allowed. Fixed window counter with TTL."""
    key = RATELIMIT_KEY.format(bucket=bucket)
    window = int(time.time()) // window_seconds
    windowed_key = f"{key}:{window}"
    count = await redis.incr(windowed_key)
    if count == 1:
        await redis.expire(windowed_key, window_seconds + 1)
    return count <= limit
