import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture
def client(tmp_path, monkeypatch):
    db_path = tmp_path / "test.db"
    monkeypatch.setenv("WORKLOG_DB_PATH", str(db_path))
    monkeypatch.setenv("WORKLOG_DISABLE_MDNS", "1")

    # app.config reads the env var at import time, so make sure every
    # module that cached the old DB_PATH is reloaded against this one.
    for mod in list(sys.modules):
        if mod == "app" or mod.startswith("app."):
            del sys.modules[mod]

    from fastapi.testclient import TestClient
    from app.main import app
    from app.db import init_db

    init_db()

    with TestClient(app) as test_client:
        yield test_client
