package com.vibe.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vibe.tv.R
import com.vibe.tv.model.MediaCardItem

class MediaCardAdapter(
    private val onItemClick: (MediaCardItem) -> Unit
) : RecyclerView.Adapter<MediaCardAdapter.CardViewHolder>() {

    private val items = mutableListOf<MediaCardItem>()

    fun submitList(newItems: List<MediaCardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBadge: TextView = itemView.findViewById(R.id.tv_card_badge)
        private val tvProvider: TextView = itemView.findViewById(R.id.tv_card_provider)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_card_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_card_subtitle)

        fun bind(item: MediaCardItem, onClick: (MediaCardItem) -> Unit) {
            tvBadge.text = item.badge
            tvProvider.text = item.providerName
            tvTitle.text = item.title
            tvSubtitle.text = item.subtitle

            // Smooth 10-foot Fire TV D-Pad focus scale animation
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(130)
                    .start()
            }

            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
