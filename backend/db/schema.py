import sqlite3
from pathlib import Path
from threading import Lock
from typing import Iterable


def init_db(db_path: Path, upload_dirs: Iterable[Path], lock: Lock):
    with lock:
        db = sqlite3.connect(db_path)
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
                media_urls TEXT,
                media_duration INTEGER NOT NULL DEFAULT 0,
                media_width INTEGER NOT NULL DEFAULT 0,
                media_height INTEGER NOT NULL DEFAULT 0,
                media_widths TEXT,
                media_heights TEXT,
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
        ensure_column(db, "messages", "media_urls", "TEXT")
        ensure_column(db, "messages", "media_duration", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "media_width", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "media_height", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(db, "messages", "media_widths", "TEXT")
        ensure_column(db, "messages", "media_heights", "TEXT")
        ensure_column(db, "messages", "reply_to_message_id", "INTEGER")
        ensure_column(db, "messages", "reply_to_sender_name", "TEXT")
        ensure_column(db, "messages", "reply_to_text", "TEXT")
        for path in upload_dirs:
            path.mkdir(parents=True, exist_ok=True)
        db.commit()
        db.close()


def ensure_column(db: sqlite3.Connection, table: str, column: str, definition: str):
    rows = db.execute(f"PRAGMA table_info({table})").fetchall()
    existing = {row[1] for row in rows}
    if column not in existing:
        db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
