from flask import Blueprint


def create_auth_blueprint(*, auth_required, register_handler, login_handler, me_handler) -> Blueprint:
    auth_bp = Blueprint("auth", __name__)
    auth_bp.add_url_rule("/api/auth/register", view_func=register_handler, methods=["POST"])
    auth_bp.add_url_rule("/api/auth/login", view_func=login_handler, methods=["POST"])
    auth_bp.add_url_rule("/api/auth/me", view_func=auth_required(me_handler), methods=["GET"])
    return auth_bp
