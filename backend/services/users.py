import sqlite3
from collections.abc import Callable


def user_to_json(row: sqlite3.Row, *, normalize_avatar_url: Callable[[str | None], str | None]) -> dict:
    return {
        "uid": f"u{row['id']}",
        "login": row["login"],
        "displayName": row["display_name"],
        "avatarUrl": normalize_avatar_url(row["avatar_url"]),
    }


def parse_uid(uid: str) -> int:
    if not uid:
        return 0
    if uid.startswith("u"):
        uid = uid[1:]
    try:
        return int(uid)
    except ValueError:
        return 0


def list_user_items(
    db: sqlite3.Connection,
    *,
    query: str,
    current_user_id: int,
    user_to_json_fn: Callable[[sqlite3.Row], dict],
) -> dict:
    sql = "SELECT * FROM users"
    params: list[str] = []
    if query:
        like = f"%{query}%"
        sql += " WHERE login LIKE ? OR display_name LIKE ?"
        params = [like, like]
    sql += " ORDER BY display_name COLLATE NOCASE, login COLLATE NOCASE LIMIT 500"

    rows = db.execute(sql, tuple(params)).fetchall()
    items = [user_to_json_fn(row) for row in rows if int(row["id"]) != current_user_id]
    return {"items": items}


def update_user_display_name(
    db: sqlite3.Connection,
    *,
    user_id: int,
    display_name: str,
    updated_at: int,
) -> sqlite3.Row:
    db.execute(
        "UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?",
        (display_name, updated_at, user_id),
    )
    db.commit()
    return db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def update_user_avatar(
    db: sqlite3.Connection,
    *,
    user_id: int,
    avatar_file_name: str,
    updated_at: int,
) -> tuple[sqlite3.Row, str]:
    old_row = db.execute("SELECT avatar_url FROM users WHERE id = ?", (user_id,)).fetchone()
    old_avatar = str(old_row["avatar_url"] or "").strip() if old_row else ""

    db.execute(
        "UPDATE users SET avatar_url = ?, updated_at = ? WHERE id = ?",
        (avatar_file_name, updated_at, user_id),
    )
    db.commit()

    user = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    return user, old_avatar
