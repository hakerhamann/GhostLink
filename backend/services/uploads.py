from dataclasses import dataclass
from pathlib import Path
from typing import Callable


class UploadValidationError(ValueError):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class StoredUpload:
    file_name: str
    file_size: int


def save_chat_media_upload(
    *,
    file_storage,
    upload_dir: Path,
    file_prefix: str,
    chat_id: int,
    user_id: int,
    timestamp: int,
    token_factory: Callable[[], str],
    max_bytes: int,
    allowed_suffixes: set[str],
    default_suffix: str,
) -> StoredUpload:
    if file_storage is None:
        raise UploadValidationError("missing")

    data = file_storage.read()
    if not data:
        raise UploadValidationError("empty")
    if len(data) > max_bytes:
        raise UploadValidationError("too_large")

    raw_suffix = Path(file_storage.filename or "").suffix.lower()
    suffix = raw_suffix if raw_suffix in allowed_suffixes else default_suffix

    upload_dir.mkdir(parents=True, exist_ok=True)
    file_name = f"{file_prefix}_{chat_id}_{user_id}_{timestamp}_{token_factory()}{suffix}"
    file_path = upload_dir / file_name
    file_path.write_bytes(data)

    return StoredUpload(file_name=file_name, file_size=len(data))


def save_avatar_upload(
    *,
    file_storage,
    upload_dir: Path,
    token_factory: Callable[[], str],
    max_bytes: int,
) -> StoredUpload:
    if file_storage is None:
        raise UploadValidationError("missing")

    data = file_storage.read()
    if not data:
        raise UploadValidationError("empty")
    if len(data) > max_bytes:
        raise UploadValidationError("too_large")

    upload_dir.mkdir(parents=True, exist_ok=True)
    file_name = f"{token_factory()}.jpg"
    file_path = upload_dir / file_name
    file_path.write_bytes(data)

    return StoredUpload(file_name=file_name, file_size=len(data))


def save_chat_avatar_upload(
    *,
    file_storage,
    upload_dir: Path,
    chat_id: int,
    token_factory: Callable[[], str],
    max_bytes: int,
) -> StoredUpload:
    if file_storage is None:
        raise UploadValidationError("missing")

    data = file_storage.read()
    if not data:
        raise UploadValidationError("empty")
    if len(data) > max_bytes:
        raise UploadValidationError("too_large")

    upload_dir.mkdir(parents=True, exist_ok=True)
    file_name = f"chat_{chat_id}_{token_factory()}.jpg"
    file_path = upload_dir / file_name
    file_path.write_bytes(data)

    return StoredUpload(file_name=file_name, file_size=len(data))


def remove_local_upload_if_present(upload_dir: Path, file_name: str):
    old_name = str(file_name or "").strip()
    if not old_name or "://" in old_name:
        return

    old_path = upload_dir / old_name
    if old_path.exists():
        try:
            old_path.unlink()
        except OSError:
            pass
