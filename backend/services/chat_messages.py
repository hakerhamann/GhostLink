import sqlite3
from collections.abc import Callable
from pathlib import Path


def resolve_reply_preview(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    reply_to_message_id: int,
    voice_fallback_text: str,
    image_fallback_text: str,
    video_fallback_text: str,
    normalize_reply_preview_text: Callable[[str], str],
) -> tuple[int, str | None, str | None]:
    if reply_to_message_id <= 0:
        return 0, None, None

    reply_row = db.execute(
        """
        SELECT m.id, m.text, m.kind, u.display_name
        FROM messages m
        JOIN users u ON u.id = m.sender_id
        WHERE m.id = ? AND m.chat_id = ?
        LIMIT 1
        """,
        (reply_to_message_id, chat_id),
    ).fetchone()
    if reply_row is None:
        return 0, None, None

    reply_id = int(reply_row["id"])
    reply_sender_name = str(reply_row["display_name"] or "").strip()
    reply_kind = str(reply_row["kind"] or "text").strip().lower()
    if reply_kind == "voice":
        reply_text = voice_fallback_text
    elif reply_kind == "image":
        reply_text = normalize_reply_preview_text(reply_row["text"])
        if not reply_text or reply_text == image_fallback_text:
            reply_text = image_fallback_text
    elif reply_kind == "video":
        reply_text = video_fallback_text
    else:
        reply_text = normalize_reply_preview_text(reply_row["text"])
        if not reply_text:
            reply_text = "..."

    return reply_id, reply_sender_name, reply_text


def list_chat_messages(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    user_id: int,
    delivered_at: int,
    build_message_json: Callable[[sqlite3.Connection, sqlite3.Row, int], dict],
) -> dict:
    db.execute(
        "UPDATE chat_members SET last_delivered_at = ? WHERE chat_id = ? AND user_id = ?",
        (delivered_at, chat_id, user_id),
    )
    db.commit()

    rows = db.execute(
        """
        SELECT
            m.id,
            m.sender_id,
            m.text,
            m.kind,
            m.media_url,
            m.media_urls,
            m.media_duration,
            m.media_width,
            m.media_height,
            m.media_widths,
            m.media_heights,
            m.reply_to_message_id,
            m.reply_to_sender_name,
            m.reply_to_text,
            m.created_at,
            m.updated_at,
            u.display_name,
            u.avatar_url
        FROM messages m
        JOIN users u ON u.id = m.sender_id
        WHERE m.chat_id = ?
        ORDER BY m.created_at ASC, m.id ASC
        LIMIT 500
        """,
        (chat_id,),
    ).fetchall()

    return {"items": [build_message_json(db, row, chat_id) for row in rows]}


def delete_chat_message(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    message_id: int,
    message_row: sqlite3.Row,
    voice_dir: Path,
    photo_dir: Path,
    video_dir: Path,
    now_ms: Callable[[], int],
    normalize_media_urls: Callable,
) -> dict:
    db.execute(
        "DELETE FROM messages WHERE id = ? AND chat_id = ?",
        (message_id, chat_id),
    )
    latest = db.execute(
        "SELECT MAX(created_at) AS ts FROM messages WHERE chat_id = ?",
        (chat_id,),
    ).fetchone()
    updated_at = int(latest["ts"]) if latest and latest["ts"] else now_ms()
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (updated_at, chat_id),
    )
    db.commit()

    message_kind = str(message_row["kind"] or "text").strip().lower()
    if message_kind in {"voice", "image", "video"}:
        media_values = [str(message_row["media_url"] or "").strip()]
        if message_kind == "image":
            media_values.extend(normalize_media_urls(message_row["media_urls"]))
        for media_value in media_values:
            if not media_value or "://" in media_value:
                continue
            if message_kind == "voice":
                media_dir = voice_dir
            elif message_kind == "image":
                media_dir = photo_dir
            else:
                media_dir = video_dir
            path = media_dir / media_value
            if path.exists():
                try:
                    path.unlink()
                except OSError:
                    pass

    return {"ok": True}


