package com.dynag.memoonly

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.InputStream

/**
 * Snapshot of whatever MediaSession the phone is currently playing.
 *
 * Third-party apps cannot bind a MediaBrowser for an arbitrary player, but a
 * granted NotificationListener service may list the active sessions through
 * [MediaSessionManager.getActiveSessions], which is the public, permission
 * gated way to know the title, artist, cover and position of any player.
 */
class NowPlaying(private val context: Context) {

    data class Track(
        val id: Int,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean
    )

    private val tag = "NowPlaying"

    /** Cheap fingerprint used to detect a track change across polls. */
    fun fingerprint(): String {
        val controller = activeController() ?: return "none"
        val md = runCatching { controller.metadata }.getOrNull() ?: return "none"
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        return "$title|$artist"
    }

    /** True when a session is present and reports STATE_PLAYING. */
    fun isPlaying(): Boolean {
        val controller = activeController() ?: return false
        val state = runCatching { controller.playbackState }.getOrNull()
        return state?.state == PlaybackState.STATE_PLAYING
    }

    /** Read the active session, or null when nothing is playing or readable. */
    fun snapshot(): Track? {
        val controller = activeController() ?: return null
        val md = runCatching { controller.metadata }.getOrNull() ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        if (title == null) return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: ""
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val state = runCatching { controller.playbackState }.getOrNull()
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val position = positionOf(controller, state, duration)
        val id = 31 * title.hashCode() + artist.hashCode()
        return Track(id, title, artist, duration, position, playing)
    }

    /** Toggle play/pause on the active session (glasses button request). */
    fun toggle(): Boolean {
        val controller = activeController() ?: return false
        val playing = runCatching { controller.playbackState }.getOrNull()?.state ==
            PlaybackState.STATE_PLAYING
        return runCatching {
            if (playing) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Decode the current cover art and return it as GRAY_4 nibbles packed two
     * pixels per byte (even x in the high nibble), scaled to fit [maxEdge].
     * Null when the session carries no artwork.
     */
    fun coverNibbles(maxEdge: Int): Pair<Bitmap, ByteArray>? {
        val controller = activeController() ?: return null
        val md = runCatching { controller.metadata }.getOrNull() ?: return null
        val source = decodeCover(md) ?: return null
        val scaled = fit(source, maxEdge)
        if (scaled !== source) source.recycle()
        val stride = (scaled.width + 1) / 2
        val out = ByteArray(stride * scaled.height)
        val row = IntArray(scaled.width)
        for (y in 0 until scaled.height) {
            scaled.getPixels(row, 0, scaled.width, 0, y, scaled.width, 1)
            for (x in 0 until scaled.width) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Rec. 601 luma, rounded to the nearest of the 16 shades.
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                val shade = (luma * 15 + 127) / 255
                val at = y * stride + (x shr 1)
                if ((x and 1) == 0) {
                    out[at] = (shade shl 4).toByte()
                } else {
                    out[at] = (out[at].toInt() or shade).toByte()
                }
            }
        }
        return scaled to out
    }

    // ---- internals ------------------------------------------------------

    private fun activeController(): MediaController? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val component = ComponentName(context, NowPlayingListener::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull()
            ?: return null
        // Prefer a session that is actually playing, else the first with metadata.
        var fallback: MediaController? = null
        for (session in sessions) {
            val state = runCatching { session.playbackState }.getOrNull()
            if (state?.state == PlaybackState.STATE_PLAYING) return session
            if (fallback == null && runCatching { session.metadata }.getOrNull() != null) {
                fallback = session
            }
        }
        return fallback
    }

    /**
     * Extrapolate the position while playing: PlaybackState.position is only
     * refreshed when the player reports a change, so a paused-at-t=0 state
     * would otherwise freeze the progress bar.
     */
    private fun positionOf(
        controller: MediaController,
        state: PlaybackState?,
        durationMs: Long
    ): Long {
        if (state == null) return 0L
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            val rate = state.playbackSpeed.takeIf { it > 0f } ?: 1f
            pos += (elapsed * rate).toLong()
        }
        if (durationMs > 0L) pos = pos.coerceIn(0L, durationMs)
        return pos.coerceAtLeast(0L)
    }

    private fun decodeCover(md: MediaMetadata): Bitmap? {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) return bmp
        val desc: MediaDescription? = md.description
        val icon = desc?.iconBitmap
        if (icon != null) return icon
        val uri = desc?.iconUri ?: return null
        return decodeUri(uri)
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        return try {
            val stream: InputStream? = when (uri.scheme) {
                "content", "android.resource", "file" ->
                    context.contentResolver.openInputStream(uri)
                else -> null
            } ?: return null
            stream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.d(tag, "cover decode failed: ${e.message}")
            null
        }
    }

    private fun fit(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge && longest > 0) return source
        val scale = maxEdge.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}
