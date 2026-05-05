import json
import os
import sqlite3
import threading
from pathlib import Path
from typing import Callable


class PushService:
    def __init__(
        self,
        *,
        db_path: Path,
        credentials_path: Path,
        max_tokens_per_user: int,
        voice_fallback_text: str,
        image_fallback_text: str,
        video_fallback_text: str,
        firebase_admin_module,
        credentials_module,
        messaging_module,
        logger,
        get_chat_title_for_user: Callable[[sqlite3.Connection, int, int], str],
    ):
        self.db_path = db_path
        self.credentials_path = credentials_path
        self.max_tokens_per_user = max_tokens_per_user
        self.voice_fallback_text = voice_fallback_text
        self.image_fallback_text = image_fallback_text
        self.video_fallback_text = video_fallback_text
        self.firebase_admin = firebase_admin_module
        self.credentials = credentials_module
        self.messaging = messaging_module
        self.logger = logger
        self.get_chat_title_for_user = get_chat_title_for_user
        self._fcm_initialized = False
        self._fcm_lock = threading.Lock()

    def init_fcm(self) -> bool:
        if self._fcm_initialized:
            return True
        if self.firebase_admin is None or self.credentials is None or self.messaging is None:
            return False

        with self._fcm_lock:
            if self._fcm_initialized:
                return True

            existing = self.firebase_admin._apps  # type: ignore[attr-defined]
            if existing:
                self._fcm_initialized = True
                return True

            service_account_json = os.getenv("FCM_SERVICE_ACCOUNT_JSON", "").strip()
            credential = None

            try:
                if service_account_json:
                    payload = json.loads(service_account_json)
                    credential = self.credentials.Certificate(payload)
                elif self.credentials_path.exists():
                    credential = self.credentials.Certificate(str(self.credentials_path))
                else:
                    self.logger.warning(
                        "FCM is disabled: set FCM_SERVICE_ACCOUNT_PATH or FCM_SERVICE_ACCOUNT_JSON."
                    )
                    return False

                self.firebase_admin.initialize_app(credential)
                self._fcm_initialized = True
                return True
            except Exception as exc:  # pragma: no cover - network/env dependent
                self.logger.warning("FCM init failed: %s", exc)
                return False

    @staticmethod
    def should_remove_push_token(exc: Exception | None) -> bool:
        if exc is None:
            return False
        lowered = f"{exc.__class__.__name__}: {exc}".lower()
        return (
            "unregistered" in lowered
            or "registration token is not a valid" in lowered
            or "invalid registration token" in lowered
            or "requested entity was not found" in lowered
        )

    def trim_push_tokens_for_user(self, db: sqlite3.Connection, user_id: int):
        rows = db.execute(
            """
            SELECT token
            FROM push_tokens
            WHERE user_id = ?
            ORDER BY updated_at DESC
            """,
            (user_id,),
        ).fetchall()

        if len(rows) <= self.max_tokens_per_user:
            return

        stale = [str(row["token"]) for row in rows[self.max_tokens_per_user :] if row["token"]]
        if not stale:
            return

        db.executemany("DELETE FROM push_tokens WHERE token = ?", [(token,) for token in stale])

    def send_message_push_notifications(
        self,
        db: sqlite3.Connection,
        *,
        chat_id: int,
        sender_row: sqlite3.Row,
        message_text: str,
        message_type: str,
        message_id: int,
        message_ts: int,
    ):
        if not self.init_fcm():
            return

        recipients = db.execute(
            """
            SELECT DISTINCT cm.user_id, pt.token
            FROM chat_members cm
            JOIN push_tokens pt ON pt.user_id = cm.user_id
            WHERE cm.chat_id = ? AND cm.user_id != ?
            """,
            (chat_id, int(sender_row["id"])),
        ).fetchall()
        if not recipients:
            return

        chat_row = db.execute(
            "SELECT is_direct FROM chats WHERE id = ?",
            (chat_id,),
        ).fetchone()
        is_group_chat = bool(chat_row and int(chat_row["is_direct"]) == 0)

        member_count_row = db.execute(
            "SELECT COUNT(*) AS cnt FROM chat_members WHERE chat_id = ?",
            (chat_id,),
        ).fetchone()
        member_count = int(member_count_row["cnt"]) if member_count_row else 2

        sender_uid = f"u{int(sender_row['id'])}"
        sender_login = str(sender_row["login"] or "").strip()
        sender_name = str(sender_row["display_name"] or "").strip() or sender_login or "GhostLink"

        grouped_tokens: dict[int, list[str]] = {}
        for row in recipients:
            user_id = int(row["user_id"])
            token = str(row["token"] or "").strip()
            if not token:
                continue
            grouped_tokens.setdefault(user_id, []).append(token)

        body = message_text.strip()
        if message_type == "voice":
            body = self.voice_fallback_text
        elif message_type == "image":
            body = self.image_fallback_text
        elif message_type == "video":
            body = self.video_fallback_text
        if len(body) > 200:
            body = f"{body[:197]}..."

        for recipient_id, tokens in grouped_tokens.items():
            if not tokens:
                continue

            chat_title = self.get_chat_title_for_user(db, chat_id, recipient_id)
            data_payload = {
                "type": "message",
                "chatId": str(chat_id),
                "chatTitle": chat_title,
                "text": body,
                "messageType": message_type,
                "messageId": str(message_id),
                "timestamp": str(message_ts),
                "senderUid": sender_uid,
                "senderName": sender_name,
                "isGroup": "1" if is_group_chat else "0",
                "memberCount": str(max(member_count, 1)),
                "peerUid": sender_uid,
                "peerLogin": sender_login,
                "peerDisplayName": sender_name,
            }

            multicast = self.messaging.MulticastMessage(
                tokens=tokens,
                data=data_payload,
                # Data-only payload keeps notification lifecycle fully under app control
                # (heads-up behavior, per-chat dismissal, and badge cleanup).
                android=self.messaging.AndroidConfig(priority="high"),
            )

            try:
                response = self.messaging.send_each_for_multicast(multicast)
            except Exception as exc:  # pragma: no cover - network/env dependent
                self.logger.warning("FCM send failed for chat %s: %s", chat_id, exc)
                continue

            stale_tokens: list[str] = []
            for index, item in enumerate(response.responses):
                if item.success:
                    continue
                token = tokens[index] if index < len(tokens) else None
                if token and self.should_remove_push_token(item.exception):
                    stale_tokens.append(token)

            if stale_tokens:
                db.executemany("DELETE FROM push_tokens WHERE token = ?", [(token,) for token in stale_tokens])
                db.commit()

    def send_message_push_notifications_async(
        self,
        *,
        chat_id: int,
        sender_id: int,
        message_text: str,
        message_type: str,
        message_id: int,
        message_ts: int,
    ):
        def worker():
            db = None
            try:
                db = sqlite3.connect(self.db_path)
                db.row_factory = sqlite3.Row
                db.execute("PRAGMA foreign_keys = ON")
                sender_row = db.execute(
                    "SELECT id, login, display_name FROM users WHERE id = ?",
                    (sender_id,),
                ).fetchone()
                if sender_row is None:
                    return
                self.send_message_push_notifications(
                    db,
                    chat_id=chat_id,
                    sender_row=sender_row,
                    message_text=message_text,
                    message_type=message_type,
                    message_id=message_id,
                    message_ts=message_ts,
                )
            except Exception as exc:  # pragma: no cover - background push is best effort
                self.logger.warning("Async push dispatch failed for message %s: %s", message_id, exc)
            finally:
                if db is not None:
                    db.close()

        threading.Thread(
            target=worker,
            daemon=True,
            name=f"push-msg-{message_id}",
        ).start()
