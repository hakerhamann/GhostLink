import sqlite3
from collections.abc import Callable


class ChatGroupService:
    def __init__(
        self,
        *,
        user_to_json: Callable[[sqlite3.Row], dict],
        normalize_avatar_url: Callable[[str | None], str | None],
        normalize_login: Callable[[str], str],
        parse_int: Callable,
    ):
        self.user_to_json = user_to_json
        self.normalize_avatar_url = normalize_avatar_url
        self.normalize_login = normalize_login
        self.parse_int = parse_int

    def get_member_user_ids(self, db: sqlite3.Connection, chat_id: int) -> list[int]:
        rows = db.execute(
            "SELECT user_id FROM chat_members WHERE chat_id = ?",
            (chat_id,),
        ).fetchall()
        return [int(r["user_id"]) for r in rows]

    def is_chat_member(self, db: sqlite3.Connection, chat_id: int, user_id: int) -> bool:
        row = db.execute(
            "SELECT 1 FROM chat_members WHERE chat_id = ? AND user_id = ?",
            (chat_id, user_id),
        ).fetchone()
        return row is not None

    def build_group_info_payload(self, db: sqlite3.Connection, chat_id: int) -> dict | None:
        chat_row = db.execute(
            "SELECT id, is_direct, title, description, avatar_url, created_by FROM chats WHERE id = ?",
            (chat_id,),
        ).fetchone()
        if chat_row is None:
            return None
        if int(chat_row["is_direct"]) == 1:
            return None

        member_rows = db.execute(
            """
            SELECT u.*
            FROM chat_members cm
            JOIN users u ON u.id = cm.user_id
            WHERE cm.chat_id = ?
            ORDER BY u.display_name COLLATE NOCASE, u.login COLLATE NOCASE
            """,
            (chat_id,),
        ).fetchall()
        members = [self.user_to_json(row) for row in member_rows]

        created_by_uid = None
        created_by_login = None
        created_by_id = self.parse_int(chat_row["created_by"], 0)
        if created_by_id > 0:
            created_by_row = db.execute(
                "SELECT id, login FROM users WHERE id = ? LIMIT 1",
                (created_by_id,),
            ).fetchone()
            if created_by_row is not None:
                created_by_uid = f"u{int(created_by_row['id'])}"
                created_by_login = self.normalize_login(created_by_row["login"])

        return {
            "group": {
                "id": str(chat_id),
                "title": str(chat_row["title"] or "").strip() or f"Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р РЋРЎв„ўР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° {chat_id}",
                "description": str(chat_row["description"] or "").strip(),
                "avatarUrl": self.normalize_avatar_url(chat_row["avatar_url"]),
                "createdByUid": created_by_uid,
                "createdByLogin": created_by_login,
            },
            "members": members,
        }

    def get_chat_title_for_user(self, db: sqlite3.Connection, chat_id: int, user_id: int) -> str:
        row = db.execute(
            "SELECT is_direct, title FROM chats WHERE id = ?",
            (chat_id,),
        ).fetchone()
        if row is None:
            return "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В§Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ"

        if int(row["is_direct"]) == 1:
            other = db.execute(
                """
                SELECT u.display_name
                FROM chat_members cm
                JOIN users u ON u.id = cm.user_id
                WHERE cm.chat_id = ? AND cm.user_id != ?
                LIMIT 1
                """,
                (chat_id, user_id),
            ).fetchone()
            if other:
                return other["display_name"]
        else:
            title = (row["title"] or "").strip()
            if title:
                return title

        return f"Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В§Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ {chat_id}"

    def add_group_member_record(
        self,
        db: sqlite3.Connection,
        *,
        chat_id: int,
        user_id: int,
        actor_user_id: int,
        actor_display_name: str,
        target_display_name: str,
        timestamp: int,
    ) -> None:
        db.execute(
            "INSERT INTO chat_members(chat_id, user_id, last_read_at, last_delivered_at, created_at) VALUES(?, ?, ?, ?, ?)",
            (chat_id, user_id, timestamp, timestamp, timestamp),
        )
        self.create_system_message(
            db,
            chat_id=chat_id,
            sender_id=actor_user_id,
            text=f"{actor_display_name} добавил(а) {target_display_name}",
            kind="system",
            timestamp=timestamp,
        )
        db.execute(
            "UPDATE chats SET updated_at = ? WHERE id = ?",
            (timestamp, chat_id),
        )
        db.commit()

    def leave_group_record(
        self,
        db: sqlite3.Connection,
        *,
        chat_id: int,
        user_id: int,
        created_by,
        timestamp: int,
    ) -> dict:
        members_before = db.execute(
            "SELECT user_id FROM chat_members WHERE chat_id = ? ORDER BY created_at ASC, user_id ASC",
            (chat_id,),
        ).fetchall()
        if len(members_before) <= 1:
            db.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
            db.commit()
            return {"left": True, "deleted": True}

        db.execute(
            "DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?",
            (chat_id, user_id),
        )

        replacement_creator_id = self.parse_int(created_by, 0)
        if replacement_creator_id == user_id:
            replacement_row = db.execute(
                "SELECT user_id FROM chat_members WHERE chat_id = ? ORDER BY created_at ASC, user_id ASC LIMIT 1",
                (chat_id,),
            ).fetchone()
            if replacement_row is None:
                db.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
                db.commit()
                return {"left": True, "deleted": True}
            replacement_creator_id = int(replacement_row["user_id"])

        db.execute(
            "UPDATE chats SET created_by = ?, updated_at = ? WHERE id = ?",
            (replacement_creator_id if replacement_creator_id > 0 else None, timestamp, chat_id),
        )
        db.commit()
        return {"left": True, "deleted": False}

    def remove_group_member_record(
        self,
        db: sqlite3.Connection,
        *,
        chat_id: int,
        target_user_id: int,
        actor_user_id: int,
        actor_display_name: str,
        target_display_name: str,
        timestamp: int,
    ) -> None:
        self.create_system_message(
            db,
            chat_id=chat_id,
            sender_id=actor_user_id,
            text=f"{actor_display_name} исключил(а) {target_display_name}",
            kind="system",
            timestamp=timestamp,
        )
        db.execute(
            "DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?",
            (chat_id, target_user_id),
        )
        db.execute(
            "UPDATE chats SET updated_at = ? WHERE id = ?",
            (timestamp, chat_id),
        )
        db.commit()

    def create_avatar_changed_message(
        self,
        db: sqlite3.Connection,
        *,
        chat_id: int,
        actor_user_id: int,
        actor_display_name: str,
        avatar_file_name: str,
        timestamp: int,
    ) -> None:
        self.create_system_message(
            db,
            chat_id=chat_id,
            sender_id=actor_user_id,
            text=f"{actor_display_name} изменил(а) аватарку группы",
            kind="system_avatar",
            media_url=avatar_file_name,
            timestamp=timestamp,
        )

    def create_system_message(
        self,
        db: sqlite3.Connection,
        *,
        chat_id: int,
        sender_id: int,
        text: str,
        kind: str,
        timestamp: int,
        media_url: str | None = None,
    ) -> None:
        db.execute(
            """
            INSERT INTO messages(
                chat_id,
                sender_id,
                text,
                kind,
                media_url,
                created_at,
                updated_at
            )
            VALUES(?, ?, ?, ?, ?, ?, 0)
            """,
            (chat_id, sender_id, text, kind, media_url, timestamp),
        )
