package com.dynag.memoonly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps the "now playing" mirror alive while the phone screen is off.
 *
 * It polls the active MediaSession once a second and pushes what changed to
 * the glasses music plugin: metadata when the track changes, a light progress
 * frame every tick, and the cover art (decoded, quantised to 16 grays and LZ4
 * compressed) when the track changes. The glasses button comes back as a
 * toggle request, which we forward to the MediaSession.
 *
 * A foreground service keeps the poll running when the player is in the
 * background and the display is off.
 */
class MusicMirrorService : Service(),
    GlassesHudClient.Listener {

    private lateinit var glasses: GlassesHudClient
    private lateinit var nowPlaying: NowPlaying
    private val handler = Handler(Looper.getMainLooper())

    private var lastFingerprint = ""
    private var lastCoverFingerprint = ""
    private var lastTrackId = 0
    private var lastIdle = false
    private var glassesLinked = false
    private var idleConnected = false
    private var lastStatus = "Miroir actif"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        glasses = GlassesHudClient(this, this)
        nowPlaying = NowPlaying(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground("Miroir actif")
                setMirrorPref(true)
                idleConnected = false
                connectGlasses()
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            ACTION_CONNECT -> {
                // Only reach for the glasses, never for the player: this is the
                // "Connecter les lunettes" button of the config tab, and the
                // single connection the whole app shares.
                startAsForeground("Miroir actif")
                idleConnected = true
                connectGlasses()
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            ACTION_STOP -> stopSelf()
            ACTION_YIELD -> yieldToOfficial()
            else -> startAsForeground("Miroir actif")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setMirrorPref(false)
        handler.removeCallbacks(ticker)
        if (glassesLinked) glasses.sendIdle()
        glasses.disconnect()
        super.onDestroy()
    }

    // ---- glasses -------------------------------------------------------

    private fun connectGlasses() {
        glassesLinked = true
        if (glasses.isReady) return
        glasses.startLink()
    }

    override fun onGlassesStatus(message: String) {
        // The music tab shows the journal; the notification keeps the last line.
        UiState.log(this, message)
        if (message.startsWith("Plugin musique")) lastStatus = message
        updateNotification(currentText())
    }

    override fun onGlassesReady() {
        // Force a full refresh once the link comes up.
        lastFingerprint = ""
        lastCoverFingerprint = ""
        lastIdle = false
        pushSnapshot()
    }

    override fun onGlassesDisconnected() {
        // The client retries on its own; the ticker keeps polling. Nothing new
        // is opened here, so a link released on purpose stays released.
        glassesLinked = false
    }

    /**
     * The user handed the glasses back to the official app: leave the single
     * phone-facing link to it. The mirror stops too, so nothing reconnects.
     */
    private fun yieldToOfficial() {
        glassesLinked = false
        setMirrorPref(false)
        handler.removeCallbacks(ticker)
        glasses.releaseLink(getString(R.string.msg_yield_requested))
        stopSelf()
    }

    override fun onMusicReady() {
        lastFingerprint = ""
        lastCoverFingerprint = ""
        lastIdle = false
        pushSnapshot()
    }

    override fun onCardioReady() {
        // The workout service owns the cardio HUD stream.
    }

    override fun onToggleRequest() {
        nowPlaying.toggle()
        handler.postDelayed({ pushSnapshot() }, 250)
    }

    override fun onGlassesChooser(devices: List<Pair<String, String>>) {
        // No foreground UI here; the config tab drives the first pairing, but
        // say so instead of failing silently.
        UiState.log(this, getString(R.string.msg_glasses_in_config))
    }

    // ---- polling loop --------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            pushSnapshot()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun pushSnapshot() {
        val track = nowPlaying.snapshot()
        if (track == null) {
            if (idleConnected) {
                if (!lastIdle) {
                    lastIdle = true
                    lastFingerprint = ""
                    lastCoverFingerprint = ""
                    glasses.sendIdle()
                }
                updateNotification(currentText())
            }
            return
        }
        // A track appeared while only the glasses were connected: start the
        // mirror for good.
        idleConnected = false
        lastIdle = false
        val fingerprint = "${track.title}|${track.artist}"
        val coverChanged = fingerprint != lastCoverFingerprint
        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint
            lastTrackId = track.id
            glasses.sendTrackNow(
                GmFrame.trackMeta(
                    trackId = track.id,
                    durationMs = track.durationMs,
                    positionMs = track.positionMs,
                    playing = track.playing,
                    title = track.title,
                    artist = track.artist
                )
            )
            updateNotification("${track.title} — ${track.artist}".trim(' ', '—'))
        } else {
            glasses.sendTrackPayload(
                GmFrame.trackProgress(track.id, track.positionMs, track.playing)
            )
        }
        if (coverChanged) {
            lastCoverFingerprint = fingerprint
            sendCover()
        }
    }

    private fun sendCover() {
        val cover = nowPlaying.coverNibbles(COVER_MAX_EDGE) ?: return
        val (bitmap, nibbles) = cover
        val width = bitmap.width
        val height = bitmap.height
        val lz4 = Lz4.compressBlock(nibbles)
        bitmap.recycle()
        glasses.sendCoverPayload(
            GmFrame.coverPayload(width, height, lz4)
        )
    }

    private fun setMirrorPref(running: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_RUNNING, running).apply()
    }

    // ---- foreground notification ---------------------------------------

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Never let a foreground-start refusal take the whole app down
            // (e.g. restricted FGS type on a given OEM build).
            Log.w("MusicMirrorService", "startForeground refused", e)
            stopSelf()
        }
    }

    private fun currentText(): String {
        val track = nowPlaying.snapshot()
        val label = track?.let { "${it.title} — ${it.artist}".trim(' ', '—') }
        return if (label.isNullOrBlank()) lastStatus else "$label · $lastStatus"
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicMirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "music_mirror"
        private const val PREFS = "memo_mirror"
        private const val PREF_RUNNING = "mirror_running"
        private const val NOTIF_ID = 42
        private const val TICK_MS = 1000L
        private const val COVER_MAX_EDGE = 240

        const val ACTION_START = "com.dynag.memoonly.action.START_MIRROR"
        const val ACTION_CONNECT = "com.dynag.memoonly.action.CONNECT_GLASSES"
        const val ACTION_STOP = "com.dynag.memoonly.action.STOP_MIRROR"
        const val ACTION_YIELD = "com.dynag.memoonly.action.YIELD_TO_OFFICIAL"

        fun start(context: Context) {
            val intent = Intent(context, MusicMirrorService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Connect the glasses only (no mirroring until a track is playing). */
        fun connectOnly(context: Context) {
            val intent = Intent(context, MusicMirrorService::class.java)
                .setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** True while a mirror was started and not stopped since. */
        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_RUNNING, false)

        fun stop(context: Context) {
            context.startService(
                Intent(context, MusicMirrorService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Stop the mirror and hand the glasses to the official app. Cardio is
         * told to stop tracking first so its stream cannot claim the link
         * again right after we release it.
         */
        fun yieldFromGlasses(context: Context) {
            CardioService.yield(context)
            context.startService(
                Intent(context, MusicMirrorService::class.java).setAction(ACTION_YIELD)
            )
        }

        /** Called by [NowPlayingListener] when a notification arrives or leaves. */
        fun notifySessionsChanged() {
            // The service polls on its own ticker; this hook keeps the contract
            // explicit for the notification listener and allows a future
            // push-driven mode without changing callers.
        }
    }
}
