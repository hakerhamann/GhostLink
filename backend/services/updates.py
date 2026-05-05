import hashlib
import ipaddress
import json
from pathlib import Path
from typing import Callable


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


def normalize_ip_address(value: str) -> str:
    candidate = str(value or "").strip()
    if not candidate:
        return ""
    try:
        return str(ipaddress.ip_address(candidate))
    except ValueError:
        return ""


def load_update_feed(feed_path: Path, normalize_login: Callable[[str], str]) -> list[dict]:
    if not feed_path.exists():
        return []

    try:
        payload = json.loads(feed_path.read_text(encoding="utf-8"))
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

        allowed_logins_raw = raw.get("targetLogins", raw.get("allowedLogins", []))
        blocked_logins_raw = raw.get("excludeLogins", [])
        allowed_ips_raw = raw.get("targetIps", raw.get("allowedIps", []))
        blocked_ips_raw = raw.get("excludeIps", [])
        allowed_logins: set[str] = set()
        blocked_logins: set[str] = set()
        allowed_ips: set[str] = set()
        blocked_ips: set[str] = set()
        if isinstance(allowed_logins_raw, list):
            for login_value in allowed_logins_raw:
                normalized_login = normalize_login(str(login_value))
                if normalized_login:
                    allowed_logins.add(normalized_login)
        if isinstance(blocked_logins_raw, list):
            for login_value in blocked_logins_raw:
                normalized_login = normalize_login(str(login_value))
                if normalized_login:
                    blocked_logins.add(normalized_login)
        if isinstance(allowed_ips_raw, list):
            for ip_value in allowed_ips_raw:
                normalized_ip = normalize_ip_address(str(ip_value))
                if normalized_ip:
                    allowed_ips.add(normalized_ip)
        if isinstance(blocked_ips_raw, list):
            for ip_value in blocked_ips_raw:
                normalized_ip = normalize_ip_address(str(ip_value))
                if normalized_ip:
                    blocked_ips.add(normalized_ip)

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
                "targetLogins": sorted(allowed_logins),
                "excludeLogins": sorted(blocked_logins),
                "targetIps": sorted(allowed_ips),
                "excludeIps": sorted(blocked_ips),
            }
        )

    normalized.sort(
        key=lambda item: (int(item["versionCode"]), int(item.get("publishedAt") or 0)),
        reverse=True,
    )
    return normalized


def is_update_visible_for_request(
    raw: dict,
    login: str,
    request_ip: str,
    normalize_login: Callable[[str], str],
) -> bool:
    safe_login = normalize_login(login)
    safe_ip = normalize_ip_address(request_ip)
    allowed_logins = {normalize_login(item) for item in (raw.get("targetLogins") or []) if normalize_login(item)}
    blocked_logins = {normalize_login(item) for item in (raw.get("excludeLogins") or []) if normalize_login(item)}
    allowed_ips = {normalize_ip_address(item) for item in (raw.get("targetIps") or []) if normalize_ip_address(item)}
    blocked_ips = {normalize_ip_address(item) for item in (raw.get("excludeIps") or []) if normalize_ip_address(item)}

    if allowed_logins or allowed_ips:
        login_allowed = bool(safe_login and safe_login in allowed_logins)
        ip_allowed = bool(safe_ip and safe_ip in allowed_ips)
        if not (login_allowed or ip_allowed):
            return False

    if blocked_logins and safe_login and safe_login in blocked_logins:
        return False
    if blocked_ips and safe_ip and safe_ip in blocked_ips:
        return False
    return True


def build_update_entry(raw: dict, update_dir: Path, public_base_url: str) -> dict:
    file_name = raw.get("fileName")
    file_size = int(raw.get("fileSize") or 0)
    sha256 = raw.get("sha256")
    apk_url = None

    if file_name:
        file_path = update_dir / str(file_name)
        if file_path.exists() and file_path.is_file():
            apk_url = f"{public_base_url.rstrip('/')}/updates/{file_name}"
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
