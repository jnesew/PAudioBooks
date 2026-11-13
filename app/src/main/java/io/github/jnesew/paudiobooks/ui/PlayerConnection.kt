package io.github.jnesew.paudiobooks.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import io.github.jnesew.paudiobooks.playback.PlaybackService
import java.util.concurrent.Executor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class PlaybackSnapshot(
    val trackId: String? = null,
    val bookId: String? = null,
    val title: String = "",
    val author: String? = null,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val speed: Float = 1f,
    val failed: Boolean = false,
    val saveFailed: Boolean = false,
    val undoAvailable: Boolean = false,
    val sleepActive: Boolean = false,
)

data class PlaybackConnection(val controller: MediaController?, val state: PlaybackSnapshot)

/** A controller only. The Activity and composables never own an audio player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun rememberPlaybackConnection(): PlaybackConnection {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var snapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    DisposableEffect(context) {
        val executor = mainExecutor()
        fun update(player: MediaController) { snapshot = player.snapshot() }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                controller?.let(::update)
            }
        }
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) { update(controller) }
                override fun onDisconnected(controller: MediaController) {
                    snapshot = snapshot.copy(playing = false)
                }
            }).buildAsync()
        var disposed = false
        future.addListener({
            try {
                val connected = future.get()
                if (!disposed) {
                    controller = connected
                    connected.addListener(listener)
                    update(connected)
                }
            } catch (_: Exception) {
                if (!disposed) snapshot = snapshot.copy(failed = true)
            }
        }, executor)
        onDispose {
            disposed = true
            controller?.removeListener(listener)
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    LaunchedEffect(controller, snapshot.playing) {
        val connected = controller ?: return@LaunchedEffect
        while (snapshot.playing) {
            delay(500)
            snapshot = connected.snapshot()
        }
    }
    return PlaybackConnection(controller, snapshot)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun MediaController.snapshot() = PlaybackSnapshot(
    trackId = currentMediaItem?.mediaId,
    bookId = mediaMetadata.extras?.getString(PlaybackService.BOOK_ID),
    title = mediaMetadata.title?.toString().orEmpty(),
    author = mediaMetadata.artist?.toString(),
    trackIndex = currentMediaItemIndex.coerceAtLeast(0),
    positionMs = currentPosition.coerceAtLeast(0),
    durationMs = duration.takeIf { it != C.TIME_UNSET && it >= 0 },
    playing = isPlaying,
    ready = playbackState == Player.STATE_READY,
    speed = playbackParameters.speed,
    failed = playerError != null,
    saveFailed = sessionExtras.getBoolean(PlaybackService.SAVE_FAILED),
    undoAvailable = sessionExtras.getBoolean(PlaybackService.UNDO_AVAILABLE),
    sleepActive = sessionExtras.getBoolean(PlaybackService.SLEEP_ACTIVE),
)

private fun mainExecutor(): Executor {
    val handler = Handler(Looper.getMainLooper())
    return Executor { handler.post(it) }
}

suspend fun MediaController.command(action: String, args: Bundle = Bundle.EMPTY): Boolean =
    suspendCancellableCoroutine { continuation ->
        val future = sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            val successful = try { future.get().resultCode == SessionResult.RESULT_SUCCESS }
                catch (_: Exception) { false }
            if (continuation.isActive) continuation.resume(successful)
        }, mainExecutor())
        continuation.invokeOnCancellation { future.cancel(false) }
    }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
suspend fun MediaController.loadBook(bookId: String): Boolean = command(PlaybackService.LOAD_BOOK,
    Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) })

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
suspend fun MediaController.pauseAndSave(): Boolean = command(PlaybackService.PAUSE_AND_SAVE)
