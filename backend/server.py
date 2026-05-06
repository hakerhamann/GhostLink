import json
import os
import secrets
import sqlite3
import threading
import time
from functools import wraps
from pathlib import Path

from flask import Flask, g, jsonify, request, send_from_directory
from db.schema import init_db as init_schema_db
from routes.auth import create_auth_blueprint
from routes.chat import create_chat_blueprint
from routes.profile import create_profile_blueprint
from routes.push import create_push_blueprint
from routes.uploads import create_uploads_blueprint
from routes.updates import create_updates_blueprint
from services.auth import create_session, hash_password, normalize_login, verify_password
from services.chat_creation import create_group_chat_record, ensure_direct_chat, update_chat_avatar_record
from services.chat_groups import ChatGroupService
from services.chat_lists import list_chat_previews
from services.chat_messages import (
    create_chat_message,
    delete_chat_message,
    list_chat_messages,
    resolve_reply_preview,
    update_chat_message,
)
from services.chat_read import mark_chat_read
from services.chat_serialization import ChatSerializationService
from services.media_formatting import (
    build_image_dimension_payload,
    normalize_int_list as normalize_int_list_value,
    normalize_media_urls as normalize_media_url_values,
    normalize_reply_preview_text as normalize_reply_preview_text_value,
    normalize_uploaded_url,
    parse_int as parse_int_value,
)
from services.push import PushService
from services.uploads import (
    UploadValidationError,
    remove_local_upload_if_present,
    save_avatar_upload,
    save_chat_avatar_upload,
    save_chat_media_upload,
)
from services.updates import (
    build_update_entry,
    is_update_visible_for_request,
    load_update_feed,
    normalize_ip_address,
)
from services.users import list_user_items
from services.users import parse_uid as parse_uid_value
from services.users import update_user_avatar, update_user_display_name
from services.users import user_to_json as user_to_json_value

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
    init_schema_db(
        db_path=DB_PATH,
        upload_dirs=(AVATAR_DIR, VOICE_DIR, PHOTO_DIR, VIDEO_DIR, UPDATE_DIR),
        lock=_db_lock,
    )


def normalize_avatar_url(value: str | None) -> str | None:
    return normalize_uploaded_url(value, base_url=request.host_url, upload_segment="avatars")


def normalize_voice_url(value: str | None) -> str | None:
    return normalize_uploaded_url(value, base_url=request.host_url, upload_segment="voice")


def normalize_photo_url(value: str | None) -> str | None:
    return normalize_uploaded_url(value, base_url=request.host_url, upload_segment="photos")


def normalize_media_urls(value, normalizer=None) -> list[str]:
    return normalize_media_url_values(value, normalizer)


def normalize_int_list(value) -> list[int]:
    return normalize_int_list_value(value)


def normalize_video_url(value: str | None) -> str | None:
    return normalize_uploaded_url(value, base_url=request.host_url, upload_segment="videos")


def user_to_json(row: sqlite3.Row) -> dict:
    return user_to_json_value(row, normalize_avatar_url=normalize_avatar_url)


def parse_uid(uid: str) -> int:
    return parse_uid_value(uid)


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
    return chat_group_service.get_member_user_ids(db, chat_id)


def is_chat_member(db: sqlite3.Connection, chat_id: int, user_id: int) -> bool:
    return chat_group_service.is_chat_member(db, chat_id, user_id)


def build_group_info_payload(db: sqlite3.Connection, chat_id: int) -> dict | None:
    return chat_group_service.build_group_info_payload(db, chat_id)


def get_chat_title_for_user(db: sqlite3.Connection, chat_id: int, user_id: int) -> str:
    return chat_group_service.get_chat_title_for_user(db, chat_id, user_id)


push_service = PushService(
    db_path=DB_PATH,
    credentials_path=FCM_CREDENTIALS_PATH,
    max_tokens_per_user=int(os.getenv("MAX_PUSH_TOKENS_PER_USER", "20")),
    voice_fallback_text=VOICE_MESSAGE_FALLBACK_TEXT,
    image_fallback_text=IMAGE_MESSAGE_FALLBACK_TEXT,
    video_fallback_text=VIDEO_MESSAGE_FALLBACK_TEXT,
    firebase_admin_module=firebase_admin,
    credentials_module=credentials,
    messaging_module=messaging,
    logger=app.logger,
    get_chat_title_for_user=get_chat_title_for_user,
)


