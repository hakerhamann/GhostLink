import hashlib
import json
import os
import secrets
import sqlite3
import threading
import time
from functools import wraps
from pathlib import Path

from flask import Flask, g, jsonify, request, send_from_directory

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
except Exception:  # pragma: no cover - backend still works without FCM libs
    firebase_admin = None
    credentials = None
    messaging = None

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "reserv.db"
AVATAR_DIR = BASE_DIR / "uploads" / "avatars"
VOICE_DIR = BASE_DIR / "uploads" / "voice"
PHOTO_DIR = BASE_DIR / "uploads" / "photos"
VIDEO_DIR = BASE_DIR / "uploads" / "videos"
UPDATE_DIR = BASE_DIR / "updates"
UPDATE_FEED_PATH = BASE_DIR / "update_feed.json"
FCM_CREDENTIALS_PATH = Path(
    os.getenv("FCM_SERVICE_ACCOUNT_PATH", str(BASE_DIR / "fcm-service-account.json"))
)
MAX_PUSH_TOKENS_PER_USER = int(os.getenv("MAX_PUSH_TOKENS_PER_USER", "20"))
MAX_VOICE_FILE_BYTES = int(os.getenv("MAX_VOICE_FILE_BYTES", str(12 * 1024 * 1024)))
MAX_VOICE_DURATION_SEC = int(os.getenv("MAX_VOICE_DURATION_SEC", "600"))
MAX_PHOTO_FILE_BYTES = int(os.getenv("MAX_PHOTO_FILE_BYTES", str(20 * 1024 * 1024)))
MAX_VIDEO_FILE_BYTES = int(os.getenv("MAX_VIDEO_FILE_BYTES", str(40 * 1024 * 1024)))
MAX_VIDEO_DURATION_SEC = int(os.getenv("MAX_VIDEO_DURATION_SEC", "60"))
VOICE_MESSAGE_FALLBACK_TEXT = "\U0001F3A4 Voice message"
IMAGE_MESSAGE_FALLBACK_TEXT = "\U0001F4F7 \u0424\u043e\u0442\u043e"
VIDEO_MESSAGE_FALLBACK_TEXT = "\U0001F3A5 \u0412\u0438\u0434\u0435\u043e"

app = Flask(__name__)
_db_lock = threading.Lock()
_fcm_lock = threading.Lock()
_fcm_initialized = False


def now_ms() -> int:
    return int(time.time() * 1000)


def get_db() -> sqlite3.Connection:
    db = getattr(g, "db", None)
    if db is None:
        db = sqlite3.connect(DB_PATH)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        g.db = db
    return db


@app.teardown_appcontext
def close_db(exc):
    db = getattr(g, "db", None)
    if db is not None:
        db.close()


