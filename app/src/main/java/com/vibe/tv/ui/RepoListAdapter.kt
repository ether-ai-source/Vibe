package com.vibe.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vibe.tv.R
import com.vibe.tv.model.Cs3PluginMeta
import com.vibe.tv.model.RepoConfig

/**
 * Dual-mode adapter for the Repositories screen:
 * Mode 1: Displays installed Repositories (with "Browse / Install" to view extensions inside).
 * Mode 2: Displays all Extensions/Sub-Repos inside a selected Repository so user can install/open them.
 */
class RepoListAdapter(
    private val onOpenRepoExtensions: (RepoConfig) -> Unit,
    private val onRemoveRepoClick: (RepoConfig) -> Unit,
    private val onToggleExtensionClick: (Cs3PluginMeta) -> Unit
) : RecyclerView.Adapter<RepoListAdapter.RowViewHolder>() {

    private val repos = mutableListOf<RepoConfig>()
    private val extensions = mutableListOf<Cs3PluginMeta>()
    private var showingExtensionsMode = false

    fun submitRepos(newRepos: List<RepoConfig>) {
        showingExtensionsMode = false
        repos.clear()
        repos.addAll(newRepos)
        notifyDataSetChanged()
    }

    fun submitExtensions(newExtensions: List<Cs3PluginMeta>) {
        showingExtensionsMode = true
        extensions.clear()
        extensions.addAll(newExtensions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_repo_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        if (showingExtensionsMode) {
            holder.bindExtension(extensions[position], onToggleExtensionClick)
        } else {
            holder.bindRepo(repos[position], onOpenRepoExtensions, onRemoveRepoClick)
        }
    }

    override fun getItemCount(): Int = if (showingExtensionsMode) extensions.size else repos.size

    class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_repo_name)
        private val tvUrl: TextView = itemView.findViewById(R.id.tv_repo_url)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_repo_meta)
        private val btnOpen: Button = itemView.findViewById(R.id.btn_open_repo)
        private val btnRemove: Button = itemView.findViewById(R.id.btn_remove_repo)

        fun bindRepo(
            repo: RepoConfig,
            onOpen: (RepoConfig) -> Unit,
            onRemove: (RepoConfig) -> Unit
        ) {
            tvName.text = repo.name
            tvUrl.text = repo.description.ifEmpty { repo.url }
            tvMeta.text = "${repo.repoType} • ${repo.pluginCount} providers/channels"

            btnOpen.visibility = View.VISIBLE
            btnOpen.text = if (repo.repoType == "MEGARepo") "Open MegaRepo List" else "Browse / Install"
            btnOpen.setOnClickListener { onOpen(repo) }

            btnRemove.visibility = View.VISIBLE
            btnRemove.setOnClickListener { onRemove(repo) }
        }

        fun bindExtension(
            ext: Cs3PluginMeta,
            onToggle: (Cs3PluginMeta) -> Unit
        ) {
            tvName.text = "${ext.name}  [${ext.language}]"
            tvUrl.text = ext.description.ifEmpty { ext.url }
            tvMeta.text = if (ext.isInstalled) "INSTALLED & ACTIVE" else "NOT INSTALLED"

            btnRemove.visibility = View.GONE
            btnOpen.visibility = View.VISIBLE
            btnOpen.text = if (ext.isInstalled) "Installed (Open / Toggle)" else "+ Install"
            btnOpen.setOnClickListener { onToggle(ext) }
        }
    }
}
