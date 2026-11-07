package com.vibe.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vibe.tv.R
import com.vibe.tv.model.RepoConfig

class RepoListAdapter(
    private val onRemoveClick: (RepoConfig) -> Unit
) : RecyclerView.Adapter<RepoListAdapter.RepoViewHolder>() {

    private val repos = mutableListOf<RepoConfig>()

    fun submitList(newRepos: List<RepoConfig>) {
        repos.clear()
        repos.addAll(newRepos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RepoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_repo_row, parent, false)
        return RepoViewHolder(view)
    }

    override fun onBindViewHolder(holder: RepoViewHolder, position: Int) {
        holder.bind(repos[position], onRemoveClick)
    }

    override fun getItemCount(): Int = repos.size

    class RepoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_repo_name)
        private val tvUrl: TextView = itemView.findViewById(R.id.tv_repo_url)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_repo_meta)
        private val btnRemove: Button = itemView.findViewById(R.id.btn_remove_repo)

        fun bind(repo: RepoConfig, onRemove: (RepoConfig) -> Unit) {
            tvName.text = repo.name
            tvUrl.text = repo.url
            tvMeta.text = "${repo.repoType} • ${repo.pluginCount} indexed entries"

            if (repo.url.startsWith("builtin://")) {
                btnRemove.visibility = View.GONE
            } else {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener { onRemove(repo) }
            }
        }
    }
}