app.register_blueprint(
    create_push_blueprint(
        auth_required=auth_required,
        get_db=get_db,
        now_ms=now_ms,
        push_service=push_service,
    )
)


def build_chat_preview(db: sqlite3.Connection, chat_id: int, user_id: int) -> dict:
    return chat_serializer.build_chat_preview(db, chat_id, user_id)


def build_message_json(db: sqlite3.Connection, row: sqlite3.Row, chat_id: int) -> dict:
    return chat_serializer.build_message_json(db, row, chat_id)

def parse_int(value, default: int = 0) -> int:
    return parse_int_value(value, default)


chat_group_service = ChatGroupService(
    user_to_json=user_to_json,
    normalize_avatar_url=normalize_avatar_url,
    normalize_login=normalize_login,
    parse_int=parse_int,
)


app.register_blueprint(
    create_updates_blueprint(
        get_db=get_db,
        normalize_login=normalize_login,
        normalize_ip_address=normalize_ip_address,
        parse_int=parse_int,
        load_update_feed=load_update_feed,
        is_update_visible_for_request=is_update_visible_for_request,
        build_update_entry=build_update_entry,
        update_feed_path=UPDATE_FEED_PATH,
        update_dir=UPDATE_DIR,
    )
)


def normalize_reply_preview_text(value) -> str:
    return normalize_reply_preview_text_value(value)


chat_serializer = ChatSerializationService(
    get_chat_title_for_user=get_chat_title_for_user,
    normalize_avatar_url=normalize_avatar_url,
    normalize_voice_url=normalize_voice_url,
    normalize_photo_url=normalize_photo_url,
    normalize_video_url=normalize_video_url,
    normalize_media_urls=normalize_media_urls,
    normalize_int_list=normalize_int_list,
    normalize_reply_preview_text=normalize_reply_preview_text,
    parse_int=parse_int,
    voice_fallback_text=VOICE_MESSAGE_FALLBACK_TEXT,
    image_fallback_text=IMAGE_MESSAGE_FALLBACK_TEXT,
    video_fallback_text=VIDEO_MESSAGE_FALLBACK_TEXT,
)


@app.get("/health")
def health():
    return jsonify({"status": "ok", "time": now_ms()})


def serve_avatar(filename: str):
    return send_from_directory(AVATAR_DIR, filename)


def serve_voice(filename: str):
    return send_from_directory(VOICE_DIR, filename)


def serve_photo(filename: str):
    return send_from_directory(PHOTO_DIR, filename)


def serve_video(filename: str):
    return send_from_directory(VIDEO_DIR, filename)


@app.get("/updates/<path:filename>")
def serve_update_apk(filename: str):
    return send_from_directory(UPDATE_DIR, filename, as_attachment=True)


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

    token = create_session(db, user_id, now_ms())
    db.commit()

    return jsonify({"token": token, "user": user_to_json(user)})


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

    token = create_session(db, int(user["id"]), now_ms())
    db.commit()

    return jsonify({"token": token, "user": user_to_json(user)})


def auth_me():
    return jsonify({"user": user_to_json(g.current_user)})


app.register_blueprint(
    create_auth_blueprint(
        auth_required=auth_required,
        register_handler=register,
        login_handler=login,
        me_handler=auth_me,
    )
)


def list_users():
    query = normalize_login(request.args.get("q", ""))
    db = get_db()
    current_user_id = int(g.current_user["id"])
    return jsonify(
        list_user_items(
            db,
            query=query,
            current_user_id=current_user_id,
            user_to_json_fn=user_to_json,
        )
    )


def update_profile():
    data = request.get_json(silent=True) or {}
    display_name = str(data.get("displayName", "")).strip()
    if not display_name:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ"}), 400
    if len(display_name) > 64:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎвЂќР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¶Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ"}), 400

    db = get_db()
    user_id = int(g.current_user["id"])
    user = update_user_display_name(
        db,
        user_id=user_id,
        display_name=display_name,
        updated_at=now_ms(),
    )
    return jsonify({"user": user_to_json(user)})


