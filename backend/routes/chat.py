from flask import Blueprint


def create_chat_blueprint(
    *,
    auth_required,
    list_chats,
    create_direct_chat,
    create_group_chat,
    update_chat_avatar,
    get_group_info,
    add_group_member,
    leave_group,
    remove_group_member,
    list_messages,
    send_message,
    edit_message,
    delete_message,
    mark_read,
) -> Blueprint:
    chat_bp = Blueprint("chat", __name__)
    protected = auth_required
    chat_bp.add_url_rule("/api/chats", view_func=protected(list_chats), methods=["GET"])
    chat_bp.add_url_rule("/api/chats/direct", view_func=protected(create_direct_chat), methods=["POST"])
    chat_bp.add_url_rule("/api/chats/group", view_func=protected(create_group_chat), methods=["POST"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/avatar", view_func=protected(update_chat_avatar), methods=["POST"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/group-info", view_func=protected(get_group_info), methods=["GET"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/members", view_func=protected(add_group_member), methods=["POST"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/leave", view_func=protected(leave_group), methods=["POST"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/members", view_func=protected(remove_group_member), methods=["DELETE"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/messages", view_func=protected(list_messages), methods=["GET"])
    chat_bp.add_url_rule("/api/chats/<chat_id>/messages", view_func=protected(send_message), methods=["POST"])
    chat_bp.add_url_rule(
        "/api/chats/<chat_id>/messages/<message_id>",
        view_func=protected(edit_message),
        methods=["PUT"],
    )
    chat_bp.add_url_rule(
        "/api/chats/<chat_id>/messages/<message_id>",
        view_func=protected(delete_message),
        methods=["DELETE"],
    )
    chat_bp.add_url_rule("/api/chats/<chat_id>/read", view_func=protected(mark_read), methods=["POST"])
    return chat_bp
