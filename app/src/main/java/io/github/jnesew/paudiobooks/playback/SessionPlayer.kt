package io.github.jnesew.paudiobooks.playback

import android.os.Bundle
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/** Restricts all session-facing metadata to the app's own bounded library values. */
@UnstableApi
internal class SessionPlayer(player: Player, private val genericTitle: String,
    private val onSeekRequested: (Int, Long) -> Unit = { _, _ -> }) : ForwardingSimpleBasePlayer(player) {
    private var showDetails = true
    private var approvedArtwork: ByteArray? = null

    fun setApprovedArtwork(data: ByteArray?) {
        approvedArtwork = data?.copyOf()
        invalidateState()
    }

    fun setShowDetails(show: Boolean) {
        if (showDetails != show) {
            showDetails = show
            invalidateState()
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        onSeekRequested(mediaItemIndex, positionMs)
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun getState(): State {
        val original = super.getState()
        val items = original.playlist.map { entry ->
            val source = entry.mediaItem.mediaMetadata
            val metadata = MediaMetadata.Builder()
                .setTitle(if (showDetails) source.title else genericTitle)
                .setArtist(if (showDetails) source.artist else null)
                .setArtworkData(if (showDetails) approvedArtwork else null, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setExtras(Bundle().apply { source.extras?.getString(PlaybackService.BOOK_ID)?.let { putString(PlaybackService.BOOK_ID, it) } })
                .build()
            entry.buildUpon().setTracks(Tracks.EMPTY).setMediaMetadata(metadata)
                .setMediaItem(entry.mediaItem.buildUpon().setMediaMetadata(metadata).build()).build()
        }
        return original.buildUpon().setPlaylist(items).setPlaylistMetadata(MediaMetadata.EMPTY)
            .setTimedMetadata(Metadata()).build()
    }
}