def update_avatar():
    try:
        upload = save_avatar_upload(
            file_storage=request.files.get("avatar"),
            upload_dir=AVATAR_DIR,
            token_factory=lambda: secrets.token_hex(16),
            max_bytes=5 * 1024 * 1024,
        )
    except UploadValidationError as exc:
        if exc.reason == "missing":
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 400
        if exc.reason == "empty":
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІР‚С”Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»"}), 400
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ"}), 400

    db = get_db()
    user_id = int(g.current_user["id"])
    user, old_avatar = update_user_avatar(
        db,
        user_id=user_id,
        avatar_file_name=upload.file_name,
        updated_at=now_ms(),
    )

    remove_local_upload_if_present(AVATAR_DIR, old_avatar)

    return jsonify({"user": user_to_json(user)})


app.register_blueprint(
    create_profile_blueprint(
        auth_required=auth_required,
        list_users=list_users,
        update_profile=update_profile,
        update_avatar=update_avatar,
    )
)


def list_chats():
    db = get_db()
    user_id = int(g.current_user["id"])
    return jsonify(
        list_chat_previews(db, user_id=user_id, build_chat_preview=build_chat_preview)
    )


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

    chat_id = ensure_direct_chat(
        db,
        current_user_id=current_user_id,
        target_user_id=target_user_id,
        now_ms=now_ms,
    )
    preview = build_chat_preview(db, chat_id, current_user_id)
    return jsonify({"chat": preview})


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

    chat_id = create_group_chat_record(
        db,
        title=raw_title,
        description=raw_description,
        current_user_id=current_user_id,
        member_ids=list(found_logins.values()),
        now_ms=now_ms,
    )
    preview = build_chat_preview(db, chat_id, current_user_id)
    return jsonify({"chat": preview})


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

    try:
        upload = save_chat_avatar_upload(
            file_storage=request.files.get("avatar"),
            upload_dir=AVATAR_DIR,
            chat_id=parsed_chat_id,
            token_factory=lambda: secrets.token_hex(12),
            max_bytes=5 * 1024 * 1024,
        )
    except UploadValidationError as exc:
        if exc.reason == "missing":
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦"}), 400
        if exc.reason == "empty":
            return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІР‚С”Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»"}), 400
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В¤Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В» Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р Р‹Р вЂ™Р’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р Р†Р вЂљРЎв„ўР вЂ™Р’В¬Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р вЂ Р Р†Р вЂљРЎвЂєР Р†Р вЂљРІР‚Сљ"}), 400

    old_avatar = str(chat_row["avatar_url"] or "").strip()
    timestamp = now_ms()
    actor_display_name = str(g.current_user["display_name"] or "").strip() or str(g.current_user["login"] or "").strip()
    update_chat_avatar_record(
        db,
        chat_id=parsed_chat_id,
        file_name=upload.file_name,
        updated_at=timestamp,
    )
    chat_group_service.create_avatar_changed_message(
        db,
        chat_id=parsed_chat_id,
        actor_user_id=user_id,
        actor_display_name=actor_display_name,
        avatar_file_name=upload.file_name,
        timestamp=timestamp,
    )
    db.commit()

    if old_avatar != upload.file_name:
        remove_local_upload_if_present(AVATAR_DIR, old_avatar)

    preview = build_chat_preview(db, parsed_chat_id, user_id)
    return jsonify({"chat": preview})


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

    chat_group_service.add_group_member_record(
        db,
        chat_id=parsed_chat_id,
        user_id=target_user_id,
        actor_user_id=user_id,
        actor_display_name=str(g.current_user["display_name"] or "").strip() or str(g.current_user["login"] or "").strip(),
        target_display_name=str(target_user["display_name"] or "").strip() or str(target_user["login"] or "").strip(),
        timestamp=now_ms(),
    )

    payload = build_group_info_payload(db, parsed_chat_id)
    if payload is None:
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р В РІР‚В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СљР В Р’В Р В Р вЂ№Р В Р’В Р Р†Р вЂљРЎв„ўР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’Вµ Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В±Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В»Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р вЂ™Р’В Р В Р’В Р Р†Р вЂљР’В¦Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљР’ВР В Р’В Р В Р вЂ№Р В Р’В Р В Р РЏ"}), 500
    return jsonify(payload)



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

    return jsonify(
        chat_group_service.leave_group_record(
            db,
            chat_id=parsed_chat_id,
            user_id=user_id,
            created_by=chat_row["created_by"],
            timestamp=now_ms(),
        )
    )


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
        "SELECT id, login, display_name FROM users WHERE login = ? LIMIT 1",
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

    chat_group_service.remove_group_member_record(
        db,
        chat_id=parsed_chat_id,
        target_user_id=target_user_id,
        actor_user_id=current_user_id,
        actor_display_name=str(g.current_user["display_name"] or "").strip() or str(g.current_user["login"] or "").strip(),
        target_display_name=str(target_user["display_name"] or "").strip() or str(target_user["login"] or "").strip(),
        timestamp=now_ms(),
    )

    payload = build_group_info_payload(db, parsed_chat_id)
    if payload is None:
        return jsonify({"error": "Failed to refresh group info"}), 500
    return jsonify(payload)

