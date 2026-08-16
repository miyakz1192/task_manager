from datetime import datetime, timezone

from fastapi import APIRouter

from app.db import get_connection, next_change_seq
from app.models import PullResponse, PushRequest, PushResponse, ServerRecord

router = APIRouter(prefix="/api/v1/sync", tags=["sync"])


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@router.post("/push", response_model=PushResponse)
def push(payload: PushRequest) -> PushResponse:
    accepted: list[str] = []

    with get_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        try:
            for record in payload.records:
                existing = conn.execute(
                    "SELECT is_deleted FROM records WHERE task_id = ?",
                    (record.task_id,),
                ).fetchone()

                if existing is None:
                    seq = next_change_seq(conn)
                    conn.execute(
                        """
                        INSERT INTO records
                            (task_id, text, created_at, updated_at, is_deleted, change_seq)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (
                            record.task_id,
                            record.text,
                            record.created_at,
                            record.updated_at,
                            int(record.is_deleted),
                            seq,
                        ),
                    )
                    accepted.append(record.task_id)
                elif record.is_deleted and not existing["is_deleted"]:
                    seq = next_change_seq(conn)
                    conn.execute(
                        """
                        UPDATE records
                        SET is_deleted = 1, updated_at = ?, change_seq = ?
                        WHERE task_id = ?
                        """,
                        (_now_iso(), seq, record.task_id),
                    )
                    accepted.append(record.task_id)
                else:
                    # Already synced, or an invalid/no-op request (e.g. trying
                    # to resurrect a deleted record, or re-pushing text edits
                    # which clients are never allowed to make). Silently ignored.
                    accepted.append(record.task_id)

            conn.commit()
        except Exception:
            conn.rollback()
            raise

    return PushResponse(accepted=accepted)


@router.get("/pull", response_model=PullResponse)
def pull(since_change_seq: int = 0) -> PullResponse:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT task_id, text, created_at, updated_at, is_deleted, change_seq
            FROM records
            WHERE change_seq > ?
            ORDER BY change_seq
            """,
            (since_change_seq,),
        ).fetchall()

        records = [
            ServerRecord(
                task_id=row["task_id"],
                text=row["text"],
                created_at=row["created_at"],
                updated_at=row["updated_at"],
                is_deleted=bool(row["is_deleted"]),
                change_seq=row["change_seq"],
            )
            for row in rows
        ]

        max_change_seq = records[-1].change_seq if records else since_change_seq

    return PullResponse(records=records, max_change_seq=max_change_seq)
