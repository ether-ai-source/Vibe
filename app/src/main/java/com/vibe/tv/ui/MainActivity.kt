package com.vibe.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vibe.tv.R
import com.vibe.tv.core.LowLatencyHttp
import com.vibe.tv.core.NativeEngine
import com.vibe.tv.core.ZeroCacheJanitor
import com.vibe.tv.model.MediaCardItem
import com.vibe.tv.model.StreamSource
import com.vibe.tv.player.PlayerActivity
import com.vibe.tv.repo.ExtractorEngine
import com.vibe.tv.repo.RepoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repoManager: RepoManager

    private lateinit var tvEngineStatus: TextView
    private lateinit var tvSectionSubtitle: TextView
    private lateinit var btnNavHome: Button
    private lateinit var btnNavRepos: Button
    private lateinit var btnNavDirect: Button
    private lateinit var btnPurgeCache: Button

    private lateinit var etSearchQuery: EditText
    private lateinit var btnExecuteSearch: Button
    private lateinit var btnReloadSources: Button

    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var panelRepositories: LinearLayout
    private lateinit var etRepoUrl: EditText
    private lateinit var btnAddRepo: Button
    private lateinit var rvReposList: RecyclerView

    private lateinit var mediaAdapter: MediaCardAdapter
    private lateinit var repoAdapter: RepoListAdapter

    private var allCatalogItems: List<MediaCardItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immediately sweep any residual cache on launch
        ZeroCacheJanitor.purgeAll(applicationContext)

        setContentView(R.layout.activity_main)
        repoManager = RepoManager(applicationContext)

        bindViews()
        setupAdapters()
        setupListeners()
        updateCacheBadge()

        // Default Fire TV focus on Home button
        btnNavHome.requestFocus()
        syncAllRepositories()
    }

    override fun onResume() {
        super.onResume()
        // Every time user returns from PlayerActivity, guarantee 0 KB cache
        ZeroCacheJanitor.purgeAll(applicationContext)
        updateCacheBadge()
    }

    override fun onDestroy() {
        ZeroCacheJanitor.purgeAll(applicationContext)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        tvEngineStatus = findViewById(R.id.tv_engine_status)
        tvSectionSubtitle = findViewById(R.id.tv_section_subtitle)
        btnNavHome = findViewById(R.id.btn_nav_home)
        btnNavRepos = findViewById(R.id.btn_nav_repos)
        btnNavDirect = findViewById(R.id.btn_nav_direct)
        btnPurgeCache = findViewById(R.id.btn_purge_cache)

        etSearchQuery = findViewById(R.id.et_search_query)
        btnExecuteSearch = findViewById(R.id.btn_execute_search)
        btnReloadSources = findViewById(R.id.btn_reload_sources)

        rvMediaGrid = findViewById(R.id.rv_media_grid)
        panelRepositories = findViewById(R.id.panel_repositories)
        etRepoUrl = findViewById(R.id.et_repo_url)
        btnAddRepo = findViewById(R.id.btn_add_repo)
        rvReposList = findViewById(R.id.rv_repos_list)

        val engineMode = if (NativeEngine.isRustLoaded) {
            "Rust Native Core Active (JNI) • Zero-Cache"
        } else {
            "Kotlin Core Active • Zero-Cache"
        }
        tvEngineStatus.text = engineMode
    }

    private fun setupAdapters() {
        mediaAdapter = MediaCardAdapter { item ->
            onMediaCardSelected(item)
        }
        rvMediaGrid.layoutManager = GridLayoutManager(this, 3)
        rvMediaGrid.adapter = mediaAdapter

        repoAdapter = RepoListAdapter { repoToRemove ->
            repoManager.removeRepository(repoToRemove.url)
            refreshRepoListUi()
            syncAllRepositories()
            Toast.makeText(this, "Removed ${repoToRemove.name}", Toast.LENGTH_SHORT).show()
        }
        rvReposList.layoutManager = LinearLayoutManager(this)
        rvReposList.adapter = repoAdapter
    }

    private fun setupListeners() {
        btnNavHome.setOnClickListener {
            showHomeSection()
        }

        btnNavRepos.setOnClickListener {
            showReposSection()
        }

        btnNavDirect.setOnClickListener {
            showHomeSection()
            etSearchQuery.requestFocus()
            Toast.makeText(
                this,
                "Paste any direct .m3u8 / .mp4 link or webpage URL into the box below and press Fetch",
                Toast.LENGTH_LONG
            ).show()
        }

        btnPurgeCache.setOnClickListener {
            val freed = ZeroCacheJanitor.purgeAll(applicationContext)
            updateCacheBadge()
            Toast.makeText(this, "RAM & Temp swept (${freed / 1024} KB freed) • Cache: 0 KB", Toast.LENGTH_SHORT).show()
        }

        btnExecuteSearch.setOnClickListener {
            handleSearchOrDirectFetch()
        }

        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                handleSearchOrDirectFetch()
                true
            } else {
                false
            }
        }

        btnReloadSources.setOnClickListener {
            syncAllRepositories()
        }

        btnAddRepo.setOnClickListener {
            val rawUrl = etRepoUrl.text.toString().trim()
            if (rawUrl.isEmpty()) {
                Toast.makeText(this, "Enter a CloudStream 3 repo.json or M3U URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addNewRepository(rawUrl)
        }
    }

    private fun showHomeSection() {
        panelRepositories.visibility = View.GONE
        rvMediaGrid.visibility = View.VISIBLE
        tvSectionSubtitle.text = "Showing ${mediaAdapter.itemCount} streams & providers • D-Pad Center to Play"
    }

    private fun showReposSection() {
        rvMediaGrid.visibility = View.GONE
        panelRepositories.visibility = View.VISIBLE
        refreshRepoListUi()
        tvSectionSubtitle.text = "Add CloudStream 3 (repo.json / plugins.json), M3U Playlists, or Vibe JSON Repos"
        etRepoUrl.requestFocus()
    }

    private fun refreshRepoListUi() {
        repoAdapter.submitList(repoManager.getSavedRepos())
    }

    private fun addNewRepository(rawUrl: String) {
        tvSectionSubtitle.text = "Inspecting repository: $rawUrl ..."
        btnAddRepo.isEnabled = false

        uiScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    repoManager.addRepositoryUrl(rawUrl)
                }
                etRepoUrl.setText("")
                refreshRepoListUi()
                Toast.makeText(
                    this@MainActivity,
                    "Added ${added.name} (${added.pluginCount} entries)",
                    Toast.LENGTH_SHORT
                ).show()
                syncAllRepositories()
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnAddRepo.isEnabled = true
                ZeroCacheJanitor.purgeAll(applicationContext)
                updateCacheBadge()
            }
        }
    }

    private fun syncAllRepositories() {
        tvSectionSubtitle.text = "Fetching streams & CloudStream 3 repositories in RAM..."
        uiScope.launch {
            val items = withContext(Dispatchers.IO) {
                repoManager.fetchAllCatalogItems()
            }
            allCatalogItems = items
            mediaAdapter.submitList(items)
            tvSectionSubtitle.text =
                "Loaded ${items.size} streams/providers across ${repoManager.getSavedRepos().size} repo(s) • Disk Cache: 0 KB"
            ZeroCacheJanitor.purgeAll(applicationContext)
            updateCacheBadge()
        }
    }

    private fun handleSearchOrDirectFetch() {
        val query = etSearchQuery.text.toString().trim()
        if (query.isEmpty()) {
            mediaAdapter.submitList(allCatalogItems)
            tvSectionSubtitle.text = "Showing all ${allCatalogItems.size} items"
            return
        }

        // If user entered a direct URL (.m3u8, .mp4, or webpage to scrape)
        if (query.startsWith("http://", ignoreCase = true) ||
            query.startsWith("https://", ignoreCase = true) ||
            ExtractorEngine.isDirectStreamUrl(query)
        ) {
            fetchAndPlayDirectUrl(query)
            return
        }

        // Filter catalog items by title, subtitle, or provider name
        val filtered = allCatalogItems.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true) ||
                it.providerName.contains(query, ignoreCase = true)
        }
        mediaAdapter.submitList(filtered)
        tvSectionSubtitle.text = "Found ${filtered.size} match(es) for \"$query\""
    }

    private fun fetchAndPlayDirectUrl(rawUrl: String) {
        val url = NativeEngine.normalizeUrl(rawUrl)
        if (ExtractorEngine.isDirectStreamUrl(url)) {
            launchPlayer(
                title = url.substringAfterLast('/').substringBefore('?').ifEmpty { "Direct Stream" },
                streams = listOf(StreamSource(name = "Direct URL", url = url))
            )
            return
        }

        tvSectionSubtitle.text = "Running Rust Native Extractor on $url ..."
        uiScope.launch {
            val extractedStreams = withContext(Dispatchers.IO) {
                try {
                    val html = LowLatencyHttp.fetchText(url)
                    val links = NativeEngine.extractStreams(html, url)
                    links.mapIndexed { idx, streamUrl ->
                        StreamSource(
                            name = "Extracted #${idx + 1}",
                            url = streamUrl,
                            referer = url
                        )
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            if (extractedStreams.isNotEmpty()) {
                launchPlayer(
                    title = "Extracted Stream (${extractedStreams.size} mirror(s))",
                    streams = extractedStreams
                )
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No playable .m3u8 / .mp4 streams found at that URL",
                    Toast.LENGTH_LONG
                ).show()
                tvSectionSubtitle.text = "No direct stream found in page payload"
            }
        }
    }

    private fun onMediaCardSelected(item: MediaCardItem) {
        if (item.streams.isNotEmpty()) {
            launchPlayer(item.title, item.streams)
            return
        }

        tvSectionSubtitle.text = "Resolving streams for ${item.title} in RAM..."
        uiScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                ExtractorEngine.resolveStreamsForItem(item)
            }
            if (resolved.isNotEmpty()) {
                launchPlayer(item.title, resolved)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Provider '${item.title}' is a CloudStream 3 DEX plugin without direct static streams. Paste a direct page/M3U URL or use an active stream item.",
                    Toast.LENGTH_LONG
                ).show()
                tvSectionSubtitle.text = "Ready"
            }
        }
    }

    private fun launchPlayer(title: String, streams: List<StreamSource>) {
        if (streams.isEmpty()) return
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putStringArrayListExtra(
                PlayerActivity.EXTRA_STREAM_URLS,
                ArrayList(streams.map { it.url })
            )
            putStringArrayListExtra(
                PlayerActivity.EXTRA_STREAM_NAMES,
                ArrayList(streams.map { "${it.name} (${it.quality})" })
            )
            putExtra(PlayerActivity.EXTRA_REFERER, streams.first().referer)
        }
        startActivity(intent)
    }

    private fun updateCacheBadge() {
        val bytes = ZeroCacheJanitor.getCurrentCacheBytes(applicationContext)
        val kb = bytes / 1024
        btnPurgeCache.text = "Cache: ${kb} KB (Sweep)"
    }
}
