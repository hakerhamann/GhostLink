import sqlite3
from collections.abc import Callable


def ensure_direct_chat(
    db: sqlite3.Connection,
    *,
    current_user_id: int,
    target_user_id: int,
    now_ms: Callable[[], int],
) -> int:
    user_a, user_b = sorted([current_user_id, target_user_id])
    existing = db.execute(
        "SELECT chat_id FROM direct_chats WHERE user_a = ? AND user_b = ?",
        (user_a, user_b),
    ).fetchone()

    if existing:
        return int(existing["chat_id"])

    ts = now_ms()
    chat_cursor = db.execute(
        "INSERT INTO chats(is_direct, title, created_by, created_at, updated_at) VALUES(1, NULL, ?, ?, ?)",
        (current_user_id, ts, ts),
    )
    chat_id = int(chat_cursor.lastrowid)

    db.execute(
        "INSERT INTO direct_chats(user_a, user_b, chat_id) VALUES(?, ?, ?)",
        (user_a, user_b, chat_id),
    )
    db.execute(
        "INSERT INTO chat_members(chat_id, user_id, last_read_at, last_delivered_at, created_at) VALUES(?, ?, 0, 0, ?)",
        (chat_id, current_user_id, ts),
    )
    db.execute(
        "INSERT INTO chat_members(chat_id, user_id, last_read_at, last_delivered_at, created_at) VALUES(?, ?, 0, 0, ?)",
        (chat_id, target_user_id, ts),
    )
    db.commit()
    return chat_id


def create_group_chat_record(
    db: sqlite3.Connection,
    *,
    title: str,
    description: str,
    current_user_id: int,
    member_ids: list[int],
    now_ms: Callable[[], int],
) -> int:
    ts = now_ms()
    chat_cursor = db.execute(
        "INSERT INTO chats(is_direct, title, description, created_by, created_at, updated_at) VALUES(0, ?, ?, ?, ?, ?)",
        (title, description, current_user_id, ts, ts),
    )
    chat_id = int(chat_cursor.lastrowid)

    for user_id in sorted(set(member_ids)):
        db.execute(
            "INSERT INTO chat_members(chat_id, user_id, last_read_at, last_delivered_at, created_at) VALUES(?, ?, 0, 0, ?)",
            (chat_id, user_id, ts),
        )

    db.commit()
    return chat_id


def update_chat_avatar_record(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    file_name: str,
    updated_at: int,
) -> None:
    db.execute(
        "UPDATE chats SET avatar_url = ?, updated_at = ? WHERE id = ?",
        (file_name, updated_at, chat_id),
    )
    db.commit()
