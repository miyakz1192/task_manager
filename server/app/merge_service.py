import json
import re
import unicodedata
from datetime import datetime, timezone

from rapidfuzz import fuzz

from app.config import FUZZY_MATCH_THRESHOLD
from app.db import get_connection, next_change_seq

_WHITESPACE_RE = re.compile(r"\s+")


def normalize(text: str) -> str:
    """NFKC normalize + trim + collapse internal whitespace.

    Used to detect "same wording" candidates (full-width/half-width,
    stray whitespace) that are safe to confirm with a single click.
    """
    normalized = unicodedata.normalize("NFKC", text).strip()
    return _WHITESPACE_RE.sub(" ", normalized)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _active_texts_with_counts(conn) -> list[tuple[str, int]]:
    rows = conn.execute(
        """
        SELECT text, COUNT(*) AS cnt
        FROM records
        WHERE is_deleted = 0
        GROUP BY text
        ORDER BY cnt DESC, text ASC
        """
    ).fetchall()
    return [(row["text"], row["cnt"]) for row in rows]


def list_task_texts(order_by_frequency_asc: bool = False) -> list[dict]:
    """Distinct active task texts with occurrence counts, for the manual
    merge picker and the low-frequency review list."""
    with get_connection() as conn:
        rows = _active_texts_with_counts(conn)
    items = [{"text": text, "count": count} for text, count in rows]
    if order_by_frequency_asc:
        items.sort(key=lambda item: (item["count"], item["text"]))
    return items


def find_exact_duplicate_groups() -> list[dict]:
    """Group distinct texts whose NFKC/trim/whitespace-collapsed form is
    identical but whose raw form differs. Each group is a "same wording"
    cluster the wizard can confirm with one click, not auto-merged."""
    with get_connection() as conn:
        rows = _active_texts_with_counts(conn)

    groups: dict[str, list[dict]] = {}
    for text, count in rows:
        key = normalize(text)
        groups.setdefault(key, []).append({"text": text, "count": count})

    return [
        {
            "normalized": key,
            "variants": variants,
            "total_count": sum(v["count"] for v in variants),
        }
        for key, variants in groups.items()
        if len(variants) > 1
    ]


def find_similar_candidates(threshold: int = FUZZY_MATCH_THRESHOLD) -> list[dict]:
    """Pairs of distinct texts scored by rapidfuzz.fuzz.ratio() on their
    normalized form, at or above `threshold`, sorted by score desc.
    Exact-duplicate pairs (handled separately) are excluded."""
    with get_connection() as conn:
        rows = _active_texts_with_counts(conn)

    entries = [(text, count, normalize(text)) for text, count in rows]
    candidates = []
    for i in range(len(entries)):
        text_a, count_a, norm_a = entries[i]
        for j in range(i + 1, len(entries)):
            text_b, count_b, norm_b = entries[j]
            if norm_a == norm_b:
                continue
            score = fuzz.ratio(norm_a, norm_b)
            if score >= threshold:
                candidates.append(
                    {
                        "text_a": text_a,
                        "count_a": count_a,
                        "text_b": text_b,
                        "count_b": count_b,
                        "score": round(score, 1),
                    }
                )

    candidates.sort(key=lambda c: c["score"], reverse=True)
    return candidates


def merge_texts(source_texts: list[str], target_text: str) -> int:
    """Rewrite every active record whose text is in `source_texts` to
    `target_text`, stamping a single new change_seq, and record the merge
    in merge_history. Returns the number of affected records.

    `target_text` may itself be one of `source_texts` (no-op rows are
    still restamped with a fresh change_seq, which is harmless: Pull is a
    '>' comparison and clients simply re-upsert the same content).
    """
    target_text = target_text.strip()
    if not target_text:
        raise ValueError("target_text must not be empty")

    with get_connection() as conn:
        conn.execute("BEGIN IMMEDIATE")
        try:
            placeholders = ",".join("?" for _ in source_texts)
            affected = conn.execute(
                f"""
                SELECT task_id FROM records
                WHERE is_deleted = 0 AND text IN ({placeholders})
                """,
                source_texts,
            ).fetchall()
            affected_task_ids = [row["task_id"] for row in affected]

            if not affected_task_ids:
                conn.rollback()
                return 0

            seq = next_change_seq(conn)
            now = _now_iso()
            task_placeholders = ",".join("?" for _ in affected_task_ids)
            conn.execute(
                f"""
                UPDATE records
                SET text = ?, updated_at = ?, change_seq = ?
                WHERE task_id IN ({task_placeholders})
                """,
                [target_text, now, seq, *affected_task_ids],
            )

            from_text_label = source_texts[0] if len(source_texts) == 1 else json.dumps(
                source_texts, ensure_ascii=False
            )
            conn.execute(
                """
                INSERT INTO merge_history
                    (merged_at, from_text, to_text, affected_task_ids)
                VALUES (?, ?, ?, ?)
                """,
                (now, from_text_label, target_text, json.dumps(affected_task_ids, ensure_ascii=False)),
            )

            conn.commit()
            return len(affected_task_ids)
        except Exception:
            conn.rollback()
            raise


def list_merge_history() -> list[dict]:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT merged_at, from_text, to_text, affected_task_ids
            FROM merge_history
            ORDER BY id DESC
            """
        ).fetchall()

    history = []
    for row in rows:
        task_ids = json.loads(row["affected_task_ids"])
        history.append(
            {
                "merged_at": row["merged_at"],
                "from_text": row["from_text"],
                "to_text": row["to_text"],
                "affected_task_ids": task_ids,
                "affected_count": len(task_ids),
            }
        )
    return history
