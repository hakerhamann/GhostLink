from flask import Blueprint


def create_profile_blueprint(*, auth_required, list_users, update_profile, update_avatar) -> Blueprint:
    profile_bp = Blueprint("profile", __name__)
    profile_bp.add_url_rule("/api/users", view_func=auth_required(list_users), methods=["GET"])
    profile_bp.add_url_rule("/api/profile", view_func=auth_required(update_profile), methods=["POST"])
    profile_bp.add_url_rule("/api/profile/avatar", view_func=auth_required(update_avatar), methods=["POST"])
    return profile_bp
