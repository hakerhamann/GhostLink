from flask import Blueprint, g, jsonify, request


def create_push_blueprint(*, auth_required, get_db, now_ms, push_service) -> Blueprint:
    push_bp = Blueprint("push", __name__)

    @push_bp.post("/api/push/register")
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
        push_service.trim_push_tokens_for_user(db, user_id)
        db.commit()
        return jsonify({"ok": True})

    @push_bp.post("/api/push/unregister")
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

    return push_bp
