package com.vibe.tv.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vibe.tv.R
import com.vibe.tv.core.LowLatencyHttp
import com.vibe.tv.core.ZeroCacheJanitor
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var tvBufferingStatus: TextView
    private lateinit var osdContainer: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerStats: TextView
    private lateinit var btnOsdPlayPause: Button
    private lateinit var btnOsdRew: Button
    private lateinit var btnOsdFfwd: Button
    private lateinit var btnOsdSwitchSource: Button
    private lateinit var btnOsdAspect: Button
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var progressBar: ProgressBar

    private var exoPlayer: ExoPlayer? = null
    private var streamUrls: List<String> = emptyList()
    private var streamNames: List<String> = emptyList()
    private var currentStreamIndex: Int = 0
    private var streamReferer: String = ""
    private var streamTitle: String = "Now Playing"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isZoomMode = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgressHud()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val autoHideOsdRunnable = Runnable {
        if (exoPlayer?.isPlaying == true) {
            osdContainer.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        streamTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
        streamUrls = intent.getStringArrayListExtra(EXTRA_STREAM_URLS) ?: emptyList()
        streamNames = intent.getStringArrayListExtra(EXTRA_STREAM_NAMES) ?: emptyList()
        streamReferer = intent.getStringExtra(EXTRA_REFERER) ?: ""

        if (streamUrls.isEmpty()) {
            finish()
            return
        }

        bindViews()
        initializeLowLatencyPlayer()
        loadStreamAtIndex(0)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        loadingOverlay = findViewById(R.id.loading_overlay)
        tvBufferingStatus = findViewById(R.id.tv_buffering_status)
        osdContainer = findViewById(R.id.osd_container)
        tvPlayerTitle = findViewById(R.id.tv_player_title)
        tvPlayerStats = findViewById(R.id.tv_player_stats)
        btnOsdPlayPause = findViewById(R.id.btn_osd_play_pause)
        btnOsdRew = findViewById(R.id.btn_osd_rew)
        btnOsdFfwd = findViewById(R.id.btn_osd_ffwd)
        btnOsdSwitchSource = findViewById(R.id.btn_osd_switch_source)
        btnOsdAspect = findViewById(R.id.btn_osd_aspect)
        tvPosition = findViewById(R.id.tv_position)
        tvDuration = findViewById(R.id.tv_duration)
        progressBar = findViewById(R.id.player_progress_bar)

        tvPlayerTitle.text = streamTitle

        btnOsdPlayPause.setOnClickListener {
            togglePlayPause()
        }

        btnOsdRew.setOnClickListener {
            seekRelativeMs(-10_000L)
        }

        btnOsdFfwd.setOnClickListener {
            seekRelativeMs(10_000L)
        }

        btnOsdSwitchSource.setOnClickListener {
            if (streamUrls.size > 1) {
                currentStreamIndex = (currentStreamIndex + 1) % streamUrls.size
                loadStreamAtIndex(currentStreamIndex)
            } else {
                Toast.makeText(this, "Only 1 source available for this title", Toast.LENGTH_SHORT).show()
            }
        }

        btnOsdAspect.setOnClickListener {
            isZoomMode = !isZoomMode
            playerView.resizeMode = if (isZoomMode) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            btnOsdAspect.text = if (isZoomMode) "Screen: Zoom" else "Screen: Fit"
            resetOsdAutoHideTimer()
        }
    }

    private fun initializeLowLatencyPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 6_000,
                /* maxBufferMs = */ 18_000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1_200
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 0,
                /* retainBackBufferFromKeyframe = */ false
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val defaultHeaders = mutableMapOf<String, String>()
        if (streamReferer.isNotEmpty()) {
            defaultHeaders["Referer"] = streamReferer
        }
        val dataSourceFactory = OkHttpDataSource.Factory(LowLatencyHttp.client)
            .setDefaultRequestProperties(defaultHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(1_500)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { player ->
                playerView.player = player
                player.playWhenReady = true
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                loadingOverlay.visibility = View.VISIBLE
                                tvBufferingStatus.text = "Loading video..."
                            }
                            Player.STATE_READY -> {
                                loadingOverlay.visibility = View.GONE
                                updateVideoFormatStats()
                                resetOsdAutoHideTimer()
                            }
                            Player.STATE_ENDED -> {
                                loadingOverlay.visibility = View.GONE
                                ZeroCacheJanitor.purgeAll(applicationContext)
                                finish()
                            }
                            Player.STATE_IDLE -> {
                                loadingOverlay.visibility = View.GONE
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        btnOsdPlayPause.text = if (isPlaying) "Pause" else "Play"
                        if (isPlaying) {
                            resetOsdAutoHideTimer()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (streamUrls.size > 1 && currentStreamIndex + 1 < streamUrls.size) {
                            currentStreamIndex++
                            loadStreamAtIndex(currentStreamIndex)
                        } else {
                            loadingOverlay.visibility = View.GONE
                            Toast.makeText(
                                this@PlayerActivity,
                                "This stream is currently offline. Please try another title.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
            }

        mainHandler.post(progressUpdater)
    }

    private fun loadStreamAtIndex(index: Int) {
        val url = streamUrls.getOrNull(index) ?: return
        val mirrorLabel = streamNames.getOrNull(index) ?: "Source #${index + 1}"

        val mimeType = when {
            url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(1_500)
                    .setMaxPlaybackSpeed(1.04f)
                    .build()
            )

        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        tvPlayerTitle.text = if (streamUrls.size > 1) "$streamTitle ($mirrorLabel)" else streamTitle
        loadingOverlay.visibility = View.VISIBLE

        exoPlayer?.apply {
            setMediaItem(mediaItemBuilder.build())
            prepare()
            play()
        }
        showOsd()
    }

    private fun updateVideoFormatStats() {
        val player = exoPlayer ?: return
        val format = player.videoFormat
        val res = if (format != null && format.height > 0) {
            "${format.height}p HD"
        } else {
            "HD"
        }
        tvPlayerStats.text = "Quality: $res • Press Left / Right on remote to jump 10 seconds"
    }

    private fun updateProgressHud() {
        val player = exoPlayer ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration

        tvPosition.text = formatTimeMs(pos)
        if (dur != C.TIME_UNSET && dur > 0) {
            tvDuration.text = formatTimeMs(dur)
            progressBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        } else {
            tvDuration.text = "LIVE TV"
            progressBar.progress = 1000
        }
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSec = ms / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            showOsd()
        } else {
            player.play()
            resetOsdAutoHideTimer()
        }
    }

    private fun seekRelativeMs(deltaMs: Long) {
        val player = exoPlayer ?: return
        val dur = player.duration
        val nextPos = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val clamped = if (dur != C.TIME_UNSET && dur > 0) nextPos.coerceAtMost(dur) else nextPos
        player.seekTo(clamped)
        showOsd()
        updateProgressHud()
    }

    private fun showOsd() {
        osdContainer.visibility = View.VISIBLE
        resetOsdAutoHideTimer()
    }

    private fun resetOsdAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideOsdRunnable)
        mainHandler.postDelayed(autoHideOsdRunnable, 4500L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exoPlayer?.play()
                resetOsdAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exoPlayer?.pause()
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelativeMs(10_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelativeMs(-10_000L)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (osdContainer.visibility != View.VISIBLE) {
                    seekRelativeMs(-10_000L)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (osdContainer.visibility != View.VISIBLE) {
                    seekRelativeMs(10_000L)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (osdContainer.visibility != View.VISIBLE) {
                    showOsd()
                    btnOsdPlayPause.requestFocus()
                    togglePlayPause()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                if (osdContainer.visibility != View.VISIBLE) {
                    showOsd()
                    btnOsdPlayPause.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (osdContainer.visibility == View.VISIBLE) {
                    osdContainer.visibility = View.GONE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        releasePlayerAndPurgeCache()
    }

    override fun onDestroy() {
        releasePlayerAndPurgeCache()
        super.onDestroy()
    }

    private fun releasePlayerAndPurgeCache() {
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.release()
        }
        exoPlayer = null
        ZeroCacheJanitor.purgeAll(applicationContext)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_STREAM_URLS = "extra_stream_urls"
        const val EXTRA_STREAM_NAMES = "extra_stream_names"
        const val EXTRA_REFERER = "extra_referer"
    }
}
