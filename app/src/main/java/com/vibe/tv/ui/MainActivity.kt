package com.vibe.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.vibe.tv.core.RamImageLoader
import com.vibe.tv.core.ZeroCacheJanitor
import com.vibe.tv.model.Cs3PluginMeta
import com.vibe.tv.model.MediaCardItem
import com.vibe.tv.model.RepoConfig
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

    private lateinit var btnNavWatch: Button
    private lateinit var btnNavIndian: Button
    private lateinit var btnNavLive: Button
    private lateinit var btnNavSearch: Button
    private lateinit var btnNavSources: Button
    private lateinit var tvStatusHint: TextView

    private lateinit var heroBannerContainer: FrameLayout
    private lateinit var ivHeroBackdrop: ImageView
    private lateinit var tvHeroBadge: TextView
    private lateinit var tvHeroMeta: TextView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroDesc: TextView
    private lateinit var tvRowHeader: TextView

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearchQuery: EditText
    private lateinit var btnExecuteSearch: Button
    private lateinit var btnClearSearch: Button

    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var panelRepositories: LinearLayout
    private lateinit var tvRepoPanelTitle: TextView
    private lateinit var btnBackToRepos: Button
    private lateinit var repoInputBar: LinearLayout
    private lateinit var etRepoUrl: EditText
    private lateinit var btnAddRepo: Button
    private lateinit var rvReposList: RecyclerView

    private lateinit var mediaAdapter: MediaCardAdapter
    private lateinit var repoAdapter: RepoListAdapter

    private var allCatalogItems: List<MediaCardItem> = emptyList()
    private var activeCategory: String = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ZeroCacheJanitor.purgeAll(applicationContext)
        setContentView(R.layout.activity_main)
        repoManager = RepoManager(applicationContext)

        bindViews()
        setupAdapters()
        setupListeners()

        btnNavWatch.requestFocus()
        syncAllRepositories()
    }

    override fun onResume() {
        super.onResume()
        ZeroCacheJanitor.purgeAll(applicationContext)
    }

    override fun onDestroy() {
        RamImageLoader.clearRamCache()
        ZeroCacheJanitor.purgeAll(applicationContext)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        btnNavWatch = findViewById(R.id.btn_nav_watch)
        btnNavIndian = findViewById(R.id.btn_nav_indian)
        btnNavLive = findViewById(R.id.btn_nav_live)
        btnNavSearch = findViewById(R.id.btn_nav_search)
        btnNavSources = findViewById(R.id.btn_nav_sources)
        tvStatusHint = findViewById(R.id.tv_status_hint)

        heroBannerContainer = findViewById(R.id.hero_banner_container)
        ivHeroBackdrop = findViewById(R.id.iv_hero_backdrop)
        tvHeroBadge = findViewById(R.id.tv_hero_badge)
        tvHeroMeta = findViewById(R.id.tv_hero_meta)
        tvHeroTitle = findViewById(R.id.tv_hero_title)
        tvHeroDesc = findViewById(R.id.tv_hero_desc)
        tvRowHeader = findViewById(R.id.tv_row_header)

        searchBarContainer = findViewById(R.id.search_bar_container)
        etSearchQuery = findViewById(R.id.et_search_query)
        btnExecuteSearch = findViewById(R.id.btn_execute_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)

        rvMediaGrid = findViewById(R.id.rv_media_grid)
        panelRepositories = findViewById(R.id.panel_repositories)
        tvRepoPanelTitle = findViewById(R.id.tv_repo_panel_title)
        btnBackToRepos = findViewById(R.id.btn_back_to_repos)
        repoInputBar = findViewById(R.id.repo_input_bar)
        etRepoUrl = findViewById(R.id.et_repo_url)
        btnAddRepo = findViewById(R.id.btn_add_repo)
        rvReposList = findViewById(R.id.rv_repos_list)
    }

    private fun setupAdapters() {
        mediaAdapter = MediaCardAdapter(
            onItemFocused = { item -> updateNetflixHeroBanner(item) },
            onItemClick = { item -> onMediaCardSelected(item) }
        )
        rvMediaGrid.layoutManager = GridLayoutManager(this, 4)
        rvMediaGrid.adapter = mediaAdapter

        repoAdapter = RepoListAdapter(
            onOpenRepoExtensions = { repo -> openExtensionsInsideRepo(repo) },
            onRemoveRepoClick = { repoToRemove ->
                repoManager.removeRepository(repoToRemove.url)
                refreshRepoListUi()
                syncAllRepositories()
                Toast.makeText(this, "Removed ${repoToRemove.name}", Toast.LENGTH_SHORT).show()
            },
            onToggleExtensionClick = { ext -> handleExtensionAction(ext) }
        )
        rvReposList.layoutManager = LinearLayoutManager(this)
        rvReposList.adapter = repoAdapter
    }

    private fun setupListeners() {
        btnNavWatch.setOnClickListener {
            showWatchSection("ALL", "Featured & Trending (1080p / 4K)")
        }

        btnNavIndian.setOnClickListener {
            showWatchSection("INDIAN", "Indian Releases, Regional Providers & Live Channels")
        }

        btnNavLive.setOnClickListener {
            showWatchSection("LIVE", "Live Television HD Channels")
        }

        btnNavSearch.setOnClickListener {
            showSearchSection()
        }

        btnNavSources.setOnClickListener {
            showSourcesSection()
        }

        btnBackToRepos.setOnClickListener {
            refreshRepoListUi()
        }

        btnExecuteSearch.setOnClickListener {
            handleIntelligentSearch()
        }

        btnClearSearch.setOnClickListener {
            etSearchQuery.setText("")
            showWatchSection("ALL", "Featured & Trending (1080p / 4K)")
        }

        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                handleIntelligentSearch()
                true
            } else {
                false
            }
        }

        btnAddRepo.setOnClickListener {
            val rawUrl = etRepoUrl.text.toString().trim()
            if (rawUrl.isEmpty()) {
                Toast.makeText(
                    this,
                    "Type megarepo, phisherrepo, cnc, csx, or a repo URL",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            addNewRepository(rawUrl)
        }
    }

    private fun updateNetflixHeroBanner(item: MediaCardItem) {
        tvHeroBadge.text = item.badge
        tvHeroMeta.text = "${item.providerName} • ${item.subtitle}"
        tvHeroTitle.text = item.title
        tvHeroDesc.text = item.synopsis.ifEmpty {
            "Press OK on your Fire TV remote to stream '${item.title}' in 1080p / 4K HD."
        }
        val heroImage = item.backdropUrl.ifEmpty { item.posterUrl }
        RamImageLoader.load(ivHeroBackdrop, heroImage, targetWidthPx = 960)
    }

    private fun showWatchSection(category: String, headerTitle: String) {
        panelRepositories.visibility = View.GONE
        searchBarContainer.visibility = View.GONE
        heroBannerContainer.visibility = View.VISIBLE
        tvRowHeader.visibility = View.VISIBLE
        rvMediaGrid.visibility = View.VISIBLE
        tvRowHeader.text = headerTitle
        filterByCategory(category)
    }

    private fun showSearchSection() {
        panelRepositories.visibility = View.GONE
        heroBannerContainer.visibility = View.GONE
        searchBarContainer.visibility = View.VISIBLE
        tvRowHeader.visibility = View.VISIBLE
        rvMediaGrid.visibility = View.VISIBLE
        tvRowHeader.text = "Intelligent Search (Indian & Global Movies, Series & Installed Repos)"
        etSearchQuery.requestFocus()
    }

    private fun showSourcesSection() {
        rvMediaGrid.visibility = View.GONE
        heroBannerContainer.visibility = View.GONE
        tvRowHeader.visibility = View.GONE
        searchBarContainer.visibility = View.GONE
        panelRepositories.visibility = View.VISIBLE
        refreshRepoListUi()
        etRepoUrl.requestFocus()
    }

    private fun filterByCategory(category: String) {
        activeCategory = category
        val filtered = when (category) {
            "INDIAN" -> allCatalogItems.filter {
                it.category == "INDIAN" ||
                    it.providerName.contains("India", true) ||
                    it.providerName.contains("Phisher", true) ||
                    it.providerName.contains("CNC", true) ||
                    it.providerName.contains("CSX", true)
            }
            "LIVE" -> allCatalogItems.filter {
                it.category == "LIVE" || it.badge.contains("LIVE", true)
            }
            else -> allCatalogItems
        }
        mediaAdapter.submitList(filtered)
        filtered.firstOrNull()?.let { updateNetflixHeroBanner(it) }
        tvStatusHint.text = "${filtered.size} Titles Ready"
    }

    private fun refreshRepoListUi() {
        tvRepoPanelTitle.text =
            "Installed Repositories (Click 'Browse / Install' on any Repo to view & install its extensions)"
        btnBackToRepos.visibility = View.GONE
        repoInputBar.visibility = View.VISIBLE
        repoAdapter.submitRepos(repoManager.getSavedRepos())
    }

    private fun openExtensionsInsideRepo(repo: RepoConfig) {
        tvRepoPanelTitle.text = "Loading extensions inside ${repo.name}..."
        btnBackToRepos.visibility = View.VISIBLE
        repoInputBar.visibility = View.GONE

        uiScope.launch {
            val extensions = withContext(Dispatchers.IO) {
                repoManager.fetchExtensionsInsideRepo(repo)
            }
            if (extensions.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "${repo.name} is a direct stream playlist (${repo.pluginCount} streams already on Home screen)",
                    Toast.LENGTH_LONG
                ).show()
                refreshRepoListUi()
            } else {
                tvRepoPanelTitle.text = "${repo.name} — ${extensions.size} Extensions / Sub-Repos"
                repoAdapter.submitExtensions(extensions)
            }
        }
    }

    private fun handleExtensionAction(ext: Cs3PluginMeta) {
        // If this entry is a sub-repository from MegaRepo, install the sub-repository!
        if (ext.tvTypes.contains("Sub-Repository") || ext.url.endsWith(".json", true)) {
            addNewRepository(ext.url, ext.name)
            return
        }

        // Toggle plugin enabled/installed state AND try opening it
        val isNowInstalled = repoManager.togglePluginInstalled(ext.url)
        Toast.makeText(
            this,
            if (isNowInstalled) "Installed & Enabled: ${ext.name}" else "Disabled: ${ext.name}",
            Toast.LENGTH_SHORT
        ).show()
        syncAllRepositories()
    }

    private fun addNewRepository(rawUrl: String, customName: String = "") {
        tvStatusHint.text = "Installing $rawUrl..."
        btnAddRepo.isEnabled = false

        uiScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    repoManager.addRepositoryUrl(rawUrl, customName)
                }
                etRepoUrl.setText("")
                refreshRepoListUi()
                Toast.makeText(
                    this@MainActivity,
                    "Installed: ${added.name}",
                    Toast.LENGTH_SHORT
                ).show()
                syncAllRepositories()
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnAddRepo.isEnabled = true
                ZeroCacheJanitor.purgeAll(applicationContext)
            }
        }
    }

    private fun syncAllRepositories() {
        tvStatusHint.text = "Syncing Indian & Global Repos..."
        uiScope.launch {
            val items = withContext(Dispatchers.IO) {
                repoManager.fetchAllCatalogItems()
            }
            allCatalogItems = items
            filterByCategory(activeCategory)
            ZeroCacheJanitor.purgeAll(applicationContext)
        }
    }

    private fun handleIntelligentSearch() {
        val query = etSearchQuery.text.toString().trim()
        if (query.isEmpty()) {
            filterByCategory("ALL")
            return
        }

        if (query.startsWith("http://", ignoreCase = true) ||
            query.startsWith("https://", ignoreCase = true) ||
            ExtractorEngine.isDirectStreamUrl(query)
        ) {
            fetchAndPlayDirectUrl(query)
            return
        }

        tvRowHeader.text = "Searching Indian & Global Releases for \"$query\"..."
        val localMatches = allCatalogItems.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true) ||
                it.providerName.contains(query, ignoreCase = true)
        }
        mediaAdapter.submitList(localMatches)

        uiScope.launch {
            val liveCatalogMatches = withContext(Dispatchers.IO) {
                ExtractorEngine.searchGlobalAndIndianCatalog(query)
            }
            val combined = (localMatches + liveCatalogMatches).distinctBy { it.title + it.subtitle }
            mediaAdapter.submitList(combined)
            tvRowHeader.text = "Search Results for \"$query\" (${combined.size} matches)"
            combined.firstOrNull()?.let { updateNetflixHeroBanner(it) }
        }
    }

    private fun fetchAndPlayDirectUrl(rawUrl: String) {
        val url = NativeEngine.normalizeUrl(rawUrl)
        if (ExtractorEngine.isDirectStreamUrl(url)) {
            launchPlayer(
                title = url.substringAfterLast('/').substringBefore('?').ifEmpty { "Direct Stream" },
                streams = listOf(StreamSource(name = "Direct 1080p", url = url))
            )
            return
        }

        tvStatusHint.text = "Extracting 1080p/4K stream..."
        uiScope.launch {
            val extractedStreams = withContext(Dispatchers.IO) {
                try {
                    val html = LowLatencyHttp.fetchText(url)
                    val links = NativeEngine.extractStreams(html, url)
                    links.mapIndexed { idx, streamUrl ->
                        StreamSource(
                            name = "Server #${idx + 1}",
                            url = streamUrl,
                            referer = url
                        )
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            if (extractedStreams.isNotEmpty()) {
                launchPlayer(title = "Extracted Stream", streams = extractedStreams)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No direct video stream found at that URL",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onMediaCardSelected(item: MediaCardItem) {
        if (item.streams.isNotEmpty()) {
            launchPlayer(item.title, item.streams)
            return
        }

        tvStatusHint.text = "Fetching highest quality stream for ${item.title}..."
        uiScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                ExtractorEngine.resolveStreamsForItem(item)
            }
            if (resolved.isNotEmpty()) {
                launchPlayer(item.title, resolved)
            } else {
                // Fallback: search matching live/repo streams by title keyword
                val keyword = item.title.split(" ").firstOrNull { it.length > 3 } ?: item.title
                val fallbackMatch = allCatalogItems.firstOrNull {
                    it.streams.isNotEmpty() && it.title.contains(keyword, ignoreCase = true)
                }
                if (fallbackMatch != null) {
                    launchPlayer(fallbackMatch.title, fallbackMatch.streams)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Provider '${item.title}' requires a direct episode/stream link or Live TV channel.",
                        Toast.LENGTH_LONG
                    ).show()
                    tvStatusHint.text = "Ready"
                }
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
}