def update_chat_message(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    message_id: int,
    text: str,
    media_url,
    media_urls,
    media_width: int,
    media_height: int,
    media_widths,
    media_heights,
    updated_at: int,
    build_message_json: Callable[[sqlite3.Connection, sqlite3.Row, int], dict],
) -> dict:
    db.execute(
        """
        UPDATE messages
        SET text = ?, media_url = ?, media_urls = ?, media_width = ?, media_height = ?, media_widths = ?, media_heights = ?, updated_at = ?
        WHERE id = ? AND chat_id = ?
        """,
        (
            text,
            media_url,
            media_urls,
            media_width,
            media_height,
            media_widths,
            media_heights,
            updated_at,
            message_id,
            chat_id,
        ),
    )
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (updated_at, chat_id),
    )
    db.commit()

    row = db.execute(
        """
        SELECT
            m.id,
            m.sender_id,
            m.text,
            m.kind,
            m.media_url,
            m.media_urls,
            m.media_duration,
            m.media_width,
            m.media_height,
            m.media_widths,
            m.media_heights,
            m.reply_to_message_id,
            m.reply_to_sender_name,
            m.reply_to_text,
            m.created_at,
            m.updated_at,
            u.display_name,
            u.avatar_url
        FROM messages m
        JOIN users u ON u.id = m.sender_id
        WHERE m.id = ? AND m.chat_id = ?
        """,
        (message_id, chat_id),
    ).fetchone()

    return {"message": build_message_json(db, row, chat_id)}


def create_chat_message(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    user_id: int,
    text: str,
    message_type: str,
    media_url,
    media_urls,
    media_duration: int,
    media_width: int,
    media_height: int,
    media_widths,
    media_heights,
    reply_to_message_id: int,
    reply_to_sender_name,
    reply_to_text,
    timestamp: int,
    build_message_json: Callable[[sqlite3.Connection, sqlite3.Row, int], dict],
) -> tuple[dict, int, int]:
    cursor = db.execute(
        """
        INSERT INTO messages(
            chat_id,
            sender_id,
            text,
            kind,
            media_url,
            media_urls,
            media_duration,
            media_width,
            media_height,
            media_widths,
            media_heights,
            reply_to_message_id,
            reply_to_sender_name,
            reply_to_text,
            created_at,
            updated_at
        )
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        (
            chat_id,
            user_id,
            text,
            message_type,
            media_url,
            media_urls,
            media_duration,
            media_width,
            media_height,
            media_widths,
            media_heights,
            reply_to_message_id if reply_to_message_id > 0 else None,
            reply_to_sender_name,
            reply_to_text,
            timestamp,
        ),
    )

    db.execute(
        "UPDATE chat_members SET last_read_at = ?, last_delivered_at = ? WHERE chat_id = ? AND user_id = ?",
        (timestamp, timestamp, chat_id, user_id),
    )
    db.execute(
        """
        UPDATE chat_members
        SET last_delivered_at = CASE
            WHEN last_delivered_at < ? THEN ?
            ELSE last_delivered_at
        END
        WHERE chat_id = ? AND user_id != ?
        """,
        (timestamp, timestamp, chat_id, user_id),
    )
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (timestamp, chat_id),
    )

    db.commit()
    message_id = int(cursor.lastrowid)

    row = db.execute(
        """
        SELECT
            m.id,
            m.sender_id,
            m.text,
            m.kind,
            m.media_url,
            m.media_urls,
            m.media_duration,
            m.media_width,
            m.media_height,
            m.media_widths,
            m.media_heights,
            m.reply_to_message_id,
            m.reply_to_sender_name,
            m.reply_to_text,
            m.created_at,
            m.updated_at,
            u.display_name,
            u.avatar_url
        FROM messages m
        JOIN users u ON u.id = m.sender_id
        WHERE m.id = ?
        """,
        (message_id,),
    ).fetchone()

    return {"message": build_message_json(db, row, chat_id)}, message_id, timestamp
