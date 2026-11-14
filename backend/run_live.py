"""Local dev launcher: sqlite + fakeredis (no real Redis on this machine).

Usage: .venv/Scripts/python.exe -m uvicorn run_live:app --port 8000
Integration-only helper (Agent H); production uses app.main:app.
"""
import os

import fakeredis.aioredis

os.environ.setdefault("ST_DATABASE_URL", "sqlite+aiosqlite:///./live.db")
os.environ.setdefault("AUTO_CREATE_TABLES", "true")

from app.db import make_engine, make_sessionmaker
from app.deps import init_state
from app.main import create_app

engine = make_engine(os.environ["ST_DATABASE_URL"])
redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
init_state(engine, make_sessionmaker(engine), redis)

app = create_app()