def init_db():
    with _db_lock:
        db = sqlite3.connect(DB_PATH)
        db.execute("PRAGMA foreign_keys = ON")
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                login TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                display_name TEXT NOT NULL,
                avatar_url TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS chats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                is_direct INTEGER NOT NULL DEFAULT 1,
                title TEXT,
                created_by INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS chat_members (
                chat_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                last_read_at INTEGER NOT NULL DEFAULT 0,
                last_delivered_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (chat_id, user_id),
                FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id INTEGER NOT NULL,
                sender_id INTEGER NOT NULL,
                text TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'text',
                media_url TEXT,
                media_duration INTEGER NOT NULL DEFAULT 0,
                media_width INTEGER NOT NULL DEFAULT 0,
                media_height INTEGER NOT NULL DEFAULT 0,
                reply_to_message_id INTEGER,
                reply_to_sender_name TEXT,
                reply_to_text TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
                FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS direct_chats (
                user_a INTEGER NOT NULL,
                user_b INTEGER NOT NULL,
                chat_id INTEGER NOT NULL UNIQUE,
                PRIMARY KEY (user_a, user_b),
                FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS push_tokens (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                platform TEXT,
                device_name TEXT,
                app_version TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_push_tokens_user_id
                ON push_tokens(user_id);
            """
        )
        ensure_column(db, "chats", "title", "TEXT")
        ensure_column(db, "chats", "created_by", "INTEGER")
        ensure_column(db, "chats", "avatar_url", "TEXT")
        ensure_column(db, "chats", "description", "TEXT")
        ensure_column(db, "chat_members", "last_delivered_at", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "updated_at", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "kind", "TEXT NOT NULL DEFAULT 'text'")
        ensure_column(db, "messages", "media_url", "TEXT")
        ensure_column(db, "messages", "media_duration", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "media_width", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "media_height", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "reply_to_message_id", "INTEGER")
        ensure_column(db, "messages", "reply_to_sender_name", "TEXT")
        ensure_column(db, "messages", "reply_to_text", "TEXT")
        AVATAR_DIR.mkdir(parents=True, exist_ok=True)
        VOICE_DIR.mkdir(parents=True, exist_ok=True)
        PHOTO_DIR.mkdir(parents=True, exist_ok=True)
        VIDEO_DIR.mkdir(parents=True, exist_ok=True)
        UPDATE_DIR.mkdir(parents=True, exist_ok=True)
        db.commit()
        db.close()


def ensure_column(db: sqlite3.Connection, table: str, column: str, definition: str):
    rows = db.execute(f"PRAGMA table_info({table})").fetchall()
    existing = {row[1] for row in rows}
    if column not in existing:
        db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


def init_fcm() -> bool:
    global _fcm_initialized
    if _fcm_initialized:
        return True
    if firebase_admin is None or credentials is None or messaging is None:
        return False

    with _fcm_lock:
        if _fcm_initialized:
            return True

        existing = firebase_admin._apps  # type: ignore[attr-defined]
        if existing:
            _fcm_initialized = True
            return True

        service_account_json = os.getenv("FCM_SERVICE_ACCOUNT_JSON", "").strip()
        credential = None

        try:
            if service_account_json:
                payload = json.loads(service_account_json)
                credential = credentials.Certificate(payload)
            elif FCM_CREDENTIALS_PATH.exists():
                credential = credentials.Certificate(str(FCM_CREDENTIALS_PATH))
            else:
                app.logger.warning(
                    "FCM is disabled: set FCM_SERVICE_ACCOUNT_PATH or FCM_SERVICE_ACCOUNT_JSON."
                )
                return False

            firebase_admin.initialize_app(credential)
            _fcm_initialized = True
            return True
        except Exception as exc:  # pragma: no cover - network/env dependent
            app.logger.warning("FCM init failed: %s", exc)
            return False


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


def trim_push_tokens_for_user(db: sqlite3.Connection, user_id: int):
    rows = db.execute(
        """
        SELECT token
        FROM push_tokens
        WHERE user_id = ?
        ORDER BY updated_at DESC
        """,
        (user_id,),
    ).fetchall()

    if len(rows) <= MAX_PUSH_TOKENS_PER_USER:
        return

    stale = [str(row["token"]) for row in rows[MAX_PUSH_TOKENS_PER_USER:] if row["token"]]
    if not stale:
        return

    db.executemany("DELETE FROM push_tokens WHERE token = ?", [(token,) for token in stale])


def send_message_push_notifications(
    db: sqlite3.Connection,
    *,
    chat_id: int,
    sender_row: sqlite3.Row,
    message_text: str,
    message_type: str,
    message_id: int,
    message_ts: int,
):
    if not init_fcm():
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
        body = VOICE_MESSAGE_FALLBACK_TEXT
    elif message_type == "image":
        body = IMAGE_MESSAGE_FALLBACK_TEXT
    elif message_type == "video":
        body = VIDEO_MESSAGE_FALLBACK_TEXT
    if len(body) > 200:
        body = f"{body[:197]}..."

    for recipient_id, tokens in grouped_tokens.items():
        if not tokens:
            continue

        chat_title = get_chat_title_for_user(db, chat_id, recipient_id)
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

        multicast = messaging.MulticastMessage(
            tokens=tokens,
            data=data_payload,
            # Data-only payload keeps notification lifecycle fully under app control
            # (heads-up behavior, per-chat dismissal, and badge cleanup).
            android=messaging.AndroidConfig(priority="high"),
        )

        try:
            response = messaging.send_each_for_multicast(multicast)
        except Exception as exc:  # pragma: no cover - network/env dependent
            app.logger.warning("FCM send failed for chat %s: %s", chat_id, exc)
            continue

        stale_tokens: list[str] = []
        for index, item in enumerate(response.responses):
            if item.success:
                continue
            token = tokens[index] if index < len(tokens) else None
            if token and should_remove_push_token(item.exception):
                stale_tokens.append(token)

        if stale_tokens:
            db.executemany("DELETE FROM push_tokens WHERE token = ?", [(token,) for token in stale_tokens])
            db.commit()


def send_message_push_notifications_async(
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
            db = sqlite3.connect(DB_PATH)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys = ON")
            sender_row = db.execute(
                "SELECT id, login, display_name FROM users WHERE id = ?",
                (sender_id,),
            ).fetchone()
            if sender_row is None:
                return
            send_message_push_notifications(
                db,
                chat_id=chat_id,
                sender_row=sender_row,
                message_text=message_text,
                message_type=message_type,
                message_id=message_id,
                message_ts=message_ts,
            )
        except Exception as exc:  # pragma: no cover - background push is best effort
            app.logger.warning("Async push dispatch failed for message %s: %s", message_id, exc)
        finally:
            if db is not None:
                db.close()

    threading.Thread(
        target=worker,
        daemon=True,
        name=f"push-msg-{message_id}",
    ).start()


def hash_password(password: str, salt: str | None = None) -> str:
    if salt is None:
        salt = secrets.token_hex(16)
    digest = hashlib.sha256(f"{salt}:{password}".encode("utf-8")).hexdigest()
    return f"{salt}${digest}"


def verify_password(password: str, encoded: str) -> bool:
    try:
        salt, _ = encoded.split("$", 1)
    except ValueError:
        return False
    return hash_password(password, salt) == encoded


def normalize_login(login: str) -> str:
    return (login or "").strip().lower()


def normalize_avatar_url(value: str | None) -> str | None:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"{request.host_url.rstrip('/')}/uploads/avatars/{raw}"


def normalize_voice_url(value: str | None) -> str | None:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"{request.host_url.rstrip('/')}/uploads/voice/{raw}"


def normalize_photo_url(value: str | None) -> str | None:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"{request.host_url.rstrip('/')}/uploads/photos/{raw}"


def normalize_video_url(value: str | None) -> str | None:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"{request.host_url.rstrip('/')}/uploads/videos/{raw}"


def user_to_json(row: sqlite3.Row) -> dict:
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


def create_session(db: sqlite3.Connection, user_id: int) -> str:
    token = secrets.token_urlsafe(32)
    db.execute(
        "INSERT INTO sessions(token, user_id, created_at) VALUES(?, ?, ?)",
        (token, user_id, now_ms()),
    )
    return token


def auth_required(handler):
    @wraps(handler)
    def wrapper(*args, **kwargs):
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРІР‚С”Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ"}), 401

        token = header[7:].strip()
        db = get_db()
        row = db.execute(
            """
            SELECT u.*
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token = ?
            """,
            (token,),
        ).fetchone()

        if row is None:
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р’В Р В РІР‚в„–Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°"}), 401

        g.current_user = row
        g.token = token
        return handler(*args, **kwargs)

    return wrapper


def get_member_user_ids(db: sqlite3.Connection, chat_id: int) -> list[int]:
    rows = db.execute(
        "SELECT user_id FROM chat_members WHERE chat_id = ?",
        (chat_id,),
    ).fetchall()
    return [int(r["user_id"]) for r in rows]


def is_chat_member(db: sqlite3.Connection, chat_id: int, user_id: int) -> bool:
    row = db.execute(
        "SELECT 1 FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (chat_id, user_id),
    ).fetchone()
    return row is not None


def build_group_info_payload(db: sqlite3.Connection, chat_id: int) -> dict | None:
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
    members = [user_to_json(row) for row in member_rows]

    created_by_uid = None
    created_by_login = None
    created_by_id = parse_int(chat_row["created_by"], 0)
    if created_by_id > 0:
        created_by_row = db.execute(
            "SELECT id, login FROM users WHERE id = ? LIMIT 1",
            (created_by_id,),
        ).fetchone()
        if created_by_row is not None:
            created_by_uid = f"u{int(created_by_row['id'])}"
            created_by_login = normalize_login(created_by_row["login"])

    return {
        "group": {
            "id": str(chat_id),
            "title": str(chat_row["title"] or "").strip() or f"Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р РЋРЎв„ўР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° {chat_id}",
            "description": str(chat_row["description"] or "").strip(),
            "avatarUrl": normalize_avatar_url(chat_row["avatar_url"]),
            "createdByUid": created_by_uid,
            "createdByLogin": created_by_login,
        },
        "members": members,
    }


def get_chat_title_for_user(db: sqlite3.Connection, chat_id: int, user_id: int) -> str:
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


def build_chat_preview(db: sqlite3.Connection, chat_id: int, user_id: int) -> dict:
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
        avatar_url = normalize_avatar_url(chat_row["avatar_url"])
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
            avatar_url = normalize_avatar_url(peer_row["avatar_url"])

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
            "title": get_chat_title_for_user(db, chat_id, user_id),
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
            last_message_text = VOICE_MESSAGE_FALLBACK_TEXT
        elif last_kind == "image":
            last_message_text = IMAGE_MESSAGE_FALLBACK_TEXT
        elif last_kind == "video":
            last_message_text = VIDEO_MESSAGE_FALLBACK_TEXT

    return {
        "id": str(chat_id),
        "title": get_chat_title_for_user(db, chat_id, user_id),
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


def build_message_json(db: sqlite3.Connection, row: sqlite3.Row, chat_id: int) -> dict:
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
    image_width = 0
    image_height = 0
    video_url = None
    video_duration = 0
    if message_kind == "voice":
        voice_url = normalize_voice_url(row["media_url"])
        voice_duration = max(0, int(row["media_duration"] or 0))
        if not text.strip():
            text = VOICE_MESSAGE_FALLBACK_TEXT
    elif message_kind == "image":
        image_url = normalize_photo_url(row["media_url"])
        image_width = max(0, int(row["media_width"] or 0))
        image_height = max(0, int(row["media_height"] or 0))
        if not text.strip():
            text = IMAGE_MESSAGE_FALLBACK_TEXT
    elif message_kind == "video":
        video_url = normalize_video_url(row["media_url"])
        video_duration = max(0, int(row["media_duration"] or 0))
        if not text.strip():
            text = VIDEO_MESSAGE_FALLBACK_TEXT

    sender_avatar_url = None
    if "avatar_url" in row.keys():
        sender_avatar_url = normalize_avatar_url(row["avatar_url"])

    reply_to = None
    reply_to_message_id = parse_int(row["reply_to_message_id"], 0)
    if reply_to_message_id > 0:
        reply_sender_name = str(row["reply_to_sender_name"] or "").strip()
        reply_text = str(row["reply_to_text"] or "").strip()
        if not reply_text:
            reply_text = "..."
        reply_to = {
            "messageId": str(reply_to_message_id),
            "senderName": reply_sender_name,
            "text": reply_text,
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
        "imageWidth": image_width,
        "imageHeight": image_height,
        "videoUrl": video_url,
        "videoDurationSec": video_duration,
        "replyTo": reply_to,
    }

def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while True:
            chunk = source.read(1024 * 64)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def parse_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def load_update_feed() -> list[dict]:
    if not UPDATE_FEED_PATH.exists():
        return []

    try:
        payload = json.loads(UPDATE_FEED_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []

    history = payload.get("history", [])
    if not isinstance(history, list):
        return []

    normalized: list[dict] = []
    for raw in history:
        if not isinstance(raw, dict):
            continue

        version_code = parse_int(raw.get("versionCode"), 0)
        version_name = str(raw.get("versionName", "")).strip()
        if version_code <= 0 or not version_name:
            continue

        title = str(raw.get("title", "??????????")).strip() or "??????????"
        changes_raw = raw.get("changes", [])
        changes: list[str] = []
        if isinstance(changes_raw, list):
            for change in changes_raw:
                value = str(change).strip()
                if value:
                    changes.append(value)

        file_name_raw = raw.get("fileName")
        file_name = str(file_name_raw).strip() if file_name_raw is not None else ""
        sha_raw = raw.get("sha256")
        sha256 = str(sha_raw).strip() if sha_raw is not None else ""

        normalized.append(
            {
                "versionCode": version_code,
                "versionName": version_name,
                "title": title,
                "changes": changes,
                "publishedAt": parse_int(raw.get("publishedAt"), 0),
                "fileName": (file_name or None),
                "fileSize": parse_int(raw.get("fileSize"), 0),
                "sha256": (sha256 or None),
            }
        )

    normalized.sort(
        key=lambda item: (int(item["versionCode"]), int(item.get("publishedAt") or 0)),
        reverse=True,
    )
    return normalized


def build_update_entry(raw: dict) -> dict:
    file_name = raw.get("fileName")
    file_size = int(raw.get("fileSize") or 0)
    sha256 = raw.get("sha256")
    apk_url = None

    if file_name:
        file_path = UPDATE_DIR / str(file_name)
        if file_path.exists() and file_path.is_file():
            apk_url = f"{request.host_url.rstrip('/')}/updates/{file_name}"
            if file_size <= 0:
                file_size = file_path.stat().st_size
            if not sha256:
                sha256 = file_sha256(file_path)

    return {
        "versionCode": int(raw["versionCode"]),
        "versionName": str(raw["versionName"]),
        "title": str(raw.get("title") or "??????????"),
        "changes": list(raw.get("changes") or []),
        "publishedAt": int(raw.get("publishedAt") or 0),
        "fileName": file_name,
        "fileSize": file_size,
        "sha256": sha256,
        "apkUrl": apk_url,
    }


@app.get("/health")
def health():
    return jsonify({"status": "ok", "time": now_ms()})


@app.get("/uploads/avatars/<path:filename>")
def serve_avatar(filename: str):
    return send_from_directory(AVATAR_DIR, filename)


@app.get("/uploads/voice/<path:filename>")
def serve_voice(filename: str):
    return send_from_directory(VOICE_DIR, filename)


@app.get("/uploads/photos/<path:filename>")
def serve_photo(filename: str):
    return send_from_directory(PHOTO_DIR, filename)


@app.get("/uploads/videos/<path:filename>")
def serve_video(filename: str):
    return send_from_directory(VIDEO_DIR, filename)


@app.get("/updates/<path:filename>")
def serve_update_apk(filename: str):
    return send_from_directory(UPDATE_DIR, filename, as_attachment=True)


@app.get("/api/updates")
def updates_info():
    current_version_code = parse_int(request.args.get("currentVersionCode"), 0)
    history_feed = load_update_feed()
    history = [build_update_entry(item) for item in history_feed]
    latest = history[0] if history else None
    has_update = bool(latest and int(latest["versionCode"]) > current_version_code)
    return jsonify(
        {
            "latest": latest,
            "history": history,
            "hasUpdate": has_update,
            "currentVersionCode": current_version_code,
        }
    )


@app.post("/api/auth/register")
def register():
    data = request.get_json(silent=True) or {}
    login = normalize_login(data.get("login", ""))
    password = str(data.get("password", ""))
    display_name = str(data.get("displayName", "")).strip() or login

    if not login:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 400
    if len(password) < 6:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ 6 Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В "}), 400

    db = get_db()
    existing = db.execute("SELECT id FROM users WHERE login = ?", (login,)).fetchone()
    if existing:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р РЋРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ"}), 409

    ts = now_ms()
    cursor = db.execute(
        """
        INSERT INTO users(login, password_hash, display_name, avatar_url, created_at, updated_at)
        VALUES(?, ?, ?, NULL, ?, ?)
        """,
        (login, hash_password(password), display_name, ts, ts),
    )
    user_id = int(cursor.lastrowid)
    user = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()

    token = create_session(db, user_id)
    db.commit()

    return jsonify({"token": token, "user": user_to_json(user)})


@app.post("/api/auth/login")
def login():
    data = request.get_json(silent=True) or {}
    login_value = normalize_login(data.get("login", ""))
    password = str(data.get("password", ""))

    if not login_value or not password:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°"}), 400

    db = get_db()
    user = db.execute("SELECT * FROM users WHERE login = ?", (login_value,)).fetchone()
    if user is None or not verify_password(password, user["password_hash"]):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњР В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°"}), 401

    token = create_session(db, int(user["id"]))
    db.commit()

    return jsonify({"token": token, "user": user_to_json(user)})


@app.get("/api/auth/me")
@auth_required
def auth_me():
    return jsonify({"user": user_to_json(g.current_user)})


@app.post("/api/push/register")
@auth_required
def register_push_token():
    data = request.get_json(silent=True) or {}
    token = str(data.get("token", "")).strip()
    if not token:
        return jsonify({"error": "Push token is required"}), 400
    if len(token) < 16 or len(token) > 4096:
        return jsonify({"error": "Push token format is invalid"}), 400

    platform = str(data.get("platform", "android")).strip() or "android"
    device_name = str(data.get("deviceName", "")).strip()
    app_version = str(data.get("appVersion", "")).strip()

    db = get_db()
    user_id = int(g.current_user["id"])
    ts = now_ms()
    db.execute(
        """
        INSERT INTO push_tokens(token, user_id, platform, device_name, app_version, created_at, updated_at)
        VALUES(?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(token) DO UPDATE SET
            user_id = excluded.user_id,
            platform = excluded.platform,
            device_name = excluded.device_name,
            app_version = excluded.app_version,
            updated_at = excluded.updated_at
        """,
        (token, user_id, platform, device_name, app_version, ts, ts),
    )
    trim_push_tokens_for_user(db, user_id)
    db.commit()
    return jsonify({"ok": True})


@app.post("/api/push/unregister")
@auth_required
def unregister_push_token():
    data = request.get_json(silent=True) or {}
    token = str(data.get("token", "")).strip()

    db = get_db()
    user_id = int(g.current_user["id"])
    if token:
        db.execute(
            "DELETE FROM push_tokens WHERE token = ? AND user_id = ?",
            (token, user_id),
        )
    else:
        db.execute("DELETE FROM push_tokens WHERE user_id = ?", (user_id,))
    db.commit()
    return jsonify({"ok": True})


@app.get("/api/users")
@auth_required
def list_users():
    query = normalize_login(request.args.get("q", ""))
    db = get_db()
    current_user_id = int(g.current_user["id"])

    sql = "SELECT * FROM users"
    params: list[str] = []
    if query:
        like = f"%{query}%"
        sql += " WHERE login LIKE ? OR display_name LIKE ?"
        params = [like, like]
    sql += " ORDER BY display_name COLLATE NOCASE, login COLLATE NOCASE LIMIT 500"

    rows = db.execute(sql, tuple(params)).fetchall()
    items = [user_to_json(row) for row in rows if int(row["id"]) != current_user_id]
    return jsonify({"items": items})


@app.post("/api/profile")
@auth_required
def update_profile():
    data = request.get_json(silent=True) or {}
    display_name = str(data.get("displayName", "")).strip()
    if not display_name:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ"}), 400
    if len(display_name) > 64:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎвЂќР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ"}), 400

    db = get_db()
    user_id = int(g.current_user["id"])
    ts = now_ms()
    db.execute(
        "UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?",
        (display_name, ts, user_id),
    )
    db.commit()
    user = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    return jsonify({"user": user_to_json(user)})


@app.post("/api/profile/avatar")
@auth_required
def update_avatar():
    file = request.files.get("avatar")
    if file is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 400

    data = file.read()
    if not data:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІР‚С”Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»"}), 400
    if len(data) > 5 * 1024 * 1024:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ"}), 400

    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{secrets.token_hex(16)}.jpg"
    avatar_path = AVATAR_DIR / filename
    avatar_path.write_bytes(data)

    db = get_db()
    user_id = int(g.current_user["id"])
    old_row = db.execute("SELECT avatar_url FROM users WHERE id = ?", (user_id,)).fetchone()
    old_avatar = str(old_row["avatar_url"] or "").strip() if old_row else ""

    ts = now_ms()
    db.execute(
        "UPDATE users SET avatar_url = ?, updated_at = ? WHERE id = ?",
        (filename, ts, user_id),
    )
    db.commit()

    if old_avatar and "://" not in old_avatar:
        old_path = AVATAR_DIR / old_avatar
        if old_path.exists():
            try:
                old_path.unlink()
            except OSError:
                pass

    user = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    return jsonify({"user": user_to_json(user)})


@app.get("/api/chats")
@auth_required
def list_chats():
    db = get_db()
    user_id = int(g.current_user["id"])

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
    return jsonify({"items": previews})


@app.post("/api/chats/direct")
@auth_required
def create_direct_chat():
    data = request.get_json(silent=True) or {}
    target_login = normalize_login(data.get("login", ""))

    if not target_login:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°"}), 400

    db = get_db()
    current_user_id = int(g.current_user["id"])
    target_user = db.execute("SELECT * FROM users WHERE login = ?", (target_login,)).fetchone()
    if target_user is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 404

    target_user_id = int(target_user["id"])
    if target_user_id == current_user_id:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚Сљ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ"}), 400

    user_a, user_b = sorted([current_user_id, target_user_id])
    existing = db.execute(
        "SELECT chat_id FROM direct_chats WHERE user_a = ? AND user_b = ?",
        (user_a, user_b),
    ).fetchone()

    if existing:
        chat_id = int(existing["chat_id"])
    else:
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

    preview = build_chat_preview(db, chat_id, current_user_id)
    return jsonify({"chat": preview})


@app.post("/api/chats/group")
@auth_required
def create_group_chat():
    data = request.get_json(silent=True) or {}
    raw_title = str(data.get("title", "")).strip()
    raw_description = str(data.get("description", "")).strip()
    if not raw_title:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњ"}), 400
    if len(raw_title) > 64:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ"}), 400
    if len(raw_description) > 280:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎвЂќР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ"}), 400

    raw_members = data.get("members", [])
    if not isinstance(raw_members, list):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р’В Р В РІР‚в„–Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В  Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В"}), 400

    normalized_members: list[str] = []
    for item in raw_members:
        login = normalize_login(str(item))
        if login and login not in normalized_members:
            normalized_members.append(login)

    db = get_db()
    current_user_id = int(g.current_user["id"])
    current_user_login = normalize_login(g.current_user["login"])
    if current_user_login not in normalized_members:
        normalized_members.append(current_user_login)

    if len(normalized_members) < 2:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р РЋРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р вЂ™Р’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС› Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°"}), 400

    placeholders = ",".join(["?"] * len(normalized_members))
    users = db.execute(
        f"SELECT id, login FROM users WHERE login IN ({placeholders})",
        tuple(normalized_members),
    ).fetchall()
    found_logins = {normalize_login(row["login"]): int(row["id"]) for row in users}
    missing = [login for login in normalized_members if login not in found_logins]
    if missing:
        return jsonify({"error": f"Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњ: {', '.join(missing)}"}), 404

    ts = now_ms()
    chat_cursor = db.execute(
        "INSERT INTO chats(is_direct, title, description, created_by, created_at, updated_at) VALUES(0, ?, ?, ?, ?, ?)",
        (raw_title, raw_description, current_user_id, ts, ts),
    )
    chat_id = int(chat_cursor.lastrowid)

    member_ids = sorted(set(found_logins.values()))
    for user_id in member_ids:
        db.execute(
            "INSERT INTO chat_members(chat_id, user_id, last_read_at, last_delivered_at, created_at) VALUES(?, ?, 0, 0, ?)",
            (chat_id, user_id, ts),
        )

    db.commit()
    preview = build_chat_preview(db, chat_id, current_user_id)
    return jsonify({"chat": preview})


@app.post("/api/chats/<chat_id>/avatar")
@auth_required
def update_chat_avatar(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    chat_row = db.execute(
        "SELECT is_direct, avatar_url FROM chats WHERE id = ?",
        (parsed_chat_id,),
    ).fetchone()
    if chat_row is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В§Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 404
    if int(chat_row["is_direct"]) == 1:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ў Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС› Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РІР‚вЂњ"}), 400

    file = request.files.get("avatar")
    if file is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 400

    data = file.read()
    if not data:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІР‚С”Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»"}), 400
    if len(data) > 5 * 1024 * 1024:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ"}), 400

    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"chat_{parsed_chat_id}_{secrets.token_hex(12)}.jpg"
    avatar_path = AVATAR_DIR / filename
    avatar_path.write_bytes(data)

    old_avatar = str(chat_row["avatar_url"] or "").strip()
    ts = now_ms()
    db.execute(
        "UPDATE chats SET avatar_url = ?, updated_at = ? WHERE id = ?",
        (filename, ts, parsed_chat_id),
    )
    db.commit()

    if old_avatar and "://" not in old_avatar and old_avatar != filename:
        old_path = AVATAR_DIR / old_avatar
        if old_path.exists():
            try:
                old_path.unlink()
            except OSError:
                pass

    preview = build_chat_preview(db, parsed_chat_id, user_id)
    return jsonify({"chat": preview})


@app.get("/api/chats/<chat_id>/group-info")
@auth_required
def get_group_info(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    payload = build_group_info_payload(db, parsed_chat_id)
    if payload is None:
        row = db.execute("SELECT is_direct FROM chats WHERE id = ?", (parsed_chat_id,)).fetchone()
        if row is None:
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В§Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 404
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІР‚С”Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС› Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚Сњ"}), 400

    return jsonify(payload)


@app.post("/api/chats/<chat_id>/members")
@auth_required
def add_group_member(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    chat_row = db.execute(
        "SELECT is_direct FROM chats WHERE id = ?",
        (parsed_chat_id,),
    ).fetchone()
    if chat_row is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В§Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 404
    if int(chat_row["is_direct"]) == 1:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р РЋРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В  Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС› Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС› Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В  Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ"}), 400

    data = request.get_json(silent=True) or {}
    target_login = normalize_login(data.get("login", ""))
    if not target_login:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ"}), 400

    target_user = db.execute("SELECT * FROM users WHERE login = ?", (target_login,)).fetchone()
    if target_user is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 404

    target_user_id = int(target_user["id"])
    existing = db.execute(
        "SELECT 1 FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (parsed_chat_id, target_user_id),
    ).fetchone()
    if existing is not None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В·Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В  Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ"}), 409

    ts = now_ms()
    db.execute(
        "INSERT INTO chat_members(chat_id, user_id, last_read_at, last_delivered_at, created_at) VALUES(?, ?, ?, ?, ?)",
        (parsed_chat_id, target_user_id, ts, ts, ts),
    )
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (ts, parsed_chat_id),
    )
    db.commit()

    payload = build_group_info_payload(db, parsed_chat_id)
    if payload is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ"}), 500
    return jsonify(payload)



@app.post("/api/chats/<chat_id>/leave")
@auth_required
def leave_group(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    chat_row = db.execute(
        "SELECT is_direct, created_by FROM chats WHERE id = ?",
        (parsed_chat_id,),
    ).fetchone()
    if chat_row is None:
        return jsonify({"error": "Chat not found"}), 404
    if int(chat_row["is_direct"]) == 1:
        return jsonify({"error": "Direct chat cannot be left"}), 400
    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "You are not a member of this group"}), 403

    members_before = db.execute(
        "SELECT user_id FROM chat_members WHERE chat_id = ? ORDER BY created_at ASC, user_id ASC",
        (parsed_chat_id,),
    ).fetchall()
    if len(members_before) <= 1:
        db.execute("DELETE FROM chats WHERE id = ?", (parsed_chat_id,))
        db.commit()
        return jsonify({"left": True, "deleted": True})

    db.execute(
        "DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (parsed_chat_id, user_id),
    )

    replacement_creator_id = parse_int(chat_row["created_by"], 0)
    if replacement_creator_id == user_id:
        replacement_row = db.execute(
            "SELECT user_id FROM chat_members WHERE chat_id = ? ORDER BY created_at ASC, user_id ASC LIMIT 1",
            (parsed_chat_id,),
        ).fetchone()
        if replacement_row is None:
            db.execute("DELETE FROM chats WHERE id = ?", (parsed_chat_id,))
            db.commit()
            return jsonify({"left": True, "deleted": True})
        replacement_creator_id = int(replacement_row["user_id"])

    ts = now_ms()
    db.execute(
        "UPDATE chats SET created_by = ?, updated_at = ? WHERE id = ?",
        (replacement_creator_id if replacement_creator_id > 0 else None, ts, parsed_chat_id),
    )
    db.commit()
    return jsonify({"left": True, "deleted": False})


@app.delete("/api/chats/<chat_id>/members")
@auth_required
def remove_group_member(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    current_user_id = int(g.current_user["id"])

    chat_row = db.execute(
        "SELECT is_direct, created_by FROM chats WHERE id = ?",
        (parsed_chat_id,),
    ).fetchone()
    if chat_row is None:
        return jsonify({"error": "Chat not found"}), 404
    if int(chat_row["is_direct"]) == 1:
        return jsonify({"error": "Direct chat has no group members"}), 400
    if not is_chat_member(db, parsed_chat_id, current_user_id):
        return jsonify({"error": "You are not a member of this group"}), 403

    creator_id = parse_int(chat_row["created_by"], 0)
    if creator_id != current_user_id:
        return jsonify({"error": "Only group creator can remove members"}), 403

    data = request.get_json(silent=True) or {}
    target_login = normalize_login(data.get("login", ""))
    if not target_login:
        return jsonify({"error": "Target login is required"}), 400

    target_user = db.execute(
        "SELECT id, login FROM users WHERE login = ? LIMIT 1",
        (target_login,),
    ).fetchone()
    if target_user is None:
        return jsonify({"error": "User not found"}), 404

    target_user_id = int(target_user["id"])
    if target_user_id == creator_id:
        return jsonify({"error": "Creator should use leave action"}), 400

    existing = db.execute(
        "SELECT 1 FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (parsed_chat_id, target_user_id),
    ).fetchone()
    if existing is None:
        return jsonify({"error": "User is not in this group"}), 404

    db.execute(
        "DELETE FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (parsed_chat_id, target_user_id),
    )
    ts = now_ms()
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (ts, parsed_chat_id),
    )
    db.commit()

    payload = build_group_info_payload(db, parsed_chat_id)
    if payload is None:
        return jsonify({"error": "Failed to refresh group info"}), 500
    return jsonify(payload)

@app.get("/api/chats/<chat_id>/messages")
@auth_required
def list_messages(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    delivered_ts = now_ms()
    db.execute(
        "UPDATE chat_members SET last_delivered_at = ? WHERE chat_id = ? AND user_id = ?",
        (delivered_ts, parsed_chat_id, user_id),
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
            m.media_duration,
            m.media_width,
            m.media_height,
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
        (parsed_chat_id,),
    ).fetchall()

    items = [build_message_json(db, row, parsed_chat_id) for row in rows]
    return jsonify({"items": items})


@app.post("/api/chats/<chat_id>/messages")
@auth_required
def send_message(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    data = request.get_json(silent=True) or {}
    message_type = str(data.get("type", "text")).strip().lower()
    if message_type not in {"text", "voice", "image", "video"}:
        message_type = "text"

    text = str(data.get("text", "")).strip()
    media_url = None
    media_duration = 0
    media_width = 0
    media_height = 0
    reply_to_message_id = parse_int(data.get("replyToMessageId"), 0)
    reply_to_sender_name = None
    reply_to_text = None

    if message_type == "voice":
        raw_voice_url = str(data.get("voiceUrl", "")).strip()
        voice_file_name = Path(raw_voice_url).name if raw_voice_url else ""
        if not voice_file_name:
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400

        voice_path = VOICE_DIR / voice_file_name
        if not voice_path.exists() or not voice_path.is_file():
            return jsonify({"error": "????????? ???? ?? ??????"}), 404

        media_url = voice_file_name
        media_duration = max(0, min(parse_int(data.get("voiceDurationSec"), 0), MAX_VOICE_DURATION_SEC))
        if not text:
            text = VOICE_MESSAGE_FALLBACK_TEXT
    elif message_type == "image":
        raw_photo_url = str(data.get("imageUrl", "")).strip()
        photo_file_name = Path(raw_photo_url).name if raw_photo_url else ""
        if not photo_file_name:
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400

        photo_path = PHOTO_DIR / photo_file_name
        if not photo_path.exists() or not photo_path.is_file():
            return jsonify({"error": "????????? ???? ?? ??????"}), 404

        media_url = photo_file_name
        media_width = max(0, min(parse_int(data.get("imageWidth"), 0), 8192))
        media_height = max(0, min(parse_int(data.get("imageHeight"), 0), 8192))
        if not text:
            text = IMAGE_MESSAGE_FALLBACK_TEXT
    elif message_type == "video":
        raw_video_url = str(data.get("videoUrl", "")).strip()
        video_file_name = Path(raw_video_url).name if raw_video_url else ""
        if not video_file_name:
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400

        video_path = VIDEO_DIR / video_file_name
        if not video_path.exists() or not video_path.is_file():
            return jsonify({"error": "????????? ???? ?? ??????"}), 404

        media_url = video_file_name
        media_duration = max(0, min(parse_int(data.get("videoDurationSec"), 0), MAX_VIDEO_DURATION_SEC))
        if not text:
            text = VIDEO_MESSAGE_FALLBACK_TEXT
    else:
        if not text:
            return jsonify({"error": "???????????? ??????????????????"}), 400

    if reply_to_message_id > 0:
        reply_row = db.execute(
            """
            SELECT m.id, m.text, m.kind, u.display_name
            FROM messages m
            JOIN users u ON u.id = m.sender_id
            WHERE m.id = ? AND m.chat_id = ?
            LIMIT 1
            """,
            (reply_to_message_id, parsed_chat_id),
        ).fetchone()
        if reply_row is not None:
            reply_to_message_id = int(reply_row["id"])
            reply_to_sender_name = str(reply_row["display_name"] or "").strip()
            reply_kind = str(reply_row["kind"] or "text").strip().lower()
            if reply_kind == "voice":
                reply_to_text = VOICE_MESSAGE_FALLBACK_TEXT
            elif reply_kind == "image":
                reply_to_text = IMAGE_MESSAGE_FALLBACK_TEXT
            elif reply_kind == "video":
                reply_to_text = VIDEO_MESSAGE_FALLBACK_TEXT
            else:
                reply_to_text = str(reply_row["text"] or "").replace("\r", " ").replace("\n", " ").strip()
                if not reply_to_text:
                    reply_to_text = "..."
                if len(reply_to_text) > 170:
                    reply_to_text = f"{reply_to_text[:167]}..."
        else:
            reply_to_message_id = 0

    ts = now_ms()
    cursor = db.execute(
        """
        INSERT INTO messages(
            chat_id,
            sender_id,
            text,
            kind,
            media_url,
            media_duration,
            media_width,
            media_height,
            reply_to_message_id,
            reply_to_sender_name,
            reply_to_text,
            created_at,
            updated_at
        )
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        (
            parsed_chat_id,
            user_id,
            text,
            message_type,
            media_url,
            media_duration,
            media_width,
            media_height,
            reply_to_message_id if reply_to_message_id > 0 else None,
            reply_to_sender_name,
            reply_to_text,
            ts,
        ),
    )

    db.execute(
        "UPDATE chat_members SET last_read_at = ?, last_delivered_at = ? WHERE chat_id = ? AND user_id = ?",
        (ts, ts, parsed_chat_id, user_id),
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
        (ts, ts, parsed_chat_id, user_id),
    )

    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (ts, parsed_chat_id),
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
            m.media_duration,
            m.media_width,
            m.media_height,
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

    send_message_push_notifications_async(
        chat_id=parsed_chat_id,
        sender_id=user_id,
        message_text=text,
        message_type=message_type,
        message_id=message_id,
        message_ts=ts,
    )

    return jsonify({"message": build_message_json(db, row, parsed_chat_id)})


@app.put("/api/chats/<chat_id>/messages/<message_id>")
@auth_required
def edit_message(chat_id: str, message_id: str):
    parsed_chat_id = int(chat_id)
    parsed_message_id = int(message_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    message_row = db.execute(
        "SELECT sender_id, kind FROM messages WHERE id = ? AND chat_id = ?",
        (parsed_message_id, parsed_chat_id),
    ).fetchone()
    if message_row is None:
        return jsonify({"error": "?????????????????? ???? ??????????????"}), 404
    if int(message_row["sender_id"]) != user_id:
        return jsonify({"error": "?????????? ?????????????????????????? ???????????? ???????? ??????????????????"}), 403
    if str(message_row["kind"] or "text").strip().lower() != "text":
        return jsonify({"error": "????????? ????????? ?????? ?????????????"}), 400

    data = request.get_json(silent=True) or {}
    text = str(data.get("text", "")).strip()
    if not text:
        return jsonify({"error": "?????????? ?????????????????? ???? ?????????? ???????? ????????????"}), 400

    ts = now_ms()
    db.execute(
        "UPDATE messages SET text = ?, updated_at = ? WHERE id = ? AND chat_id = ?",
        (text, ts, parsed_message_id, parsed_chat_id),
    )
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (ts, parsed_chat_id),
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
            m.media_duration,
            m.media_width,
            m.media_height,
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
        (parsed_message_id, parsed_chat_id),
    ).fetchone()

    return jsonify({"message": build_message_json(db, row, parsed_chat_id)})


@app.delete("/api/chats/<chat_id>/messages/<message_id>")
@auth_required
def delete_message(chat_id: str, message_id: str):
    parsed_chat_id = int(chat_id)
    parsed_message_id = int(message_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    message_row = db.execute(
        "SELECT sender_id, kind, media_url FROM messages WHERE id = ? AND chat_id = ?",
        (parsed_message_id, parsed_chat_id),
    ).fetchone()
    if message_row is None:
        return jsonify({"error": "?????????????????? ???? ??????????????"}), 404
    if int(message_row["sender_id"]) != user_id:
        return jsonify({"error": "?????????? ?????????????? ???????????? ???????? ??????????????????"}), 403

    db.execute(
        "DELETE FROM messages WHERE id = ? AND chat_id = ?",
        (parsed_message_id, parsed_chat_id),
    )
    latest = db.execute(
        "SELECT MAX(created_at) AS ts FROM messages WHERE chat_id = ?",
        (parsed_chat_id,),
    ).fetchone()
    updated_at = int(latest["ts"]) if latest and latest["ts"] else now_ms()
    db.execute(
        "UPDATE chats SET updated_at = ? WHERE id = ?",
        (updated_at, parsed_chat_id),
    )
    db.commit()

    message_kind = str(message_row["kind"] or "text").strip().lower()
    if message_kind in {"voice", "image", "video"}:
        media_value = str(message_row["media_url"] or "").strip()
        if media_value and "://" not in media_value:
            if message_kind == "voice":
                media_dir = VOICE_DIR
            elif message_kind == "image":
                media_dir = PHOTO_DIR
            else:
                media_dir = VIDEO_DIR
            path = media_dir / media_value
            if path.exists():
                try:
                    path.unlink()
                except OSError:
                    pass

    return jsonify({"ok": True})


@app.post("/api/chats/<chat_id>/voice")
@auth_required
def upload_voice(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    file = request.files.get("voice")
    if file is None:
        return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400

    data = file.read()
    if not data:
        return jsonify({"error": "???????????? ????????"}), 400
    if len(data) > MAX_VOICE_FILE_BYTES:
        return jsonify({"error": "????????? ???? ??????? ???????"}), 400

    raw_suffix = Path(file.filename or "").suffix.lower()
    allowed_suffixes = {".m4a", ".aac", ".ogg", ".opus", ".mp3", ".wav", ".3gp"}
    suffix = raw_suffix if raw_suffix in allowed_suffixes else ".m4a"

    VOICE_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"voice_{parsed_chat_id}_{user_id}_{now_ms()}_{secrets.token_hex(8)}{suffix}"
    file_path = VOICE_DIR / filename
    file_path.write_bytes(data)

    return jsonify(
        {
            "voiceUrl": normalize_voice_url(filename),
            "fileName": filename,
            "fileSize": len(data),
        }
    )


@app.post("/api/chats/<chat_id>/photo")
@auth_required
def upload_photo(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    file = request.files.get("photo")
    if file is None:
        return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400

    data = file.read()
    if not data:
        return jsonify({"error": "???????????? ????????"}), 400
    if len(data) > MAX_PHOTO_FILE_BYTES:
        return jsonify({"error": "????????? ???? ??????? ???????"}), 400

    raw_suffix = Path(file.filename or "").suffix.lower()
    allowed_suffixes = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
    suffix = raw_suffix if raw_suffix in allowed_suffixes else ".jpg"

    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"photo_{parsed_chat_id}_{user_id}_{now_ms()}_{secrets.token_hex(8)}{suffix}"
    file_path = PHOTO_DIR / filename
    file_path.write_bytes(data)

    return jsonify(
        {
            "photoUrl": normalize_photo_url(filename),
            "fileName": filename,
            "fileSize": len(data),
        }
    )


@app.post("/api/chats/<chat_id>/video")
@auth_required
def upload_video(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    file = request.files.get("video")
    if file is None:
        return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400

    data = file.read()
    if not data:
        return jsonify({"error": "???????????? ????????"}), 400
    if len(data) > MAX_VIDEO_FILE_BYTES:
        return jsonify({"error": "????????? ???? ??????? ???????"}), 400

    raw_suffix = Path(file.filename or "").suffix.lower()
    allowed_suffixes = {".mp4", ".m4v", ".3gp", ".mov", ".webm", ".mkv"}
    suffix = raw_suffix if raw_suffix in allowed_suffixes else ".mp4"

    VIDEO_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"video_{parsed_chat_id}_{user_id}_{now_ms()}_{secrets.token_hex(8)}{suffix}"
    file_path = VIDEO_DIR / filename
    file_path.write_bytes(data)

    return jsonify(
        {
            "videoUrl": normalize_video_url(filename),
            "fileName": filename,
            "fileSize": len(data),
        }
    )


@app.post("/api/chats/<chat_id>/read")
@auth_required
def mark_read(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    payload = request.get_json(silent=True) or {}
    requested_read_up_to = parse_int(payload.get("readUpToTimestamp"), 0)
    ts = requested_read_up_to if requested_read_up_to > 0 else now_ms()
    if ts < 0:
        ts = 0

    member_row = db.execute(
        "SELECT last_read_at, last_delivered_at FROM chat_members WHERE chat_id = ? AND user_id = ?",
        (parsed_chat_id, user_id),
    ).fetchone()
    prev_last_read = parse_int(member_row["last_read_at"], 0) if member_row is not None else 0
    prev_last_delivered = parse_int(member_row["last_delivered_at"], 0) if member_row is not None else 0
    next_last_read = max(prev_last_read, ts)
    next_last_delivered = max(prev_last_delivered, ts)

    db.execute(
        "UPDATE chat_members SET last_read_at = ?, last_delivered_at = ? WHERE chat_id = ? AND user_id = ?",
        (next_last_read, next_last_delivered, parsed_chat_id, user_id),
    )
    db.commit()

    return jsonify(
        {
            "ok": True,
            "lastReadAt": next_last_read,
            "lastDeliveredAt": next_last_delivered,
        }
    )


if __name__ == "__main__":
    init_db()
    port = int(os.getenv("RESERV_PORT", "8080"))
    app.run(host="0.0.0.0", port=port, debug=False)




