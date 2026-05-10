package dev.aaa1115910.bv.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.aaa1115910.bv.player.impl.exo.ExoMediaPlayer

@OptIn(UnstableApi::class)
@Composable
fun BvVideoPlayer(
    modifier: Modifier = Modifier,
    videoPlayer: AbstractVideoPlayer,
    playerListener: VideoPlayerListener,
) {
    DisposableEffect(videoPlayer, playerListener) {
        videoPlayer.setPlayerEventListener(playerListener)

        onDispose {
            videoPlayer.setPlayerEventListener(null)
        }
    }

    when (videoPlayer) {
        is ExoMediaPlayer -> {
            AndroidView(
                modifier = modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = videoPlayer.mPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                        useController = false
                    }
                },
                update = { playerView ->
                    if (playerView.player !== videoPlayer.mPlayer) {
                        playerView.player = videoPlayer.mPlayer
                    }
                },
                onRelease = { playerView ->
                    // Detach the Surface/PlayerView before the ExoPlayer is released so MediaCodec
                    // and native buffers are returned promptly on low-memory Android TV boxes.
                    playerView.player = null
                }
            )
        }
    }
}
