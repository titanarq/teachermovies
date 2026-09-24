package com.teachermovies.player.vlc

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.SubtitleExtraction
import com.teachermovies.player.api.Track
import com.teachermovies.player.api.VideoSurfaceHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * [Player] over libVLC Android 3.x (ADR-0001 §2): the only class in the product that touches an
 * `org.videolan` type, and the reason no other module has to.
 *
 * Three things about how it is built:
 *
 * - **The native objects are lazy.** `LibVLC` and `MediaPlayer` load `libvlc.so` the first time
 *   they are used, so nothing here is created until [open] or [attach] asks for it, and [release]
 *   frees them again -- [Player.release] promises that [Player.open] still works afterwards, which
 *   it does because the next call recreates them.
 * - **libVLC's events are the bridge into the flows.** `MediaPlayer` reports what the media does
 *   through its own native thread, never through a coroutine; the listener below is that thread's
 *   only job, and writing a `MutableStateFlow.value` from it is safe. The listener answers with
 *   read-only getters -- tracks and the selected ids -- and never calls back into a control, which
 *   is what libVLC's own documentation warns can deadlock its event thread.
 * - **The controls are synchronous and run on the caller's thread**, as [Player] specifies. libVLC's
 *   `stop()` can block for as long as the decoder takes to wind down, so a caller that replaces the
 *   open file with [open] should do it off the main thread.
 *
 * The video surface is the separate [VideoSurfaceHost] half: [attach] takes the `FrameLayout` a
 * screen already has and puts the `VLCVideoLayout` libVLC needs inside it, which is what keeps that
 * type -- and every other `org.videolan` one -- out of `:app-tv`.
 */
