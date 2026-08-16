import sys

import pytest


@pytest.fixture
def client_with_mdns_registration_failure(tmp_path, monkeypatch):
    """Like the `client` fixture, but mDNS is left enabled and
    Zeroconf.register_service() is forced to raise — regression coverage
    for the crash-loop bug where a non-OSError from zeroconf (e.g.
    NonUniqueNameException, raised when a previous crash left a stale
    announcement on the network) used to escape the lifespan handler and
    abort the whole app's startup."""
    db_path = tmp_path / "test.db"
    monkeypatch.setenv("WORKLOG_DB_PATH", str(db_path))
    monkeypatch.delenv("WORKLOG_DISABLE_MDNS", raising=False)

    for mod in list(sys.modules):
        if mod == "app" or mod.startswith("app."):
            del sys.modules[mod]

    from fastapi.testclient import TestClient
    from app.db import init_db
    import app.discovery as discovery_module

    class ExplodingZeroconf:
        def __init__(self, *args, **kwargs):
            pass

        def register_service(self, *args, **kwargs):
            raise RuntimeError("simulated NonUniqueNameException")

        def unregister_service(self, *args, **kwargs):
            pass

        def close(self):
            pass

    monkeypatch.setattr(discovery_module, "Zeroconf", ExplodingZeroconf)

    import app.main as main_module

    init_db()

    with TestClient(main_module.app) as test_client:
        yield test_client


def test_mdns_registration_failure_does_not_prevent_server_startup(
    client_with_mdns_registration_failure,
):
    resp = client_with_mdns_registration_failure.get(
        "/api/v1/sync/pull", params={"since_change_seq": 0}
    )
    assert resp.status_code == 200
    assert resp.json() == {"records": [], "max_change_seq": 0}
