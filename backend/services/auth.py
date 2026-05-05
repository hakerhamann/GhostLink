import hashlib
import secrets
import sqlite3


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


def create_session(db: sqlite3.Connection, user_id: int, created_at: int) -> str:
    token = secrets.token_urlsafe(32)
    db.execute(
        "INSERT INTO sessions(token, user_id, created_at) VALUES(?, ?, ?)",
        (token, user_id, created_at),
    )
    return token
