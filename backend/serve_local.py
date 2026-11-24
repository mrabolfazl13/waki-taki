"""Local server launcher for Windows (and any platform) that needs a selector loop.

Why this exists: psycopg's async mode refuses to run on Windows' default
ProactorEventLoop, and `uvicorn app.main:app` on Windows picks Proactor even
when the event-loop policy is set beforehand. This launcher creates a
SelectorEventLoop explicitly and runs the real app on it.

Usage (from `backend/`):
    set DATABASE_URL=postgresql+psycopg://user:pass@127.0.0.1:5432/securetalk
    set ST_REDIS_URL=redis://127.0.0.1:6379/0
    python serve_local.py --port 8000

On Linux/macOS the regular `uvicorn app.main:app` command is fine.
"""
from __future__ import annotations

import argparse
import asyncio
import sys

import uvicorn


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--app", default="app.main:app")
    args = parser.parse_args()

    loop = asyncio.SelectorEventLoop()
    asyncio.set_event_loop(loop)
    config = uvicorn.Config(args.app, host=args.host, port=args.port, log_level="info")
    server = uvicorn.Server(config)
    try:
        loop.run_until_complete(server.serve())
    except KeyboardInterrupt:
        pass
    finally:
        loop.close()


if __name__ == "__main__":
    if sys.platform != "win32":
        print("note: serve_local.py is mainly for Windows; uvicorn works directly elsewhere")
    main()
