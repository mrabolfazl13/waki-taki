"""JSON structured logging with secret redaction (no structlog dependency needed)."""
import json
import logging
import re

# Keys whose values must never appear in logs.
_SECRET_KEY_RE = re.compile(
    r"(password|passwd|secret|token|authorization|cookie|private[_-]?key|"
    r"envelope|prekey|pubkey|key_material|sdp|salt|media[_-]?key)",
    re.IGNORECASE,
)

_REDACTED = "[REDACTED]"


def redact_value(value):
    if isinstance(value, dict):
        return {k: (_REDACTED if _SECRET_KEY_RE.search(str(k)) else redact_value(v)) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact_value(v) for v in value]
    return value


def redact_text(text: str) -> str:
    """Redact obvious bearer tokens in free text."""
    return re.sub(r"(?i)(bearer\s+|token=)[A-Za-z0-9._\-]+", r"\1" + _REDACTED, text)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": redact_text(record.getMessage()),
        }
        extra = getattr(record, "extra_data", None)
        if extra:
            payload["data"] = redact_value(extra)
        if record.exc_info:
            payload["exception"] = redact_text(self.formatException(record.exc_info))
        return json.dumps(payload, default=str)


class RedactFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if _SECRET_KEY_RE.search(msg) and ("=" in msg or ":" in msg):
            record.msg = redact_text(msg)
            record.args = None
        return True


def setup_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    root.handlers.clear()
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    handler.addFilter(RedactFilter())
    root.addHandler(handler)
    root.setLevel(level.upper())
    for noisy in ("uvicorn.access", "uvicorn.error", "sqlalchemy.engine"):
        logging.getLogger(noisy).handlers.clear()
        logging.getLogger(noisy).propagate = True


def log(logger: logging.Logger, level: int, message: str, **data) -> None:
    logger.log(level, message, extra={"extra_data": data})
