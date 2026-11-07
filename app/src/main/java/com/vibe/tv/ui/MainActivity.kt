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

    private lateinit var btnNavWatch: Button
    private lateinit var btnNavSearch: Button
    private lateinit var btnNavSources: Button
    private lateinit var tvStatusHint: TextView

    private lateinit var categoryBar: LinearLayout
    private lateinit var btnCatAll: Button
    private lateinit var btnCatMovies: Button
    private lateinit var btnCatLive: Button
    private lateinit var btnCatRepos: Button

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearchQuery: EditText
    private lateinit var btnExecuteSearch: Button
    private lateinit var btnClearSearch: Button

    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var panelRepositories: LinearLayout
    private lateinit var btnPresetIptv: Button
    private lateinit var btnPresetMovies: Button
    private lateinit var btnReloadSources: Button
    private lateinit var etRepoUrl: EditText
    private lateinit var btnAddRepo: Button
    private lateinit var rvReposList: RecyclerView

    private lateinit var mediaAdapter: MediaCardAdapter
    private lateinit var repoAdapter: RepoListAdapter

    private var allCatalogItems: List<MediaCardItem> = emptyList()
    private var activeCategory: String = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Silently purge any cache on launch
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
        // Automatically clean all temporary memory/cache when returning from video playback
        ZeroCacheJanitor.purgeAll(applicationContext)
    }

    override fun onDestroy() {
        ZeroCacheJanitor.purgeAll(applicationContext)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        btnNavWatch = findViewById(R.id.btn_nav_watch)
        btnNavSearch = findViewById(R.id.btn_nav_search)
        btnNavSources = findViewById(R.id.btn_nav_sources)
        tvStatusHint = findViewById(R.id.tv_status_hint)

        categoryBar = findViewById(R.id.category_bar)
        btnCatAll = findViewById(R.id.btn_cat_all)
        btnCatMovies = findViewById(R.id.btn_cat_movies)
        btnCatLive = findViewById(R.id.btn_cat_live)
        btnCatRepos = findViewById(R.id.btn_cat_repos)

        searchBarContainer = findViewById(R.id.search_bar_container)
        etSearchQuery = findViewById(R.id.et_search_query)
        btnExecuteSearch = findViewById(R.id.btn_execute_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)

        rvMediaGrid = findViewById(R.id.rv_media_grid)
        panelRepositories = findViewById(R.id.panel_repositories)
        btnPresetIptv = findViewById(R.id.btn_preset_iptv)
        btnPresetMovies = findViewById(R.id.btn_preset_movies)
        btnReloadSources = findViewById(R.id.btn_reload_sources)
        etRepoUrl = findViewById(R.id.et_repo_url)
        btnAddRepo = findViewById(R.id.btn_add_repo)
        rvReposList = findViewById(R.id.rv_repos_list)
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
        btnNavWatch.setOnClickListener {
            showWatchSection()
        }

        btnNavSearch.setOnClickListener {
            showSearchSection()
        }

        btnNavSources.setOnClickListener {
            showSourcesSection()
        }

        btnCatAll.setOnClickListener { filterByCategory("ALL") }
        btnCatMovies.setOnClickListener { filterByCategory("MOVIES") }
        btnCatLive.setOnClickListener { filterByCategory("LIVE") }
        btnCatRepos.setOnClickListener { filterByCategory("REPOS") }

        btnExecuteSearch.setOnClickListener {
            handleSearchOrDirectPlay()
        }

        btnClearSearch.setOnClickListener {
            etSearchQuery.setText("")
            filterByCategory("ALL")
        }

        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                handleSearchOrDirectPlay()
                true
            } else {
                false
            }
        }

        btnPresetIptv.setOnClickListener {
            addNewRepository(RepoManager.PRESET_IPTV_ENG_URL, "World Live TV (English)")
        }

        btnPresetMovies.setOnClickListener {
            addNewRepository(RepoManager.PRESET_IPTV_MOVIES_URL, "Public Movie Channels")
        }

        btnReloadSources.setOnClickListener {
            syncAllRepositories()
            showWatchSection()
        }

        btnAddRepo.setOnClickListener {
            val rawUrl = etRepoUrl.text.toString().trim()
            if (rawUrl.isEmpty()) {
                Toast.makeText(this, "Please enter a link or use a Quick-Add button above", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addNewRepository(rawUrl)
        }
    }

    private fun showWatchSection() {
        panelRepositories.visibility = View.GONE
        searchBarContainer.visibility = View.GONE
        categoryBar.visibility = View.VISIBLE
        rvMediaGrid.visibility = View.VISIBLE
        filterByCategory(activeCategory)
    }

    private fun showSearchSection() {
        panelRepositories.visibility = View.GONE
        categoryBar.visibility = View.GONE
        searchBarContainer.visibility = View.VISIBLE
        rvMediaGrid.visibility = View.VISIBLE
        etSearchQuery.requestFocus()
        tvStatusHint.text = "Search by name or paste any video link"
    }

    private fun showSourcesSection() {
        rvMediaGrid.visibility = View.GONE
        categoryBar.visibility = View.GONE
        searchBarContainer.visibility = View.GONE
        panelRepositories.visibility = View.VISIBLE
        refreshRepoListUi()
        tvStatusHint.text = "Add CloudStream 3 or Live TV sources"
        btnPresetIptv.requestFocus()
    }

    private fun filterByCategory(category: String) {
        activeCategory = category
        val filtered = when (category) {
            "MOVIES" -> allCatalogItems.filter { it.category == "MOVIES" }
            "LIVE" -> allCatalogItems.filter { it.category == "LIVE" }
            "REPOS" -> allCatalogItems.filter { it.providerName != "Ready to Play" && it.providerName != "Live Channel" }
            else -> allCatalogItems
        }
        mediaAdapter.submitList(filtered)
        tvStatusHint.text = "Showing ${filtered.size} titles • Press OK on remote to watch"
    }

    private fun refreshRepoListUi() {
        repoAdapter.submitList(repoManager.getSavedRepos())
    }

    private fun addNewRepository(rawUrl: String, customName: String = "") {
        tvStatusHint.text = "Adding source..."
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
                    "Added ${added.name} (${added.pluginCount} items)",
                    Toast.LENGTH_SHORT
                ).show()
                syncAllRepositories()
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, "Could not add link: ${e.message}", Toast.LENGTH_LONG).show()
                tvStatusHint.text = "Ready"
            } finally {
                btnAddRepo.isEnabled = true
                ZeroCacheJanitor.purgeAll(applicationContext)
            }
        }
    }

    private fun syncAllRepositories() {
        tvStatusHint.text = "Loading channels & movies..."
        uiScope.launch {
            val items = withContext(Dispatchers.IO) {
                repoManager.fetchAllCatalogItems()
            }
            allCatalogItems = items
            filterByCategory(activeCategory)
            ZeroCacheJanitor.purgeAll(applicationContext)
        }
    }

    private fun handleSearchOrDirectPlay() {
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

        val filtered = allCatalogItems.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true) ||
                it.providerName.contains(query, ignoreCase = true)
        }
        mediaAdapter.submitList(filtered)
        tvStatusHint.text = "Found ${filtered.size} result(s) for \"$query\""
    }

    private fun fetchAndPlayDirectUrl(rawUrl: String) {
        val url = NativeEngine.normalizeUrl(rawUrl)
        if (ExtractorEngine.isDirectStreamUrl(url)) {
            launchPlayer(
                title = url.substringAfterLast('/').substringBefore('?').ifEmpty { "Direct Video" },
                streams = listOf(StreamSource(name = "Direct Stream", url = url))
            )
            return
        }

        tvStatusHint.text = "Finding video stream on page..."
        uiScope.launch {
            val extractedStreams = withContext(Dispatchers.IO) {
                try {
                    val html = LowLatencyHttp.fetchText(url)
                    val links = NativeEngine.extractStreams(html, url)
                    links.mapIndexed { idx, streamUrl ->
                        StreamSource(
                            name = "Source #${idx + 1}",
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
                    title = "Direct Stream",
                    streams = extractedStreams
                )
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No playable video found at that link",
                    Toast.LENGTH_LONG
                ).show()
                tvStatusHint.text = "Ready"
            }
        }
    }

    private fun onMediaCardSelected(item: MediaCardItem) {
        if (item.streams.isNotEmpty()) {
            launchPlayer(item.title, item.streams)
            return
        }

        tvStatusHint.text = "Opening ${item.title}..."
        uiScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                ExtractorEngine.resolveStreamsForItem(item)
            }
            if (resolved.isNotEmpty()) {
                launchPlayer(item.title, resolved)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No direct stream found inside '${item.title}'. Try another title or add a Live TV / Movie playlist.",
                    Toast.LENGTH_LONG
                ).show()
                tvStatusHint.text = "Press OK on any title to play"
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
