import sqlite3
from contextlib import contextmanager

from app.config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS records (
  db_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id     TEXT UNIQUE NOT NULL,
  text        TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  is_deleted  INTEGER NOT NULL DEFAULT 0,
  change_seq  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_records_change_seq ON records(change_seq);
CREATE INDEX IF NOT EXISTS idx_records_is_deleted ON records(is_deleted);
CREATE INDEX IF NOT EXISTS idx_records_text ON records(text);

CREATE TABLE IF NOT EXISTS merge_history (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  merged_at         TEXT NOT NULL,
  from_text         TEXT NOT NULL,
  to_text           TEXT NOT NULL,
  affected_task_ids TEXT NOT NULL
);
"""


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with get_connection() as conn:
        conn.executescript(SCHEMA)
        conn.commit()


@contextmanager
def get_connection():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    try:
        yield conn
    finally:
        conn.close()


def next_change_seq(conn: sqlite3.Connection) -> int:
    """Allocate the next change_seq value within the caller's transaction.

    Must be called with a write already pending/committed together in the
    same transaction as the caller's INSERT/UPDATE to avoid a race between
    the read here and the write that consumes it.
    """
    row = conn.execute("SELECT COALESCE(MAX(change_seq), 0) + 1 AS next FROM records").fetchone()
    return row["next"]
