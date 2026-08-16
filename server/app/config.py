import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# SQLite DB file location. Overridable via env var for tests.
DB_PATH = Path(os.environ.get("WORKLOG_DB_PATH", BASE_DIR / "data" / "worklog.db"))

# TCP port the server listens on. See ~/tcp_ports_list.txt for the
# registry of ports already used by other projects on this host.
PORT = int(os.environ.get("WORKLOG_PORT", "8090"))
HOST = os.environ.get("WORKLOG_HOST", "0.0.0.0")

# mDNS/zeroconf service type used for LAN discovery by the Android client.
ZEROCONF_SERVICE_TYPE = "_worklogapp._tcp.local."
ZEROCONF_SERVICE_NAME = "work-log-app._worklogapp._tcp.local."

# rapidfuzz similarity score (0-100) threshold above which two task
# texts are surfaced as merge candidates. Tune against real vocabulary.
FUZZY_MATCH_THRESHOLD = int(os.environ.get("WORKLOG_FUZZY_THRESHOLD", "80"))