class VlcPlayer(
    context: Context,
    /** Longest [extractTextSubtitle] may read the media for before it gives up with `Failed`. */
    private val extractionTimeout: Duration = 2.minutes,
    /**
     * libVLC's `:file-caching` for a file opened with `growing = true`, in milliseconds: how much it
     * reads ahead, and so how long a read may wait for bytes the torrent engine has not written yet.
     */
    private val fileCachingMs: Int = DEFAULT_FILE_CACHING_MS,
) : Player,
    VideoSurfaceHost {
    // The application context: this player outlives any activity that created it, and `LibVLC` only
    // needs a context to find the native libraries and the cache directory.
    private val appContext: Context = context.applicationContext

    private val mutableState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    private val mutablePositionMs = MutableStateFlow(0L)
    private val mutableDurationMs = MutableStateFlow(0L)
    private val mutableAudioTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableSubtitleTracks = MutableStateFlow<List<Track>>(emptyList())
    private val mutableSelectedAudioId = MutableStateFlow<String?>(null)
    private val mutableSelectedSubtitleId = MutableStateFlow<String?>(null)

    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    override val positionMs: StateFlow<Long> = mutablePositionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = mutableDurationMs.asStateFlow()
    override val audioTracks: StateFlow<List<Track>> = mutableAudioTracks.asStateFlow()
    override val subtitleTracks: StateFlow<List<Track>> = mutableSubtitleTracks.asStateFlow()
    override val selectedAudioId: StateFlow<String?> = mutableSelectedAudioId.asStateFlow()
    override val selectedSubtitleId: StateFlow<String?> = mutableSelectedSubtitleId.asStateFlow()

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var media: Media? = null
    private var videoLayout: VLCVideoLayout? = null

    /** The file passed to the current [open], which [extractTextSubtitle] reads; null once released. */
    @Volatile
    private var openedFile: File? = null

    // region VideoSurfaceHost

    override fun attach(view: FrameLayout) {
        detach()
        val layout = VLCVideoLayout(view.context)
        view.addView(
            layout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // No DisplayManager: libVLC uses one for cast/secondary screens, which this product does
        // not have, and every path that reads it tolerates null.
        mediaPlayer().attachViews(layout, null, RENDER_SUBTITLES, USE_TEXTURE_VIEW)
        videoLayout = layout
    }

    override fun detach() {
        val layout = videoLayout ?: return
        mediaPlayer?.detachViews()
        (layout.parent as? ViewGroup)?.removeView(layout)
        videoLayout = null
    }

    // endregion

    // region Player

    override fun open(
        file: File,
        startPositionMs: Long,
        growing: Boolean,
    ) {
        val player = mediaPlayer()
        unload(player)
        val loaded =
            Media(libVlc(), Uri.fromFile(file)).apply {
                setHWDecoderEnabled(HARDWARE_DECODING, FORCE_HARDWARE_DECODING)
                VlcMediaOptions.forMedia(startPositionMs, growing, fileCachingMs).forEach(::addOption)
            }
        media = loaded
        openedFile = file
        player.setMedia(loaded)
        resetFlows(startPositionMs)
        mutableState.value = PlayerState.Opening
        player.play()
    }

    override fun play() {
        mediaPlayer?.play()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun togglePlayPause() {
        when (mutableState.value) {
            PlayerState.Playing -> pause()

            PlayerState.Paused -> play()

            // Nothing to toggle: no media is loaded, it is not ready, or playback is over.
            PlayerState.Idle,
            PlayerState.Opening,
            PlayerState.Ended,
            is PlayerState.Error,
            -> Unit
        }
    }

    override fun seekTo(ms: Long) {
        val player = mediaPlayer ?: return
        val target = ms.coerceIn(0L, mutableDurationMs.value)
        player.setTime(target)
        mutablePositionMs.value = target
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(mutablePositionMs.value + deltaMs)
    }

    override fun selectAudio(id: String) {
        val player = mediaPlayer ?: return
        val trackId = id.toIntOrNull() ?: return
        if (player.setAudioTrack(trackId)) {
            mutableSelectedAudioId.value = id
        }
    }

    override fun selectSubtitle(id: String?) {
        val player = mediaPlayer ?: return
        // libVLC turns subtitles off by selecting its `-1` pseudo-track, which `VlcTrackMapper`
        // keeps out of the track list and `Player` reports as a null id. An id that is not one of
        // ours is ignored rather than read as "off".
        val trackId =
            when (id) {
                null -> VlcTrackMapper.DISABLE_TRACK_ID
                else -> id.toIntOrNull() ?: return
            }
        if (player.setSpuTrack(trackId)) {
            mutableSelectedSubtitleId.value = id
        }
    }

    override fun addExternalSubtitle(
        file: File,
        select: Boolean,
    ) {
        val player = mediaPlayer ?: return
        if (player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), select)) {
            publishTracks()
        }
    }

    /**
     * Reads the embedded track straight out of the file -- libVLC 3 has no SRT/ASS muxer (#115) --
     * with [EmbeddedSubtitles], which picks the Matroska or MP4 reader by the container, on
     * [Dispatchers.IO] under [extractionTimeout]. It only reads the file passed to [open]; the
     * playback `MediaPlayer` and its flows are never touched.
     */
    override suspend fun extractTextSubtitle(
        trackId: String,
        destination: File,
    ): SubtitleExtraction {
        val file = openedFile ?: return SubtitleExtraction.TrackNotFound
        val vlcTracks = mutableSubtitleTracks.value
        if (vlcTracks.none { it.id == trackId }) return SubtitleExtraction.TrackNotFound
        return withTimeoutOrNull(extractionTimeout) {
            runInterruptible(Dispatchers.IO) {
                EmbeddedSubtitles.extract(file, trackId, vlcTracks, destination)
            }
        } ?: SubtitleExtraction.Failed("timed out")
    }

    override fun release() {
        detach()
        mediaPlayer?.let { player ->
            unload(player)
            player.release()
        }
        libVlc?.release()
        mediaPlayer = null
        libVlc = null
        openedFile = null
        resetFlows(0L)
        mutableState.value = PlayerState.Idle
    }

    // endregion

    private fun libVlc(): LibVLC = libVlc ?: LibVLC(appContext, VlcMediaOptions.LIB_VLC).also { libVlc = it }

    private fun mediaPlayer(): MediaPlayer =
        mediaPlayer
            ?: MediaPlayer(libVlc()).also { created ->
                created.setEventListener { event -> onVlcEvent(event) }
                mediaPlayer = created
            }

    /** Stops whatever is loaded and frees its [Media], leaving the player ready for the next file. */
    private fun unload(player: MediaPlayer) {
        val loaded = media ?: return
        player.stop()
        player.setMedia(null)
        loaded.release()
        media = null
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                mutableState.value = PlayerState.Playing
            }

            MediaPlayer.Event.Paused -> {
                mutableState.value = PlayerState.Paused
            }

            MediaPlayer.Event.EndReached -> {
                mutablePositionMs.value = mutableDurationMs.value
                mutableState.value = PlayerState.Ended
            }

            MediaPlayer.Event.EncounteredError -> {
                mutableState.value = PlayerState.Error(PLAYBACK_FAILED)
            }

            MediaPlayer.Event.TimeChanged -> {
                mutablePositionMs.value = event.timeChanged
            }

            MediaPlayer.Event.LengthChanged -> {
                mutableDurationMs.value = event.lengthChanged
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            -> {
                publishTracks()
            }

            // libVLC also selects tracks on its own -- the default audio and subtitle of a freshly
            // opened file -- so the selection is read back from the player, not only written to it.
            MediaPlayer.Event.ESSelected -> {
                publishTracks()
                publishSelection()
            }
        }
    }

    private fun publishTracks() {
        val player = mediaPlayer ?: return
        mutableAudioTracks.value = player.audioTracks.toTracks()
        mutableSubtitleTracks.value = player.spuTracks.toTracks()
    }

    private fun publishSelection() {
        val player = mediaPlayer ?: return
        mutableSelectedAudioId.value = player.audioTrack.toSelectedId()
        mutableSelectedSubtitleId.value = player.spuTrack.toSelectedId()
    }

    /**
     * Back to a media with nothing known about it yet, reporting [startPositionMs] right away so a
     * resume shows the seek bar where playback will continue instead of jumping there a moment
     * later.
     */
    private fun resetFlows(startPositionMs: Long) {
        mutablePositionMs.value = startPositionMs
        mutableDurationMs.value = 0L
        mutableAudioTracks.value = emptyList()
        mutableSubtitleTracks.value = emptyList()
        mutableSelectedAudioId.value = null
        mutableSelectedSubtitleId.value = null
    }

    /** libVLC's track descriptions are two parallel arrays, which is what [VlcTrackMapper] takes. */
    private fun Array<MediaPlayer.TrackDescription>?.toTracks(): List<Track> {
        val descriptions = this ?: return emptyList()
        return VlcTrackMapper.map(
            ids = descriptions.map { it.id }.toIntArray(),
            names = descriptions.map { it.name.orEmpty() }.toTypedArray(),
        )
    }

    /** A libVLC track id of `-1` means "none", which [Player] reports as a null id. */
    private fun Int.toSelectedId(): String? = if (this < 0) null else toString()

    private companion object {
        /** [fileCachingMs] unless the caller tunes it: three seconds of read cache on a growing file. */
        const val DEFAULT_FILE_CACHING_MS = 3000

        /** `Media.setHWDecoderEnabled(forVideo, force)`: decode video in hardware when the SoC can. */
        const val HARDWARE_DECODING = true

        /**
         * `Media.setHWDecoderEnabled(forVideo, force)`, second argument: do not insist on it. A
         * decoder that fails on one file falls back to software instead of failing playback, which
         * matters more than speed for the arbitrary MKVs this product plays.
         */
        const val FORCE_HARDWARE_DECODING = false

        /** `MediaPlayer.attachViews(..., subtitles, ...)`: give the video a subtitle surface. */
        const val RENDER_SUBTITLES = true

        /**
         * `MediaPlayer.attachViews(..., useTextureView)`: stay on a `SurfaceView`, which is what
         * libVLC wants for video and costs less than a `TextureView`.
         */
        const val USE_TEXTURE_VIEW = false

        /** [PlayerState.Error.message]; `EncounteredError` carries no reason to put in it. */
        const val PLAYBACK_FAILED = "Playback failed"
    }
}
