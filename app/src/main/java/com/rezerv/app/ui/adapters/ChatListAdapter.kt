package com.rezerv.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rezerv.app.data.model.ChatPreview
import com.rezerv.app.databinding.ItemChatBinding
import com.rezerv.app.util.AvatarLoader
import com.rezerv.app.util.Formatters

class ChatListAdapter(
    private val onClick: (ChatPreview) -> Unit,
    private val onLongClick: (ChatPreview) -> Unit,
    private val onAvatarClick: (ChatPreview) -> Unit,
    private val onNameClick: (ChatPreview) -> Unit,
    private val isPinned: (ChatPreview) -> Boolean
) : ListAdapter<ChatPreview, ChatListAdapter.ChatViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ChatViewHolder(ItemChatBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(
            item = getItem(position),
            onClick = onClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick,
            onNameClick = onNameClick,
            isPinned = isPinned(getItem(position))
        )
    }

    class ChatViewHolder(
        private val binding: ItemChatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: ChatPreview,
            onClick: (ChatPreview) -> Unit,
            onLongClick: (ChatPreview) -> Unit,
            onAvatarClick: (ChatPreview) -> Unit,
            onNameClick: (ChatPreview) -> Unit,
            isPinned: Boolean
        ) {
            AvatarLoader.bind(
                imageView = binding.ivAvatar,
                fallbackView = binding.tvAvatarFallback,
                displayName = item.title,
                avatarUrl = item.avatarUrl
            )
            binding.tvName.text = if (item.isGroup) "${item.title} (${item.memberCount})" else item.title
            val preview = if (item.lastMessage.isBlank() && item.isGroup) {
                "Группа создана"
            } else {
                item.lastMessage
            }
            binding.tvMessage.text = preview
            binding.tvDate.text = Formatters.formatChatDate(item.timestamp)
            binding.tvPinned.isVisible = isPinned

            binding.tvStatus.isVisible = item.lastMessageOutgoing
            if (item.lastMessageOutgoing) {
                binding.tvStatus.text = when {
                    item.lastMessageRead -> "✓✓"
                    item.lastMessageDelivered -> "✓✓"
                    else -> "✓"
                }
                val color = if (item.lastMessageRead) 0xFF89FF3AL else 0xFF93A994L
                binding.tvStatus.setTextColor(color.toInt())
            }

            binding.tvUnread.isVisible = item.unreadCount > 0
            binding.tvUnread.text = item.unreadCount.toString()

            binding.avatarContainer.setOnClickListener { onAvatarClick(item) }
            binding.ivAvatar.setOnClickListener { onAvatarClick(item) }
            binding.tvAvatarFallback.setOnClickListener { onAvatarClick(item) }
            if (item.isGroup) {
                binding.tvName.setOnClickListener { onClick(item) }
            } else {
                binding.tvName.setOnClickListener { onNameClick(item) }
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatPreview>() {
        override fun areItemsTheSame(oldItem: ChatPreview, newItem: ChatPreview): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatPreview, newItem: ChatPreview): Boolean {
            return oldItem == newItem
        }
    }
}
