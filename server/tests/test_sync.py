def _record(task_id, text, is_deleted=False, ts="2026-08-15T09:00:00+00:00"):
    return {
        "task_id": task_id,
        "text": text,
        "created_at": ts,
        "updated_at": ts,
        "is_deleted": is_deleted,
    }


def test_push_new_record_is_inserted_and_accepted(client):
    resp = client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩")]})
    assert resp.status_code == 200
    assert resp.json()["accepted"] == ["t1"]

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0})
    body = pull.json()
    assert len(body["records"]) == 1
    assert body["records"][0]["task_id"] == "t1"
    assert body["records"][0]["text"] == "散歩"
    assert body["max_change_seq"] == 1


def test_push_is_idempotent_on_retry(client):
    rec = _record("t1", "散歩")
    client.post("/api/v1/sync/push", json={"records": [rec]})
    resp2 = client.post("/api/v1/sync/push", json={"records": [rec]})
    assert resp2.json()["accepted"] == ["t1"]

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0})
    # retry must not create a duplicate record or bump change_seq again
    assert len(pull.json()["records"]) == 1
    assert pull.json()["max_change_seq"] == 1


def test_push_delete_marks_soft_deleted_and_bumps_change_seq(client):
    client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩")]})
    resp = client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩", is_deleted=True)]})
    assert resp.json()["accepted"] == ["t1"]

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0})
    rec = pull.json()["records"][0]
    assert rec["is_deleted"] is True
    assert pull.json()["max_change_seq"] == 2


def test_push_cannot_resurrect_or_edit_text(client):
    client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩", is_deleted=True)]})
    seq_after_delete = client.get("/api/v1/sync/pull", params={"since_change_seq": 0}).json()["max_change_seq"]

    # Client re-pushes the same task_id as not-deleted (e.g. stale local
    # state) — server must never flip is_deleted back to false.
    client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩", is_deleted=False)]})

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0})
    assert pull.json()["records"][0]["is_deleted"] is True
    assert pull.json()["max_change_seq"] == seq_after_delete


def test_pull_since_change_seq_only_returns_newer_changes(client):
    client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩")]})
    first_pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0}).json()
    since = first_pull["max_change_seq"]

    client.post("/api/v1/sync/push", json={"records": [_record("t2", "洗濯")]})

    second_pull = client.get("/api/v1/sync/pull", params={"since_change_seq": since}).json()
    assert [r["task_id"] for r in second_pull["records"]] == ["t2"]


def test_pull_from_zero_restores_full_history_including_deleted(client):
    client.post("/api/v1/sync/push", json={"records": [_record("t1", "散歩")]})
    client.post("/api/v1/sync/push", json={"records": [_record("t2", "洗濯", is_deleted=True)]})

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": 0}).json()
    task_ids = {r["task_id"] for r in pull["records"]}
    assert task_ids == {"t1", "t2"}
    deleted = {r["task_id"] for r in pull["records"] if r["is_deleted"]}
    assert deleted == {"t2"}


def test_merge_change_is_delivered_via_normal_pull(client):
    client.post("/api/v1/sync/push", json={"records": [_record("t1", "さんぽ")]})
    since = client.get("/api/v1/sync/pull", params={"since_change_seq": 0}).json()["max_change_seq"]

    from app import merge_service
    affected = merge_service.merge_texts(["さんぽ"], "散歩")
    assert affected == 1

    pull = client.get("/api/v1/sync/pull", params={"since_change_seq": since}).json()
    assert len(pull["records"]) == 1
    assert pull["records"][0]["text"] == "散歩"
    assert pull["records"][0]["task_id"] == "t1"
