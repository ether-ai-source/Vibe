package com.vibe.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vibe.tv.R
import com.vibe.tv.core.RamImageLoader
import com.vibe.tv.model.MediaCardItem

class MediaCardAdapter(
    private val onItemFocused: (MediaCardItem) -> Unit,
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
        holder.bind(items[position], onItemFocused, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.iv_card_poster)
        private val tvBadge: TextView = itemView.findViewById(R.id.tv_card_badge)
        private val tvProvider: TextView = itemView.findViewById(R.id.tv_card_provider)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_card_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_card_subtitle)

        fun bind(
            item: MediaCardItem,
            onFocused: (MediaCardItem) -> Unit,
            onClick: (MediaCardItem) -> Unit
        ) {
            tvBadge.text = item.badge
            tvProvider.text = item.providerName
            tvTitle.text = item.title
            tvSubtitle.text = item.subtitle

            RamImageLoader.load(ivPoster, item.posterUrl, targetWidthPx = 420)

            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.07f else 1.0f
                v.elevation = if (hasFocus) 18f else 0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(140)
                    .start()
                if (hasFocus) {
                    onFocused(item)
                }
            }

            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
