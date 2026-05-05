from flask import Blueprint


def create_uploads_blueprint(
    *,
    auth_required,
    serve_avatar,
    serve_voice,
    serve_photo,
    serve_video,
    upload_voice,
    upload_photo,
    upload_video,
) -> Blueprint:
    uploads_bp = Blueprint("uploads", __name__)
    uploads_bp.add_url_rule("/uploads/avatars/<path:filename>", view_func=serve_avatar, methods=["GET"])
    uploads_bp.add_url_rule("/uploads/voice/<path:filename>", view_func=serve_voice, methods=["GET"])
    uploads_bp.add_url_rule("/uploads/photos/<path:filename>", view_func=serve_photo, methods=["GET"])
    uploads_bp.add_url_rule("/uploads/videos/<path:filename>", view_func=serve_video, methods=["GET"])
    uploads_bp.add_url_rule("/api/chats/<chat_id>/voice", view_func=auth_required(upload_voice), methods=["POST"])
    uploads_bp.add_url_rule("/api/chats/<chat_id>/photo", view_func=auth_required(upload_photo), methods=["POST"])
    uploads_bp.add_url_rule("/api/chats/<chat_id>/video", view_func=auth_required(upload_video), methods=["POST"])
    return uploads_bp
