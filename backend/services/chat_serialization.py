import sqlite3
from collections.abc import Callable


class ChatSerializationService:
    def __init__(
        self,
        *,
        get_chat_title_for_user: Callable[[sqlite3.Connection, int, int], str],
        normalize_avatar_url: Callable[[str | None], str | None],
        normalize_voice_url: Callable[[str | None], str | None],
        normalize_photo_url: Callable[[str | None], str | None],
        normalize_video_url: Callable[[str | None], str | None],
        normalize_media_urls: Callable,
        normalize_int_list: Callable,
        normalize_reply_preview_text: Callable,
        parse_int: Callable,
        voice_fallback_text: str,
        image_fallback_text: str,
        video_fallback_text: str,
    ):
        self.get_chat_title_for_user = get_chat_title_for_user
        self.normalize_avatar_url = normalize_avatar_url
        self.normalize_voice_url = normalize_voice_url
        self.normalize_photo_url = normalize_photo_url
        self.normalize_video_url = normalize_video_url
        self.normalize_media_urls = normalize_media_urls
        self.normalize_int_list = normalize_int_list
        self.normalize_reply_preview_text = normalize_reply_preview_text
        self.parse_int = parse_int
        self.voice_fallback_text = voice_fallback_text
        self.image_fallback_text = image_fallback_text
        self.video_fallback_text = video_fallback_text

    def build_chat_preview(self, db: sqlite3.Connection, chat_id: int, user_id: int) -> dict:
        chat_row = db.execute(
            "SELECT is_direct, title, avatar_url FROM chats WHERE id = ?",
            (chat_id,),
        ).fetchone()
        is_group = bool(chat_row) and int(chat_row["is_direct"]) == 0
        peer_uid = None
        peer_login = None
        peer_display_name = None

        member_count_row = db.execute(
            "SELECT COUNT(*) AS cnt FROM chat_members WHERE chat_id = ?",
            (chat_id,),
        ).fetchone()
        member_count = int(member_count_row["cnt"]) if member_count_row else 0

        avatar_url = None
        if is_group and chat_row:
            avatar_url = self.normalize_avatar_url(chat_row["avatar_url"])
        if not is_group:
            peer_row = db.execute(
                """
                SELECT u.id, u.login, u.display_name, u.avatar_url
                FROM chat_members cm
                JOIN users u ON u.id = cm.user_id
                WHERE cm.chat_id = ? AND cm.user_id != ?
                LIMIT 1
                """,
                (chat_id, user_id),
            ).fetchone()
            if peer_row:
                peer_uid = f"u{int(peer_row['id'])}"
                peer_login = str(peer_row["login"] or "").strip()
                peer_display_name = str(peer_row["display_name"] or "").strip()
                avatar_url = self.normalize_avatar_url(peer_row["avatar_url"])

        last_msg = db.execute(
            """
            SELECT m.id, m.sender_id, m.text, m.kind, m.created_at, u.display_name
            FROM messages m
            JOIN users u ON u.id = m.sender_id
            WHERE m.chat_id = ?
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT 1
            """,
            (chat_id,),
        ).fetchone()

        member = db.execute(
            "SELECT last_read_at FROM chat_members WHERE chat_id = ? AND user_id = ?",
            (chat_id, user_id),
        ).fetchone()
        last_read_at = int(member["last_read_at"]) if member else 0

        unread_row = db.execute(
            """
            SELECT COUNT(*) AS cnt
            FROM messages
            WHERE chat_id = ?
              AND sender_id != ?
              AND created_at > ?
            """,
            (chat_id, user_id, last_read_at),
        ).fetchone()
        unread_count = int(unread_row["cnt"]) if unread_row else 0

        if last_msg is None:
            return {
                "id": str(chat_id),
                "title": self.get_chat_title_for_user(db, chat_id, user_id),
                "avatarUrl": avatar_url,
                "peerUid": peer_uid,
                "peerLogin": peer_login,
                "peerDisplayName": peer_display_name,
                "lastMessage": "",
                "timestamp": 0,
                "unreadCount": unread_count,
                "lastMessageOutgoing": False,
                "lastMessageDelivered": True,
                "lastMessageRead": True,
                "isGroup": is_group,
                "memberCount": member_count,
            }

        sender_id = int(last_msg["sender_id"])
        last_message_outgoing = sender_id == user_id

        last_message_delivered = True
        last_message_read = True
        if last_message_outgoing:
            others = db.execute(
                """
                SELECT last_read_at, last_delivered_at
                FROM chat_members
                WHERE chat_id = ? AND user_id != ?
                """,
                (chat_id, user_id),
            ).fetchall()
            msg_ts = int(last_msg["created_at"])
            if others:
                last_message_delivered = all(int(r["last_delivered_at"]) >= msg_ts for r in others)
                last_message_read = all(int(r["last_read_at"]) >= msg_ts for r in others)

        last_message_text = str(last_msg["text"] or "").strip()
        last_kind = str(last_msg["kind"] or "text").strip().lower()
        if not last_message_text:
            if last_kind == "voice":
                last_message_text = self.voice_fallback_text
            elif last_kind == "image":
                last_message_text = self.image_fallback_text
            elif last_kind == "video":
                last_message_text = self.video_fallback_text

        return {
            "id": str(chat_id),
            "title": self.get_chat_title_for_user(db, chat_id, user_id),
            "avatarUrl": avatar_url,
            "peerUid": peer_uid,
            "peerLogin": peer_login,
            "peerDisplayName": peer_display_name,
            "lastMessage": last_message_text,
            "timestamp": int(last_msg["created_at"]),
            "unreadCount": unread_count,
            "lastMessageOutgoing": last_message_outgoing,
            "lastMessageDelivered": last_message_delivered,
            "lastMessageRead": last_message_read,
            "isGroup": is_group,
            "memberCount": member_count,
        }

    def build_message_json(self, db: sqlite3.Connection, row: sqlite3.Row, chat_id: int) -> dict:
        msg_ts = int(row["created_at"])
        updated_at = int(row["updated_at"]) if row["updated_at"] else 0
        delivered_rows = db.execute(
            "SELECT user_id FROM chat_members WHERE chat_id = ? AND last_delivered_at >= ?",
            (chat_id, msg_ts),
        ).fetchall()
        delivered_by = [f"u{int(r['user_id'])}" for r in delivered_rows]
        read_rows = db.execute(
            "SELECT user_id FROM chat_members WHERE chat_id = ? AND last_read_at >= ?",
            (chat_id, msg_ts),
        ).fetchall()
        read_by = [f"u{int(r['user_id'])}" for r in read_rows]

        sender_uid = f"u{int(row['sender_id'])}"
        if sender_uid not in delivered_by:
            delivered_by.append(sender_uid)
        if sender_uid not in read_by:
            read_by.append(sender_uid)

        message_kind = str(row["kind"] or "text").strip().lower()
        if message_kind not in {"voice", "image", "video"}:
            message_kind = "text"

        text = str(row["text"] or "")
        voice_url = None
        voice_duration = 0
        image_url = None
        image_urls = []
        image_width = 0
        image_height = 0
        image_widths = []
        image_heights = []
        video_url = None
        video_duration = 0
        if message_kind == "voice":
            voice_url = self.normalize_voice_url(row["media_url"])
            voice_duration = max(0, int(row["media_duration"] or 0))
            if not text.strip():
                text = self.voice_fallback_text
        elif message_kind == "image":
            image_url = self.normalize_photo_url(row["media_url"])
            image_urls = self.normalize_media_urls(row["media_urls"], self.normalize_photo_url)
            if not image_urls and image_url is not None:
                image_urls = [image_url]
            image_width = max(0, int(row["media_width"] or 0))
            image_height = max(0, int(row["media_height"] or 0))
            image_widths = self.normalize_int_list(row["media_widths"] if "media_widths" in row.keys() else None)
            image_heights = self.normalize_int_list(row["media_heights"] if "media_heights" in row.keys() else None)
            if not image_widths and image_width > 0:
                image_widths = [image_width]
            if not image_heights and image_height > 0:
                image_heights = [image_height]
            if not text.strip():
                text = self.image_fallback_text
        elif message_kind == "video":
            video_url = self.normalize_video_url(row["media_url"])
            video_duration = max(0, int(row["media_duration"] or 0))
            if not text.strip():
                text = self.video_fallback_text

        sender_avatar_url = None
        if "avatar_url" in row.keys():
            sender_avatar_url = self.normalize_avatar_url(row["avatar_url"])

        reply_to = None
        reply_to_message_id = self.parse_int(row["reply_to_message_id"], 0)
        if reply_to_message_id > 0:
            reply_sender_name = str(row["reply_to_sender_name"] or "").strip()
            reply_text = str(row["reply_to_text"] or "").strip()
            reply_image_url = None
            reply_media_row = db.execute(
                """
                SELECT kind, text, media_url, media_urls
                FROM messages
                WHERE id = ? AND chat_id = ?
                LIMIT 1
                """,
                (reply_to_message_id, chat_id),
            ).fetchone()
            if reply_media_row is not None:
                reply_kind = str(reply_media_row["kind"] or "").strip().lower()
                if reply_kind == "image":
                    reply_image_urls = self.normalize_media_urls(reply_media_row["media_urls"], self.normalize_photo_url)
                    if reply_image_urls:
                        reply_image_url = reply_image_urls[0]
                    else:
                        reply_image_url = self.normalize_photo_url(reply_media_row["media_url"])
                if reply_kind in ("text", "image"):
                    original_reply_text = self.normalize_reply_preview_text(reply_media_row["text"])
                    if original_reply_text and original_reply_text != self.image_fallback_text:
                        reply_text = original_reply_text
            if not reply_text:
                reply_text = "..."
            reply_to = {
                "messageId": str(reply_to_message_id),
                "senderName": reply_sender_name,
                "text": reply_text,
                "imageUrl": reply_image_url,
            }

        return {
            "id": str(row["id"]),
            "senderId": sender_uid,
            "senderName": row["display_name"],
            "senderAvatarUrl": sender_avatar_url,
            "text": text,
            "timestamp": msg_ts,
            "deliveredBy": delivered_by,
            "readBy": read_by,
            "edited": updated_at > msg_ts,
            "type": message_kind,
            "voiceUrl": voice_url,
            "voiceDurationSec": voice_duration,
            "imageUrl": image_url,
            "imageUrls": image_urls,
            "imageWidth": image_width,
            "imageHeight": image_height,
            "imageWidths": image_widths,
            "imageHeights": image_heights,
            "videoUrl": video_url,
            "videoDurationSec": video_duration,
            "replyTo": reply_to,
        }
