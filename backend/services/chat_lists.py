import sqlite3
from collections.abc import Callable


def list_chat_previews(
    db: sqlite3.Connection,
    *,
    user_id: int,
    build_chat_preview: Callable[[sqlite3.Connection, int, int], dict],
) -> dict:
    rows = db.execute(
        """
        SELECT c.id
        FROM chats c
        JOIN chat_members cm ON cm.chat_id = c.id
        WHERE cm.user_id = ?
        ORDER BY c.updated_at DESC
        """,
        (user_id,),
    ).fetchall()

    previews = [build_chat_preview(db, int(row["id"]), user_id) for row in rows]
    previews.sort(key=lambda item: item["timestamp"], reverse=True)
    return {"items": previews}
