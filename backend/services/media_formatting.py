import json
from collections.abc import Callable


def normalize_uploaded_url(value: str | None, *, base_url: str, upload_segment: str) -> str | None:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"{base_url.rstrip('/')}/uploads/{upload_segment}/{raw}"


def parse_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def normalize_media_urls(value, normalizer: Callable[[str], str | None] | None = None) -> list[str]:
    if value is None:
        return []
    raw = value
    if isinstance(raw, str):
        raw = raw.strip()
        if not raw:
            return []
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = [raw]
    elif isinstance(raw, (list, tuple)):
        parsed = list(raw)
    else:
        return []

    urls = []
    for item in parsed:
        normalized = str(item).strip()
        if not normalized:
            continue
        if normalizer is not None:
            normalized = normalizer(normalized)
        if normalized:
            urls.append(normalized)
    return urls


def normalize_int_list(value) -> list[int]:
    if value is None:
        return []
    raw = value
    if isinstance(raw, str):
        raw = raw.strip()
        if not raw:
            return []
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = [raw]
    elif isinstance(raw, (list, tuple)):
        parsed = list(raw)
    else:
        return []

    return [max(0, parse_int(item, 0)) for item in parsed]


def build_image_dimension_payload(
    *,
    data: dict,
    item_count: int,
    parse_int_fn: Callable,
) -> tuple[int, int, str, str]:
    raw_image_widths = data.get("imageWidths")
    raw_image_heights = data.get("imageHeights")
    if isinstance(raw_image_widths, list):
        width_values = [max(0, min(parse_int_fn(value, 0), 8192)) for value in raw_image_widths]
    else:
        width_values = [max(0, min(parse_int_fn(data.get("imageWidth"), 0), 8192))]
    if isinstance(raw_image_heights, list):
        height_values = [max(0, min(parse_int_fn(value, 0), 8192)) for value in raw_image_heights]
    else:
        height_values = [max(0, min(parse_int_fn(data.get("imageHeight"), 0), 8192))]

    while len(width_values) < item_count:
        width_values.append(0)
    while len(height_values) < item_count:
        height_values.append(0)

    media_width = width_values[0] if width_values else 0
    media_height = height_values[0] if height_values else 0
    media_widths = json.dumps(width_values[:item_count], ensure_ascii=False)
    media_heights = json.dumps(height_values[:item_count], ensure_ascii=False)
    return media_width, media_height, media_widths, media_heights


def normalize_reply_preview_text(value) -> str:
    text = str(value or "").replace("\r\n", "\n").replace("\r", "\n").strip()
    if len(text) > 170:
        return f"{text[:167]}..."
    return text
