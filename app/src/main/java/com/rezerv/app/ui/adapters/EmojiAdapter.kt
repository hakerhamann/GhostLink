package com.rezerv.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rezerv.app.databinding.ItemEmojiBinding

class EmojiAdapter(
    private val onEmojiClick: (String) -> Unit
) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return EmojiViewHolder(ItemEmojiBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        holder.bind(items[position], onEmojiClick)
    }

    override fun getItemCount(): Int = items.size

    class EmojiViewHolder(
        private val binding: ItemEmojiBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(emoji: String, onEmojiClick: (String) -> Unit) {
            binding.tvEmoji.text = emoji
            binding.root.setOnClickListener { onEmojiClick(emoji) }
        }
    }
}