def list_messages(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    return jsonify(
        list_chat_messages(
            db,
            chat_id=parsed_chat_id,
            user_id=user_id,
            delivered_at=now_ms(),
            build_message_json=build_message_json,
        )
    )


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
    media_urls = None
    media_duration = 0
    media_width = 0
    media_height = 0
    media_widths = None
    media_heights = None
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
        raw_photo_urls = data.get("imageUrls")
        if isinstance(raw_photo_urls, list):
            photo_sources = [str(value).strip() for value in raw_photo_urls]
        else:
            raw_photo_url = str(data.get("imageUrl", "")).strip()
            photo_sources = [raw_photo_url] if raw_photo_url else []

        photo_file_names = [Path(value).name for value in photo_sources if value]
        photo_file_names = [value for value in photo_file_names if value]
        if not photo_file_names:
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400
        if len(photo_file_names) > 10:
            return jsonify({"error": "????????????? ?? 10 ????"}), 400

        missing_photo = next(
            (
                file_name
                for file_name in photo_file_names
                if not (PHOTO_DIR / file_name).exists() or not (PHOTO_DIR / file_name).is_file()
            ),
            None,
        )
        if missing_photo is not None:
            return jsonify({"error": "????????? ???? ?? ??????"}), 404

        media_url = photo_file_names[0]
        media_urls = json.dumps(photo_file_names, ensure_ascii=False)

        media_width, media_height, media_widths, media_heights = build_image_dimension_payload(
            data=data,
            item_count=len(photo_file_names),
            parse_int_fn=parse_int,
        )
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

    reply_to_message_id, reply_to_sender_name, reply_to_text = resolve_reply_preview(
        db,
        chat_id=parsed_chat_id,
        reply_to_message_id=reply_to_message_id,
        voice_fallback_text=VOICE_MESSAGE_FALLBACK_TEXT,
        image_fallback_text=IMAGE_MESSAGE_FALLBACK_TEXT,
        video_fallback_text=VIDEO_MESSAGE_FALLBACK_TEXT,
        normalize_reply_preview_text=normalize_reply_preview_text,
    )

    payload, message_id, ts = create_chat_message(
        db,
        chat_id=parsed_chat_id,
        user_id=user_id,
        text=text,
        message_type=message_type,
        media_url=media_url,
        media_urls=media_urls,
        media_duration=media_duration,
        media_width=media_width,
        media_height=media_height,
        media_widths=media_widths,
        media_heights=media_heights,
        reply_to_message_id=reply_to_message_id,
        reply_to_sender_name=reply_to_sender_name,
        reply_to_text=reply_to_text,
        timestamp=now_ms(),
        build_message_json=build_message_json,
    )

    push_service.send_message_push_notifications_async(
        chat_id=parsed_chat_id,
        sender_id=user_id,
        message_text=text,
        message_type=message_type,
        message_id=message_id,
        message_ts=ts,
    )

    return jsonify(payload)


def edit_message(chat_id: str, message_id: str):
    parsed_chat_id = int(chat_id)
    parsed_message_id = int(message_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    message_row = db.execute(
        """
        SELECT
            sender_id,
            kind,
            media_url,
            media_urls,
            media_width,
            media_height,
            media_widths,
            media_heights
        FROM messages
        WHERE id = ? AND chat_id = ?
        """,
        (parsed_message_id, parsed_chat_id),
    ).fetchone()
    if message_row is None:
        return jsonify({"error": "?????????????????? ???? ??????????????"}), 404
    if int(message_row["sender_id"]) != user_id:
        return jsonify({"error": "?????????? ?????????????????????????? ???????????? ???????? ??????????????????"}), 403
    message_kind = str(message_row["kind"] or "text").strip().lower()
    if message_kind not in {"text", "image"}:
        return jsonify({"error": "????????? ????????? ?????? ?????????????"}), 400

    data = request.get_json(silent=True) or {}
    text = str(data.get("text", "")).strip()
    media_url = message_row["media_url"]
    media_urls = message_row["media_urls"]
    media_width = max(0, int(message_row["media_width"] or 0))
    media_height = max(0, int(message_row["media_height"] or 0))
    media_widths = message_row["media_widths"]
    media_heights = message_row["media_heights"]

    if message_kind == "text" and not text:
        return jsonify({"error": "?????????? ?????????????????? ???? ?????????? ???????? ????????????"}), 400

    if message_kind == "image":
        raw_photo_urls = data.get("imageUrls")
        if isinstance(raw_photo_urls, list):
            photo_sources = [str(value).strip() for value in raw_photo_urls]
        else:
            raw_photo_url = str(data.get("imageUrl", "")).strip()
            photo_sources = [raw_photo_url] if raw_photo_url else []

        photo_sources = [value for value in photo_sources if value]
        if photo_sources:
            photo_file_names = [Path(value).name for value in photo_sources if value]
            if not photo_file_names:
                return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400
            if len(photo_file_names) > 10:
                return jsonify({"error": "????????????? ?? 10 ????"}), 400

            missing_photo = next(
                (
                    file_name
                    for file_name in photo_file_names
                    if not (PHOTO_DIR / file_name).exists() or not (PHOTO_DIR / file_name).is_file()
                ),
                None,
            )
            if missing_photo is not None:
                return jsonify({"error": "????????? ???? ?? ??????"}), 404

            media_url = photo_file_names[0]
            media_urls = json.dumps(photo_file_names, ensure_ascii=False)

            media_width, media_height, media_widths, media_heights = build_image_dimension_payload(
                data=data,
                item_count=len(photo_file_names),
                parse_int_fn=parse_int,
            )

    return jsonify(
        update_chat_message(
            db,
            chat_id=parsed_chat_id,
            message_id=parsed_message_id,
            text=text,
            media_url=media_url,
            media_urls=media_urls,
            media_width=media_width,
            media_height=media_height,
            media_widths=media_widths,
            media_heights=media_heights,
            updated_at=now_ms(),
            build_message_json=build_message_json,
        )
    )


def delete_message(chat_id: str, message_id: str):
    parsed_chat_id = int(chat_id)
    parsed_message_id = int(message_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    message_row = db.execute(
        "SELECT sender_id, kind, media_url, media_urls FROM messages WHERE id = ? AND chat_id = ?",
        (parsed_message_id, parsed_chat_id),
    ).fetchone()
    if message_row is None:
        return jsonify({"error": "?????????????????? ???? ??????????????"}), 404
    if int(message_row["sender_id"]) != user_id:
        return jsonify({"error": "?????????? ?????????????? ???????????? ???????? ??????????????????"}), 403

    return jsonify(
        delete_chat_message(
            db,
            chat_id=parsed_chat_id,
            message_id=parsed_message_id,
            message_row=message_row,
            voice_dir=VOICE_DIR,
            photo_dir=PHOTO_DIR,
            video_dir=VIDEO_DIR,
            now_ms=now_ms,
            normalize_media_urls=normalize_media_urls,
        )
    )


def upload_voice(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    try:
        upload = save_chat_media_upload(
            file_storage=request.files.get("voice"),
            upload_dir=VOICE_DIR,
            file_prefix="voice",
            chat_id=parsed_chat_id,
            user_id=user_id,
            timestamp=now_ms(),
            token_factory=lambda: secrets.token_hex(8),
            max_bytes=MAX_VOICE_FILE_BYTES,
            allowed_suffixes={".m4a", ".aac", ".ogg", ".opus", ".mp3", ".wav", ".3gp"},
            default_suffix=".m4a",
        )
    except UploadValidationError as exc:
        if exc.reason == "missing":
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400
        if exc.reason == "empty":
            return jsonify({"error": "???????????? ????????"}), 400
        return jsonify({"error": "????????? ???? ??????? ???????"}), 400

    return jsonify(
        {
            "voiceUrl": normalize_voice_url(upload.file_name),
            "fileName": upload.file_name,
            "fileSize": upload.file_size,
        }
    )


def upload_photo(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    try:
        upload = save_chat_media_upload(
            file_storage=request.files.get("photo"),
            upload_dir=PHOTO_DIR,
            file_prefix="photo",
            chat_id=parsed_chat_id,
            user_id=user_id,
            timestamp=now_ms(),
            token_factory=lambda: secrets.token_hex(8),
            max_bytes=MAX_PHOTO_FILE_BYTES,
            allowed_suffixes={".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"},
            default_suffix=".jpg",
        )
    except UploadValidationError as exc:
        if exc.reason == "missing":
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400
        if exc.reason == "empty":
            return jsonify({"error": "???????????? ????????"}), 400
        return jsonify({"error": "????????? ???? ??????? ???????"}), 400

    return jsonify(
        {
            "photoUrl": normalize_photo_url(upload.file_name),
            "fileName": upload.file_name,
            "fileSize": upload.file_size,
        }
    )


def upload_video(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "?????? ?????????????? ?? ????????"}), 403

    try:
        upload = save_chat_media_upload(
            file_storage=request.files.get("video"),
            upload_dir=VIDEO_DIR,
            file_prefix="video",
            chat_id=parsed_chat_id,
            user_id=user_id,
            timestamp=now_ms(),
            token_factory=lambda: secrets.token_hex(8),
            max_bytes=MAX_VIDEO_FILE_BYTES,
            allowed_suffixes={".mp4", ".m4v", ".3gp", ".mov", ".webm", ".mkv"},
            default_suffix=".mp4",
        )
    except UploadValidationError as exc:
        if exc.reason == "missing":
            return jsonify({"error": "???? ?????????? ????????? ?? ???????"}), 400
        if exc.reason == "empty":
            return jsonify({"error": "???????????? ????????"}), 400
        return jsonify({"error": "????????? ???? ??????? ???????"}), 400

    return jsonify(
        {
            "videoUrl": normalize_video_url(upload.file_name),
            "fileName": upload.file_name,
            "fileSize": upload.file_size,
        }
    )


app.register_blueprint(
    create_uploads_blueprint(
        auth_required=auth_required,
        serve_avatar=serve_avatar,
        serve_voice=serve_voice,
        serve_photo=serve_photo,
        serve_video=serve_video,
        upload_voice=upload_voice,
        upload_photo=upload_photo,
        upload_video=upload_video,
    )
)


def mark_read(chat_id: str):
    parsed_chat_id = int(chat_id)
    db = get_db()
    user_id = int(g.current_user["id"])

    if not is_chat_member(db, parsed_chat_id, user_id):
        return jsonify({"error": "Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎв„ўР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’ВµР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћ Р В Р’В Р вЂ™Р’В Р В РЎС›Р Р†Р вЂљР’ВР В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎС›Р В Р’В Р В Р вЂ№Р В Р’В Р РЋРІР‚СљР В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРІР‚СњР В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В° Р В Р’В Р вЂ™Р’В Р В Р Р‹Р Р†Р вЂљРЎСљ Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р В Р вЂ№Р В Р’В Р вЂ™Р’В Р В РІР‚в„ўР вЂ™Р’В°Р В Р’В Р В Р вЂ№Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В Р’В Р В Р вЂ№Р В Р Р‹Р Р†Р вЂљРЎС™"}), 403

    payload = request.get_json(silent=True) or {}
    return jsonify(
        mark_chat_read(
            db,
            chat_id=parsed_chat_id,
            user_id=user_id,
            requested_read_up_to=payload.get("readUpToTimestamp"),
            now_ms=now_ms,
            parse_int=parse_int,
        )
    )


app.register_blueprint(
    create_chat_blueprint(
        auth_required=auth_required,
        list_chats=list_chats,
        create_direct_chat=create_direct_chat,
        create_group_chat=create_group_chat,
        update_chat_avatar=update_chat_avatar,
        get_group_info=get_group_info,
        add_group_member=add_group_member,
        leave_group=leave_group,
        remove_group_member=remove_group_member,
        list_messages=list_messages,
        send_message=send_message,
        edit_message=edit_message,
        delete_message=delete_message,
        mark_read=mark_read,
    )
)


if __name__ == "__main__":
    init_db()
    port = int(os.getenv("RESERV_PORT", "8080"))
    app.run(host="0.0.0.0", port=port, debug=False)

