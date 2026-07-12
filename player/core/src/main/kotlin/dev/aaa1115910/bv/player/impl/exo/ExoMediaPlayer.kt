package dev.aaa1115910.bv.player.impl.exo

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.aaa1115910.bv.player.AbstractVideoPlayer
import dev.aaa1115910.bv.player.OkHttpUtil
import dev.aaa1115910.bv.player.VideoPlayerOptions
import dev.aaa1115910.bv.util.formatHourMinSec

@OptIn(UnstableApi::class)
class ExoMediaPlayer(
    private val context: Context,
    private val options: VideoPlayerOptions
) : AbstractVideoPlayer(), Player.Listener {
    companion object {
        private const val MAX_AUTOMATIC_RECOVERY_ATTEMPTS = 2
        private const val RECOVERY_REWIND_MS = 3_000L
    }

    var mPlayer: ExoPlayer? = null
        private set

    /**
     * Incremented whenever the internal ExoPlayer instance is replaced.
     * Compose reads this value to detach the old PlayerView and bind the new player immediately.
     */
    var playerGeneration by mutableIntStateOf(0)
        private set

    protected var mMediaSource: MediaSource? = null

    private var currentVideoUrl: String? = null
    private var currentAudioUrl: String? = null
    private var automaticRecoveryAttempts = 0
    private var isRecovering = false

    @OptIn(UnstableApi::class)
    private val dataSourceFactory =
        OkHttpDataSource.Factory(OkHttpUtil.generateCustomSslOkHttpClient(context)).apply {
            options.userAgent?.let { setUserAgent(it) }
            options.referer?.let { setDefaultRequestProperties(mapOf("referer" to it)) }
        }

    init {
        initPlayer()
    }

    @OptIn(UnstableApi::class)
    override fun initPlayer() {
        if (mPlayer != null) return

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                when (options.enableFfmpegAudioRenderer) {
                    true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            )
        }
        mPlayer = ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderersFactory)
            .setSeekForwardIncrementMs(1000 * 10)
            .setSeekBackIncrementMs(1000 * 5)
            .build()
            .apply {
                // Every selected video/episode should start automatically after prepare().
                playWhenReady = true
                addListener(this@ExoMediaPlayer)
            }
        playerGeneration++
    }

    @OptIn(UnstableApi::class)
    override fun setHeader(headers: Map<String, String>) {

    }

    @OptIn(UnstableApi::class)
    override fun playUrl(videoUrl: String?, audioUrl: String?) {
        val replacingExistingStream =
            currentVideoUrl != null || currentAudioUrl != null || mMediaSource != null

        currentVideoUrl = videoUrl
        currentAudioUrl = audioUrl
        automaticRecoveryAttempts = 0
        isRecovering = false

        // A full ExoPlayer recreation is intentional here. Some Android TV MediaCodec
        // implementations retain native decoder and Surface buffers after stop/clearMediaItems,
        // eventually causing severe lag after many videos. Releasing between streams returns these
        // resources without requiring the user to force-stop the whole app.
        if (replacingExistingStream) {
            recreateInternalPlayer()
        } else if (mPlayer == null) {
            initPlayer()
        }

        mMediaSource = buildMediaSource(videoUrl, audioUrl)
    }

    private fun buildMediaSource(videoUrl: String?, audioUrl: String?): MediaSource {
        val videoMediaSource = videoUrl?.let {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(it))
        }
        val audioMediaSource = audioUrl?.let {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(it))
        }

        val mediaSources = listOfNotNull(videoMediaSource, audioMediaSource)
        require(mediaSources.isNotEmpty()) { "At least one media URL is required" }
        return MergingMediaSource(*mediaSources.toTypedArray())
    }

    @OptIn(UnstableApi::class)
    override fun prepare() {
        val player = checkNotNull(mPlayer) { "Player has been released" }
        val mediaSource = checkNotNull(mMediaSource) { "Media source has not been configured" }
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    override fun start() {
        mPlayer?.play()
    }

    override fun pause() {
        mPlayer?.pause()
    }

    override fun stop() {
        mPlayer?.stop()
    }

    override fun reset() {
        currentVideoUrl = null
        currentAudioUrl = null
        automaticRecoveryAttempts = 0
        isRecovering = false
        recreateInternalPlayer()
    }

    override val isPlaying: Boolean
        get() = mPlayer?.isPlaying == true

    override fun seekTo(time: Long) {
        mPlayer?.seekTo(time)
    }

    override fun release() {
        releaseInternalPlayer()
        currentVideoUrl = null
        currentAudioUrl = null
        automaticRecoveryAttempts = 0
        isRecovering = false
        mPlayerEventListener = null
    }

    private fun recreateInternalPlayer() {
        releaseInternalPlayer()
        initPlayer()
    }

    private fun releaseInternalPlayer() {
        val player = mPlayer
        mPlayer = null
        mMediaSource = null
        if (player != null) {
            runCatching { player.removeListener(this) }
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            runCatching { player.release() }
        }
    }

    override val currentPosition: Long
        get() = mPlayer?.currentPosition ?: 0
    override val duration: Long
        get() = mPlayer?.duration ?: 0
    override val bufferedPercentage: Int
        get() = mPlayer?.bufferedPercentage ?: 0

    override fun setOptions() {
        mPlayer?.playWhenReady = true
    }

    override var speed: Float
        get() = mPlayer?.playbackParameters?.speed ?: 1f
        set(value) {
            mPlayer?.setPlaybackSpeed(value)
        }
    override val tcpSpeed: Long
        get() = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> mPlayerEventListener?.onIdle()
            Player.STATE_BUFFERING -> mPlayerEventListener?.onBuffering()
            Player.STATE_READY -> {
                isRecovering = false
                mPlayerEventListener?.onReady()
            }
            Player.STATE_ENDED -> mPlayerEventListener?.onEnd()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            isRecovering = false
            mPlayerEventListener?.onPlay()
        } else {
            mPlayerEventListener?.onPause()
        }
    }

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        mPlayerEventListener?.onSeekBack(seekBackIncrementMs)
    }

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
        mPlayerEventListener?.onSeekForward(seekForwardIncrementMs)
    }

    override val debugInfo: String
        get() {
            return """
                player: ${androidx.media3.common.MediaLibraryInfo.VERSION_SLASHY}
                time: ${currentPosition.formatHourMinSec()} / ${duration.formatHourMinSec()}
                buffered: $bufferedPercentage%
                resolution: ${mPlayer?.videoSize?.width} x ${mPlayer?.videoSize?.height}
                audio: ${mPlayer?.audioFormat?.bitrate ?: 0} kbps
                video codec: ${mPlayer?.videoFormat?.sampleMimeType ?: "null"}
                audio codec: ${mPlayer?.audioFormat?.sampleMimeType ?: "null"} (${getAudioRendererName()})
                recovery attempts: $automaticRecoveryAttempts / $MAX_AUTOMATIC_RECOVERY_ATTEMPTS
            """.trimIndent()
        }

    private fun getAudioRendererName(): String {
        val rendererCount = mPlayer?.rendererCount ?: return "UnknownRenderer"
        for (i in 0 until rendererCount) {
            val renderer = mPlayer!!.getRenderer(i)
            if (renderer.trackType == C.TRACK_TYPE_AUDIO && renderer.state == Renderer.STATE_STARTED) {
                return renderer.name
            }
        }
        return "UnknownRenderer"
    }

    override val videoWidth: Int
        get() = mPlayer?.videoSize?.width ?: 0
    override val videoHeight: Int
        get() = mPlayer?.videoSize?.height ?: 0

    override fun onPlayerError(error: PlaybackException) {
        if (isInvalidNalLengthError(error) && tryRecoverFromMalformedNal()) {
            return
        }
        mPlayerEventListener?.onError(error)
    }

    private fun isInvalidNalLengthError(error: PlaybackException): Boolean {
        var throwable: Throwable? = error
        while (throwable != null) {
            val message = throwable.message.orEmpty()
            if (message.contains("Invalid NAL length", ignoreCase = true) ||
                message.contains("contentIsMalformed=true", ignoreCase = true)
            ) {
                return true
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun tryRecoverFromMalformedNal(): Boolean {
        if (isRecovering || automaticRecoveryAttempts >= MAX_AUTOMATIC_RECOVERY_ATTEMPTS) {
            return false
        }
        val videoUrl = currentVideoUrl
        val audioUrl = currentAudioUrl
        if (videoUrl == null && audioUrl == null) return false

        automaticRecoveryAttempts++
        isRecovering = true
        mPlayerEventListener?.onBuffering()

        val resumePosition =
            ((mPlayer?.currentPosition ?: 0L) - RECOVERY_REWIND_MS).coerceAtLeast(0L)
        val hardReset = automaticRecoveryAttempts == MAX_AUTOMATIC_RECOVERY_ATTEMPTS

        return runCatching {
            if (hardReset) {
                // The first retry rebuilds only the extractor/media source. If malformed data leaves
                // the decoder in a bad state, the second retry also recreates ExoPlayer/MediaCodec.
                recreateInternalPlayer()
            } else {
                mPlayer?.run {
                    stop()
                    clearMediaItems()
                }
            }

            val source = buildMediaSource(videoUrl, audioUrl)
            mMediaSource = source
            checkNotNull(mPlayer).run {
                setMediaSource(source)
                seekTo(resumePosition)
                prepare()
                playWhenReady = true
            }
        }.isSuccess.also { recovered ->
            if (!recovered) isRecovering = false
        }
    }
}
