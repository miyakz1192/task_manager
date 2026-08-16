from pydantic import BaseModel


class ClientRecord(BaseModel):
    task_id: str
    text: str
    created_at: str
    updated_at: str
    is_deleted: bool


class PushRequest(BaseModel):
    records: list[ClientRecord]


class PushResponse(BaseModel):
    accepted: list[str]


class ServerRecord(BaseModel):
    task_id: str
    text: str
    created_at: str
    updated_at: str
    is_deleted: bool
    change_seq: int


class PullResponse(BaseModel):
    records: list[ServerRecord]
    max_change_seq: int
