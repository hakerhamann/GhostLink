from flask import Blueprint, jsonify, request


def create_updates_blueprint(
    *,
    get_db,
    normalize_login,
    normalize_ip_address,
    parse_int,
    load_update_feed,
    is_update_visible_for_request,
    build_update_entry,
    update_feed_path,
    update_dir,
) -> Blueprint:
    updates_bp = Blueprint("updates_api", __name__)

    def resolve_update_request_login() -> str:
        header = request.headers.get("Authorization", "")
        if header.startswith("Bearer "):
            token = header[7:].strip()
            if token:
                row = get_db().execute(
                    """
                    SELECT u.login
                    FROM sessions s
                    JOIN users u ON u.id = s.user_id
                    WHERE s.token = ?
                    """,
                    (token,),
                ).fetchone()
                if row is not None:
                    return normalize_login(row["login"])

        return normalize_login(request.args.get("login", ""))

    def resolve_update_request_ip() -> str:
        return normalize_ip_address(request.remote_addr or "")

    @updates_bp.get("/api/updates")
    def updates_info():
        current_version_code = parse_int(request.args.get("currentVersionCode"), 0)
        request_login = resolve_update_request_login()
        request_ip = resolve_update_request_ip()
        history_feed = [
            item
            for item in load_update_feed(update_feed_path, normalize_login)
            if is_update_visible_for_request(item, request_login, request_ip, normalize_login)
        ]
        history = [build_update_entry(item, update_dir, request.host_url) for item in history_feed]
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

    return updates_bp
