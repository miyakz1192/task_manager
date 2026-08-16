def test_normalize_collapses_width_and_whitespace():
    from app import merge_service

    assert merge_service.normalize("散歩  する") == "散歩 する"
    assert merge_service.normalize("ｻﾝﾎﾟ") == merge_service.normalize("サンポ")
    assert merge_service.normalize("  散歩\n") == "散歩"


def _push(client, task_id, text, is_deleted=False):
    client.post(
        "/api/v1/sync/push",
        json={
            "records": [
                {
                    "task_id": task_id,
                    "text": text,
                    "created_at": "2026-08-15T09:00:00+00:00",
                    "updated_at": "2026-08-15T09:00:00+00:00",
                    "is_deleted": is_deleted,
                }
            ]
        },
    )


def test_find_exact_duplicate_groups_detects_width_variants(client):
    from app import merge_service

    _push(client, "t1", "散歩")
    _push(client, "t2", "散歩 ")  # trailing whitespace, same normalized text
    _push(client, "t3", "洗濯")

    groups = merge_service.find_exact_duplicate_groups()
    assert len(groups) == 1
    assert groups[0]["total_count"] == 2


def test_find_similar_candidates_respects_threshold(client):
    from app import merge_service

    _push(client, "t1", "散歩する")
    _push(client, "t2", "散歩をする")
    _push(client, "t3", "全く違う作業内容です")

    high = merge_service.find_similar_candidates(threshold=50)
    pairs = {(c["text_a"], c["text_b"]) for c in high}
    assert ("散歩する", "散歩をする") in pairs or ("散歩をする", "散歩する") in pairs

    strict = merge_service.find_similar_candidates(threshold=99)
    assert all(c["score"] >= 99 for c in strict)


def test_merge_texts_rewrites_records_and_records_history(client):
    from app import merge_service

    _push(client, "t1", "さんぽ")
    _push(client, "t2", "サンポ")

    affected = merge_service.merge_texts(["さんぽ", "サンポ"], "散歩")
    assert affected == 2

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0}).json()
    texts = {r["task_id"]: r["text"] for r in pull["records"]}
    assert texts == {"t1": "散歩", "t2": "散歩"}

    history = merge_service.list_merge_history()
    assert len(history) == 1
    assert history[0]["to_text"] == "散歩"
    assert set(history[0]["affected_task_ids"]) == {"t1", "t2"}


def test_merge_texts_ignores_already_deleted_records(client):
    from app import merge_service

    _push(client, "t1", "さんぽ", is_deleted=True)

    affected = merge_service.merge_texts(["さんぽ"], "散歩")
    assert affected == 0
