import sqlite3
from collections.abc import Callable


def mark_chat_read(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    user_id: int,
    requested_read_up_to,
    now_ms: Callable[[], int],
    parse_int: Callable,
) -> dict:
    requested_ts = parse_int(requested_read_up_to, 0)
    ts = requested_ts if requested_ts > 0 else now_ms()
    if ts < 0:
        ts = 0

    member_row = db.execute(
        "SELECT last_read_at, last_delivered_at FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (chat_id, user_id),
    ).fetchone()
    prev_last_read = parse_int(member_row["last_read_at"], 0) if member_row is not None else 0
    prev_last_delivered = parse_int(member_row["last_delivered_at"], 0) if member_row is not None else 0
    next_last_read = max(prev_last_read, ts)
    next_last_delivered = max(prev_last_delivered, ts)

    db.execute(
        "UPDATE chat_members SET last_read_at = ?, last_delivered_at = ? WHERE chat_id = ? AND user_id = ?",
        (next_last_read, next_last_delivered, chat_id, user_id),
    )
    db.commit()

    return {
        "ok": True,
        "lastReadAt": next_last_read,
        "lastDeliveredAt": next_last_delivered,
    }
