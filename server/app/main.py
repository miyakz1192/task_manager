import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.db import init_db
from app.discovery import ServerAdvertiser
from app.sync_api import router as sync_router
from app.web.routes import router as web_router

logging.basicConfig(level=logging.INFO)

STATIC_DIR = Path(__file__).resolve().parent / "static"

advertiser = ServerAdvertiser()

# Set by the test fixtures to avoid spinning up real mDNS sockets per test run.
_MDNS_DISABLED = os.environ.get("WORKLOG_DISABLE_MDNS") == "1"


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    if not _MDNS_DISABLED:
        try:
            await advertiser.start()
        except Exception as exc:
            # mDNS registration can fail for reasons that have nothing to
            # do with the app itself (no usable interface yet at boot,
            # the multicast port unavailable, a stale announcement from a
            # previous crash still occupying the name, ...). Discovery is
            # a nice-to-have; the sync API and wizard must come up
            # regardless, so this must never abort startup.
            logging.getLogger(__name__).warning("mDNS advertisement failed to start: %s", exc)
    try:
        yield
    finally:
        if not _MDNS_DISABLED:
            await advertiser.stop()


app = FastAPI(title="work-log-app server", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
app.include_router(sync_router)
app.include_router(web_router)
