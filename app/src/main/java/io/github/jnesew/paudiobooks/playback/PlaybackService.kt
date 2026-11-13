package io.github.jnesew.paudiobooks.playback

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.jnesew.paudiobooks.storage.ApprovedAssetDataSource
import io.github.jnesew.paudiobooks.storage.ApprovedAudioSource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.LibraryRepository
import io.github.jnesew.paudiobooks.library.PlayableBook
import io.github.jnesew.paudiobooks.storage.PrivateAudioStore
import io.github.jnesew.paudiobooks.preferences.PlaybackSettings
import io.github.jnesew.paudiobooks.preferences.PlaybackSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only this service owns the player. Controllers may load a known ID, never a URI. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        const val LOAD_BOOK = "io.github.jnesew.paudiobooks.LOAD_BOOK"
        const val BOOK_ID = "book_id"
        const val SEEK_TRACK_POSITION = "io.github.jnesew.paudiobooks.SEEK_TRACK_POSITION"
        const val TRACK_ID = "track_id"
        const val TRACK_POSITION_MS = "track_position_ms"
        const val SEEK_CHAPTER = "io.github.jnesew.paudiobooks.SEEK_CHAPTER"
        const val CHAPTER_ID = "chapter_id"
        const val SEEK_BOOK_POSITION = "io.github.jnesew.paudiobooks.SEEK_BOOK_POSITION"
        const val BOOK_POSITION_MS = "book_position_ms"
        const val SET_SPEED = "io.github.jnesew.paudiobooks.SET_SPEED"
        const val SPEED = "speed"
        const val SET_SLEEP_TIMER = "io.github.jnesew.paudiobooks.SET_SLEEP_TIMER"
        const val SLEEP_DURATION_MS = "sleep_duration_ms"
        const val SLEEP_END_CHAPTER = "sleep_end_chapter"
        const val CANCEL_SLEEP_TIMER = "io.github.jnesew.paudiobooks.CANCEL_SLEEP_TIMER"
        const val PAUSE_AND_SAVE = "io.github.jnesew.paudiobooks.PAUSE_AND_SAVE"
        const val UNLOAD_AND_SAVE = "io.github.jnesew.paudiobooks.UNLOAD_AND_SAVE"
        const val UNDO_SEEK = "io.github.jnesew.paudiobooks.UNDO_SEEK"
        const val SET_BOOK_FINISHED = "io.github.jnesew.paudiobooks.SET_BOOK_FINISHED"
        const val FINISHED = "finished"
        const val MARK_FINISHED = "io.github.jnesew.paudiobooks.MARK_FINISHED"
        const val RETRY_SAVE = "io.github.jnesew.paudiobooks.RETRY_SAVE"
        const val SAVE_FAILED = "save_failed"
        const val UNDO_AVAILABLE = "undo_available"
        const val SLEEP_ACTIVE = "sleep_active"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: ProgressCoordinator
    private lateinit var repository: LibraryRepository
    private lateinit var store: PrivateAudioStore
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: SessionPlayer
    private lateinit var session: MediaSession
    private var book: PlayableBook? = null
    @Volatile private var approvedAssets: Map<String, ApprovedAudioSource> = emptyMap()
    private var loadGeneration = 0L
    @Volatile private var destroyed = false
    private var switching = false
    private var checkpointJob: Job? = null
    private var sleepJob: Job? = null
    private var idleJob: Job? = null
    private var sleepChapterTarget: Pair<Int, Long>? = null
    private var playbackSettings = PlaybackSettings()
    private var userPausedAt: Long? = null
    private var finished = false
    private val seekHistory = ArrayDeque<TrackPosition>()
    private var undoTarget: TrackPosition? = null
    private var lastSeek: TrackPosition? = null
    private var requestedSeek: TrackPosition? = null
    private val pendingLoads = mutableSetOf<SettableFuture<SessionResult>>()
    private val gate = CheckpointGate()

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository((application as AudiobookApplication).library)
        store = PrivateAudioStore(this)
        coordinator = ProgressCoordinator(
            scope = writerScope,
            saveSink = { state ->
                val saved = repository.saveProgress(state.bookId, state.trackId, state.position, state.speed, state.finished)
                if (!saved) {
                    val exists = repository.bookExists(state.bookId)
                    if (!exists) {
                        coordinator.evictBook(state.bookId)
                        true
                    } else {
                        false
                    }
                } else {
                    true
                }
            },
            onStateChanged = { failedBookId, failed ->
                scope.launch {
                    if (!destroyed) {
                        publishSessionState()
                        if (failed && PlaybackPolicy.shouldPauseOnSaveFailure(book?.id, failedBookId)) {
                            player.pause()
                        }
                    }
                }
            }
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory {
                val app = application as AudiobookApplication
                ApprovedAssetDataSource({ approvedAssets }, app.documentAccess, app.sourceGrants,
                    unavailable={ assetId,reason -> app.cleanupScope.launch { app.library.sources().setAvailability(assetId,reason.name) } },
                    available={ assetId -> app.cleanupScope.launch { app.library.sources().setAvailability(assetId,"AVAILABLE") } })
            }))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000).build()
        sessionPlayer = SessionPlayer(player, getString(R.string.app_name)) { index, positionMs ->
            recordSeekRequest(index, positionMs)
        }
        session = MediaSession.Builder(this, sessionPlayer).setCallback(Callback()).build()
        scope.launch {
            (application as AudiobookApplication).settings.playback.collect { settings ->
                playbackSettings = settings
                player.setSeekBackIncrementMs(settings.skipBackSeconds * 1000L)
                player.setSeekForwardIncrementMs(settings.skipForwardSeconds * 1000L)
                sessionPlayer.setShowDetails(settings.showLockScreenDetails)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (switching || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (!playWhenReady) {
                    userPausedAt = SystemClock.elapsedRealtime()
                } else {
                    finished = false
                    val pausedAt = userPausedAt
                    userPausedAt = null
                    if (playbackSettings.smartRewind && pausedAt != null &&
                        SystemClock.elapsedRealtime() - pausedAt >= 300_000) {
                        val tracks = book?.tracks?.map { TrackSpan(it.id, it.durationMs) }
                        val position = tracks?.let { Timeline.toBook(it, TrackPosition(player.currentMediaItemIndex, player.currentPosition)) }
                        val target = if (position != null) Timeline.fromBook(tracks, (position - 5_000).coerceAtLeast(0)) else null
                        if (target != null) seekTo(target.index, target.offsetMs)
                        else seekTo(player.currentMediaItemIndex, (player.currentPosition - 5_000).coerceAtLeast(0))
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                checkpointJob?.cancel()
                checkpointJob = null
                if (isPlaying) {
                    cancelIdleTimeout()
                    checkpointJob = scope.launch {
                        while (true) {
                            delay(500)
                            if (gate.due(player.currentPosition, SystemClock.elapsedRealtime())) checkpoint()
                        }
                    }
                } else {
                    if (!switching) {
                        checkpoint()
                        checkpointAfterPauseAcknowledged()
                    }
                    checkIdleTimeout()
                }
                if (sleepChapterTarget != null) {
                    sleepJob?.cancel()
                    sleepJob = null
                    if (isPlaying) monitorSleepChapter()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState==Player.STATE_READY) cacheLinkedDuration()
                if (!switching && playbackState == Player.STATE_ENDED && player.playWhenReady &&
                    Completion.playedAfterSeek(lastSeek, TrackPosition(player.currentMediaItemIndex, player.currentPosition),
                        player.duration.takeIf { it >= 0 })) {
                    sleepChapterTarget = null
                    sleepJob?.cancel(); sleepJob = null
                    finished = true
                    checkpoint()
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (switching) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    userPausedAt = null
                    finished = false
                    val target = TrackPosition(newPosition.mediaItemIndex, newPosition.positionMs)
                    lastSeek = requestedSeek ?: target
                    requestedSeek = null
                    val sleepTarget = sleepChapterTarget
                    if (sleepTarget != null && PlaybackPolicy.shouldCancelSleepChapterOnSeek(sleepTarget.first, sleepTarget.second, newPosition.mediaItemIndex, newPosition.positionMs)) {
                        sleepJob?.cancel()
                        sleepJob = null
                        sleepChapterTarget = null
                    }
                    if (undoTarget != target && (oldPosition.mediaItemIndex != newPosition.mediaItemIndex || oldPosition.positionMs != newPosition.positionMs)) {
                        if (seekHistory.size >= 20) seekHistory.removeFirst()
                        seekHistory.addLast(TrackPosition(oldPosition.mediaItemIndex, oldPosition.positionMs))
                    }
                    undoTarget = null
                    publishSessionState()
                }
                checkpointAt(oldPosition.mediaItemIndex, oldPosition.positionMs)
                gate.reset(player.currentPosition, SystemClock.elapsedRealtime())
                checkpoint()
            }
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { if (!switching) checkpoint() }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            // Asynchronous intentional shutdown when paused and task is swiped away.
            // Do not invoke super.onTaskRemoved(rootIntent) here because the default
            // MediaSessionService implementation immediately calls stopSelf(), which would
            // terminate the service before the off-main progress persistence can complete.
            val snapshot = checkpoint()
            val initialGeneration = loadGeneration
            scope.launch {
                if (snapshot != null) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            awaitSave(snapshot).get(PlaybackPolicy.SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                    // Note: If timeout or I/O failure occurs, the bounded flush cannot
                    // guarantee newest progress durability, but prevents service hang.
                }
                // Recheck relevant load/playback state before stopping so that a new
                // book load, playback resumption, or active UI controller connection
                // during the asynchronous wait is not erroneously terminated.
                val hasUi = session.connectedControllers.any { isUiController(it) }
                if (PlaybackPolicy.shouldCompleteShutdown(
                        isDestroyed = destroyed,
                        isPlaying = player.isPlaying,
                        loadGenerationUnchanged = (loadGeneration == initialGeneration),
                        hasUiController = hasUi
                    )) {
                    stopSelf()
                }
            }
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val own = controller.uid == Process.myUid()
            val authenticated = own || controller.isTrusted
            if (!authenticated) return MediaSession.ConnectionResult.reject()
            if (PlaybackPolicy.shouldCancelIdleTimeout(authenticated, isUiController(controller))) {
                cancelIdleTimeout()
            }
            val commands = Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_PREPARE).add(Player.COMMAND_STOP)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE).add(Player.COMMAND_GET_METADATA).build()
            val sessionCommands = SessionCommands.Builder()
            if (own) {
                sessionCommands.add(SessionCommand(LOAD_BOOK, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(UNDO_SEEK, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(MARK_FINISHED, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(SET_BOOK_FINISHED, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(SET_SPEED, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(RETRY_SAVE, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(PAUSE_AND_SAVE, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(UNLOAD_AND_SAVE, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(SEEK_CHAPTER, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(SEEK_TRACK_POSITION, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(SEEK_BOOK_POSITION, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY))
                sessionCommands.add(SessionCommand(CANCEL_SLEEP_TIMER, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailablePlayerCommands(commands)
                .setAvailableSessionCommands(sessionCommands.build()).build()
        }
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onDisconnected(session, controller)
            checkIdleTimeout(disconnecting = controller)
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            Futures.immediateFailedFuture(SecurityException("Controller media items are not accepted"))
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFailedFuture(SecurityException("Controller media items are not accepted"))
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid())
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (customCommand.customAction == CANCEL_SLEEP_TIMER) {
                sleepJob?.cancel(); sleepJob = null
                sleepChapterTarget = null
                publishSessionState()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SET_SLEEP_TIMER) {
                val endChapter = args.getBoolean(SLEEP_END_CHAPTER, false)
                val duration = args.getLong(SLEEP_DURATION_MS, -1)
                val current = book
                if (current == null || (!endChapter && duration !in 1_000L..86_400_000L))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val target = if (endChapter) {
                    val trackIndex = player.currentMediaItemIndex
                    val track = current.tracks.getOrNull(trackIndex)
                    val spans = current.tracks.flatMapIndexed { index, t -> t.chapters.map { ChapterSpan(index, it.startMs, it.endMs) } }
                    if (!Chapters.validate(spans, current.tracks.map { TrackSpan(it.id, it.durationMs) })) null
                    else {
                        val chapter = track?.chapters?.lastOrNull { it.startMs <= player.currentPosition && (it.endMs == null || player.currentPosition < it.endMs) }
                        val next = chapter?.let { c -> track.chapters.firstOrNull { it.startMs > c.startMs }?.startMs }
                        (chapter?.endMs ?: next ?: track?.durationMs)?.let { trackIndex to it }
                    }
                } else null
                if (endChapter && target == null)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                sleepJob?.cancel()
                sleepChapterTarget = target
                if (target == null) {
                    sleepJob = scope.launch {
                        val deadline = SystemClock.elapsedRealtime() + duration
                        delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        sleepJob = null
                        publishSessionState()
                        player.pause()
                        checkpoint()
                    }
                } else if (player.isPlaying) monitorSleepChapter()
                publishSessionState()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == UNDO_SEEK) {
                val target = seekHistory.removeLastOrNull()
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                undoTarget = target
                publishSessionState()
                seekTo(target.index, target.offsetMs)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SET_BOOK_FINISHED) {
                val id = args.getString(BOOK_ID)
                if (id.isNullOrBlank() || id.length > 128 || !args.containsKey(FINISHED))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val result = SettableFuture.create<SessionResult>()
                scope.launch {
                    try {
                        val target = repository.playableBook(id)
                        if (target == null) { result.set(SessionResult(SessionError.ERROR_BAD_VALUE)); return@launch }
                        val markFinished = args.getBoolean(FINISHED)
                        val index = if (markFinished) target.tracks.lastIndex else 0
                        val track = target.tracks[index]
                        val offset = if (markFinished) track.durationMs ?: 0L else 0L
                        if (book?.id == id) {
                            switching = true
                            try {
                                player.pause()
                                sleepJob?.cancel(); sleepJob = null; sleepChapterTarget = null
                                seekTo(index, offset)
                                finished = markFinished
                            } finally { switching = false }
                            publishSessionState()
                        }
                        val snapshot = coordinator.checkpoint(id, track.id, offset,
                            if (book?.id == id) player.playbackParameters.speed else target.progress?.speed ?: 1f, markFinished)
                            ?: run { result.set(SessionResult(SessionError.ERROR_INVALID_STATE)); return@launch }
                        val saved = awaitSave(snapshot)
                        saved.addListener({ result.set(runCatching { saved.get() }.getOrElse { SessionResult(SessionError.ERROR_IO) }) }, ContextCompat.getMainExecutor(this@PlaybackService))
                    } catch (_: Exception) { result.set(SessionResult(SessionError.ERROR_IO)) }
                }
                return result
            }
            if (customCommand.customAction == MARK_FINISHED) {
                if (book == null) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                player.pause()
                finished = true
                val snapshot = checkpoint() ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                return awaitSave(snapshot)
            }
            if (customCommand.customAction == SEEK_TRACK_POSITION) {
                val current = book ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                val trackId = args.getString(TRACK_ID)
                val offset = args.getLong(TRACK_POSITION_MS, -1)
                val index = current.tracks.indexOfFirst { it.id == trackId }
                if (args.getString(BOOK_ID) != current.id || index < 0 || offset < 0 ||
                    current.tracks[index].durationMs?.let { offset > it } == true)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                seekTo(index, offset)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SEEK_CHAPTER) {
                val current = book ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                val chapterId = args.getString(CHAPTER_ID)
                val spans = current.tracks.flatMapIndexed { index, track ->
                    track.chapters.map { ChapterSpan(index, it.startMs, it.endMs) }
                }
                val tracks = current.tracks.map { TrackSpan(it.id, it.durationMs) }
                val matches = current.tracks.flatMapIndexed { index, track ->
                    track.chapters.filter { it.id == chapterId }.map { index to it }
                }
                if (chapterId.isNullOrBlank() || matches.size != 1 || !Chapters.validate(spans, tracks))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val (index, chapter) = matches.single()
                seekTo(index, chapter.startMs)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SEEK_BOOK_POSITION) {
                val current = book ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                val position = args.getLong(BOOK_POSITION_MS, -1)
                val target = Timeline.fromBook(current.tracks.map { TrackSpan(it.id, it.durationMs) }, position)
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                seekTo(target.index, target.offsetMs)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == PAUSE_AND_SAVE || customCommand.customAction == UNLOAD_AND_SAVE) {
                player.pause()
                val snapshot = checkpoint()
                if (customCommand.customAction == UNLOAD_AND_SAVE) {
                    cancelIdleTimeout()
                    ++loadGeneration
                    sleepJob?.cancel(); sleepJob = null; sleepChapterTarget = null
                    userPausedAt = null
                    seekHistory.clear()
                    undoTarget = null
                    lastSeek = null
                    requestedSeek = null
                    publishSessionState()
                    switching = true
                    player.stop()
                    player.clearMediaItems()
                    sessionPlayer.setApprovedArtwork(null)
                    book = null
                    approvedAssets = emptyMap()
                    switching = false
                    val drainFuture = coordinator.unloadAndSave()
                    val future = SettableFuture.create<SessionResult>()
                    drainFuture.addListener({
                        val res = runCatching { drainFuture.get() }.getOrNull()
                        future.set(if (res == SaveResult.SUCCESS) SessionResult(SessionResult.RESULT_SUCCESS) else SessionResult(SessionError.ERROR_IO))
                        if (book == null) stopSelf()
                    }, ContextCompat.getMainExecutor(this@PlaybackService))
                    return future
                }
                snapshot ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                return awaitSave(snapshot)
            }
            if (customCommand.customAction == RETRY_SAVE) {
                coordinator.retrySave()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == SET_SPEED) {
                val speed = args.getFloat(SPEED, Float.NaN)
                if (!PlaybackSpeed.isValid(speed))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                player.setPlaybackSpeed(speed)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction != LOAD_BOOK)
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            if (!coordinator.canLoadBook(book?.id))
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
            val id = args.getString(BOOK_ID)
            if (id.isNullOrBlank() || id.length > 128)
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            cancelIdleTimeout()
            val result = SettableFuture.create<SessionResult>()
            pendingLoads.add(result)
            val generation = ++loadGeneration
            scope.launch {
                try {
                    val loaded = runCatching { repository.playableBook(id) }.getOrNull()
                    val files = runCatching { loaded?.tracks?.map { track -> track.documentSource?.let { ApprovedAudioSource.Document(it) } ?: store.resolve(track.relativePath)?.let { ApprovedAudioSource.PrivateFile(it) } } }.getOrNull()
                    val artwork = runCatching {
                        loaded?.coverPath?.let { path -> store.resolveCover(path) }?.let { file ->
                            withContext(Dispatchers.IO) {
                                if (file.length() !in 1..65_536) null
                                else file.inputStream().use { input ->
                                    val buffer = ByteArray(65_537)
                                    var count = 0
                                    while (count < buffer.size) {
                                        val read = input.read(buffer, count, buffer.size - count)
                                        if (read < 0) break
                                        if (read == 0) break
                                        count += read
                                    }
                                    if (count in 1..65_536) buffer.copyOf(count) else null
                                }
                            }
                        }
                    }.getOrNull()
                    val settingsSnapshot = runCatching { (application as AudiobookApplication).settings.playback.first() }.getOrNull()
                    if (generation != loadGeneration || loaded == null || files == null || files.any { it == null } || settingsSnapshot == null) {
                        pendingLoads.remove(result)
                        result.set(SessionResult(if (settingsSnapshot == null && loaded != null && files != null && files.none { it == null }) SessionError.ERROR_IO else SessionError.ERROR_BAD_VALUE))
                        return@launch
                    }
                    checkpoint()
                    sleepJob?.cancel(); sleepJob = null
                    sleepChapterTarget = null
                    userPausedAt = null
                    val recent = coordinator.getLatest(loaded.id)
                    seekHistory.clear()
                    undoTarget = null
                    lastSeek = null
                    requestedSeek = null
                    publishSessionState()
                    finished = recent?.finished ?: loaded.progress?.finished ?: false
                    switching = true
                    player.pause()
                    sessionPlayer.setApprovedArtwork(null)
                    book = loaded
                    approvedAssets = loaded.tracks.zip(files).associate { (track, file) -> track.assetId to file!! }
                    val items = loaded.tracks.map { track ->
                        MediaItem.Builder().setMediaId(track.id).setUri("paudio://asset/${track.assetId}")
                            // Non-null placeholders override artwork and artist supplied by embedded tags.
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(loaded.title.take(512)).setArtist(loaded.author?.take(512) ?: "")
                                .setArtworkUri(android.net.Uri.EMPTY).setArtworkData(byteArrayOf(), null)
                                .setExtras(Bundle().apply { putString(BOOK_ID, loaded.id) }).build()).build()
                    }
                    val saved = loaded.progress
                    val savedTrack = recent?.trackId ?: saved?.trackId
                    val index = loaded.tracks.indexOfFirst { it.id == savedTrack }.takeIf { it >= 0 } ?: 0
                    val offset = (recent?.position ?: saved?.offsetMs ?: 0).coerceIn(0, loaded.tracks[index].durationMs?.coerceAtLeast(0) ?: Long.MAX_VALUE)
                    player.setMediaItems(items, index, offset)
                    sessionPlayer.setApprovedArtwork(artwork)
                    val effectiveSpeed = PlaybackSpeed.resolve(recent?.speed, saved?.speed, settingsSnapshot.defaultSpeed)
                    player.setPlaybackSpeed(effectiveSpeed)
                    player.prepare()
                    switching = false
                    ensureStarted()
                    cancelIdleTimeout()
                    gate.reset(offset, SystemClock.elapsedRealtime())
                    pendingLoads.remove(result)
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    if (session.connectedControllers.none { isUiController(it) }) checkIdleTimeout()
                } catch (e: Exception) {
                    pendingLoads.remove(result)
                    result.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return result
        }
    }
    private fun recordSeekRequest(index: Int, positionMs: Long) {
        finished = false
        if (index >= 0 && positionMs >= 0) {
            // ExoPlayer can clamp an exact-end request slightly below duration before
            // reporting ENDED. Keep the requested baseline, before synchronous events.
            requestedSeek = TrackPosition(index, positionMs)
            lastSeek = requestedSeek
            val target = sleepChapterTarget
            if (target != null && PlaybackPolicy.shouldCancelSleepChapterOnSeek(target.first, target.second, index, positionMs)) {
                sleepJob?.cancel()
                sleepJob = null
                sleepChapterTarget = null
            }
        }
    }
    private fun seekTo(index: Int, positionMs: Long) {
        recordSeekRequest(index, positionMs)
        player.seekTo(index, positionMs)
    }
    /** Cache only newly known media time from ordinary playback, without extra source scanning. */
    private fun cacheLinkedDuration() {
        val current=book ?: return
        val index=player.currentMediaItemIndex
        val track=current.tracks.getOrNull(index) ?: return
        val duration=player.duration.takeIf { it>=0 && it!=C.TIME_UNSET } ?: return
        if (track.documentSource==null || track.durationMs!=null) return
        book=current.copy(tracks=current.tracks.mapIndexed { i,t -> if (i==index) t.copy(durationMs=duration) else t })
        (application as AudiobookApplication).cleanupScope.launch {
            (application as AudiobookApplication).library.library().fillDuration(track.assetId,duration)
        }
    }

    private fun publishSessionState() {
        val currentBookId = book?.id
        session.setSessionExtras(Bundle().apply {
            putBoolean(SAVE_FAILED, currentBookId != null && coordinator.isBookFailed(currentBookId))
            putBoolean(UNDO_AVAILABLE, seekHistory.isNotEmpty())
            putBoolean(SLEEP_ACTIVE, sleepJob?.isActive == true || sleepChapterTarget != null)
        })
    }
    private fun awaitSave(snapshot: ProgressSnapshot): ListenableFuture<SessionResult> {
        val future = SettableFuture.create<SessionResult>()
        val coordinatorFuture = coordinator.awaitSave(snapshot)
        coordinatorFuture.addListener({
            val res = runCatching { coordinatorFuture.get() }.getOrNull()
            future.set(when (res) {
                SaveResult.SUCCESS -> SessionResult(SessionResult.RESULT_SUCCESS)
                SaveResult.ERROR_IO -> SessionResult(SessionError.ERROR_IO)
                else -> SessionResult(SessionError.ERROR_UNKNOWN)
            })
        }, ContextCompat.getMainExecutor(this@PlaybackService))
        return future
    }
    private fun monitorSleepChapter() {
        val target = sleepChapterTarget ?: return
        sleepJob = scope.launch {
            while (player.currentMediaItemIndex < target.first ||
                (player.currentMediaItemIndex == target.first && player.currentPosition < target.second)) delay(250)
            val currentTarget = sleepChapterTarget
            sleepChapterTarget = null
            sleepJob = null
            publishSessionState()
            if (currentTarget != null && PlaybackPolicy.shouldCorrectDecoderOvershoot(
                    currentTarget.first, currentTarget.second,
                    player.currentMediaItemIndex, player.currentPosition
                )) {
                seekTo(currentTarget.first, currentTarget.second)
            }
            player.pause()
            checkpoint()
        }
    }
    private fun checkpoint(): ProgressSnapshot? {
        if (destroyed) return null
        val state = checkpointAt(player.currentMediaItemIndex, player.currentPosition, finished)
        gate.reset(player.currentPosition, SystemClock.elapsedRealtime())
        return state
    }

    private fun checkpointAfterPauseAcknowledged() {
        if (destroyed || player.playWhenReady || book == null) return
        val pausedBook = book?.id
        val generation = loadGeneration
        // pause() updates public flags before the playback thread has stopped its
        // audio clock. Queue behind that operation, then read the acknowledged
        // position on the application looper without blocking either thread.
        player.createMessage { _, _ ->
            if (!destroyed && generation == loadGeneration && book?.id == pausedBook && !player.playWhenReady) {
                checkpoint()
            }
        }.setLooper(player.applicationLooper).send()
    }
    private fun checkpointAt(index: Int, position: Long, finished: Boolean = false): ProgressSnapshot? {
        if (destroyed) return null
        val current = book ?: return null
        val track = current.tracks.getOrNull(index) ?: return null
        return coordinator.checkpoint(
            bookId = current.id,
            trackId = track.id,
            position = position,
            speed = player.playbackParameters.speed,
            finished = finished
        )
    }
    private fun isUiController(controller: MediaSession.ControllerInfo): Boolean {
        return PlaybackPolicy.isUiController(
            isSameUid = controller.uid == Process.myUid(),
            isNotificationController = session.isMediaNotificationController(controller),
            isAutomotiveController = session.isAutomotiveController(controller),
            isAutoCompanionController = session.isAutoCompanionController(controller)
        )
    }

    private fun ensureStarted() {
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {}
    }

    private fun cancelIdleTimeout() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun checkIdleTimeout(disconnecting: MediaSession.ControllerInfo? = null) {
        if (destroyed) return
        val hasUiController = session.connectedControllers.any {
            it != disconnecting && isUiController(it)
        }
        if (!PlaybackPolicy.shouldScheduleIdleTimeout(hasUiController, player.isPlaying, book != null)) {
            cancelIdleTimeout()
            return
        }
        if (idleJob == null) {
            val startGeneration = loadGeneration
            idleJob = scope.launch {
                delay(PlaybackPolicy.IDLE_TIMEOUT_MS)
                if (destroyed || loadGeneration != startGeneration) return@launch
                if (!player.isPlaying &&
                    session.connectedControllers.none { isUiController(it) }) {
                    val snapshot = checkpoint()
                    val generation = loadGeneration
                    if (snapshot != null) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                awaitSave(snapshot).get(PlaybackPolicy.SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            }
                        }
                    }
                    val hasUi = session.connectedControllers.any { isUiController(it) }
                    if (PlaybackPolicy.shouldCompleteShutdown(
                            isDestroyed = destroyed,
                            isPlaying = player.isPlaying,
                            loadGenerationUnchanged = (loadGeneration == startGeneration && loadGeneration == generation),
                            hasUiController = hasUi
                        )) {
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        checkpoint()
        destroyed = true
        cancelIdleTimeout()
        pendingLoads.forEach { it.set(SessionResult(SessionError.ERROR_UNKNOWN)) }
        pendingLoads.clear()
        session.release()
        player.release()
        coordinator.close()
        scope.cancel()
        super.onDestroy()
    }
}
