package com.dynag.memoonly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * MemOnly — the glasses' home.
 *
 * A top bar opens a closable drawer that lists every app MemOnly hosts plus the
 * configuration, so a new MemoMind app is a new row here, not a new launcher
 * icon. Whichever app is open owns the glasses screen; the configuration owns
 * the single connection they all share.
 *
 * Closing the app (back, swipe from recents, or leaving for good) releases the
 * glasses at once: the link is single and belongs to the official app while
 * MemOnly is not in use. A deliberate workout survives that, because its
 * service keeps running and reopens the link on its own.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var badge: TextView
    private lateinit var drawerPanel: View
    private lateinit var drawerScrim: View
    private lateinit var menuCardio: Button
    private lateinit var menuMusic: Button
    private lateinit var menuConfig: Button

    private val handler = Handler(Looper.getMainLooper())
    private var current: AppScreen = AppScreen.CARDIO
    private var drawerOpen = false
    private var setAsideForDisconnect = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) autoConnect()
    }

    private val statusListener = object : GlassesHudClient.Listener {
        override fun onGlassesStatus(message: String) = Unit
        override fun onGlassesReady() {
            refreshBadge()
        }

        override fun onGlassesDisconnected() {
            refreshBadge()
        }

        override fun onGlassesChooser(devices: List<Pair<String, String>>) = Unit
        override fun onToggleRequest() = Unit
        override fun onMusicReady() {
            refreshBadge()
        }

        override fun onCardioReady() {
            refreshBadge()
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                drawerOpen -> closeDrawer()
                current != AppScreen.CARDIO -> select(AppScreen.CARDIO)
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // One journal for both glasses clients: a cardio line and a music line
        // must be readable side by side on the config tab.
        GlassesHudClient.setJournalSink { line ->
            UiState.log(applicationContext, line)
        }

        titleText = findViewById(R.id.titleText)
        badge = findViewById(R.id.glassesBadge)
        drawerPanel = findViewById(R.id.drawerPanel)
        drawerScrim = findViewById(R.id.drawerScrim)
        menuCardio = findViewById(R.id.menuCardio)
        menuMusic = findViewById(R.id.menuMusic)
        menuConfig = findViewById(R.id.menuConfig)

        findViewById<View>(R.id.btnMenu).setOnClickListener { openDrawer() }
        drawerScrim.setOnClickListener { closeDrawer() }
        menuCardio.setOnClickListener { select(AppScreen.CARDIO) }
        menuMusic.setOnClickListener { select(AppScreen.MUSIC) }
        menuConfig.setOnClickListener { select(AppScreen.CONFIG) }
        badge.setOnClickListener { select(AppScreen.CONFIG) }

        GlassesHudClient.registerStatusListener(statusListener)
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState == null) {
            UiState.log(this, "MemoMind a pris la main sur les lunettes.")
            // Report the screen before the services ask for the link, so the
            // first plugin frame already matches what is on the phone.
            AppScreen.activate(this, AppScreen.last(this))
        }
        select(currentOf(savedInstanceState))

        handler.postDelayed(badgeTicker, 1000L)
        maybeAutoConnect()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app releases the single glasses link, unless a workout
        // or a mirror is still running in the background: those keep it on
        // purpose and reopen it by themselves.
        if (isFinishing && !setAsideForDisconnect && !GlassesHudClient.isYielded()) {
            if (!CardioService.isRunning(this) && !MusicMirrorService.isRunning(this)) {
                setAsideForDisconnect = true
                CardioService.yield(this)
                MusicMirrorService.stop(this)
                GlassesHudClient.disconnectForGood(this, getString(R.string.msg_glasses_closed))
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        GlassesHudClient.unregisterStatusListener(statusListener)
        super.onDestroy()
    }

    /** Called by fragments whose "connect glasses" button opens the config. */
    fun openConfigTab() {
        select(AppScreen.CONFIG)
    }

    /** The config tab owns the single connect/disconnect button. */
    fun connectGlasses() {
        MusicMirrorService.connectOnly(this)
        handler.postDelayed({ AppScreen.activate(this, current) }, 1200L)
    }

    /**
     * Close the link on purpose: tracking and mirroring stop first, then the
     * socket closes without scheduling a retry, so the official app can take
     * the glasses back. The remembered address is kept for one-tap reconnect.
     */
    fun disconnectGlasses(reason: String) {
        setAsideForDisconnect = true
        CardioService.yield(this)
        MusicMirrorService.stop(this)
        GlassesHudClient.disconnectForGood(this, reason)
        UiState.log(this, getString(R.string.msg_glasses_disconnected))
        handler.postDelayed({ setAsideForDisconnect = false }, 5000L)
        refreshBadge()
    }

    // ---- drawer ---------------------------------------------------------

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawerPanel.post {
            if (!drawerOpen) return@post
            drawerPanel.translationX = -drawerPanel.width.toFloat()
            drawerPanel.visibility = View.VISIBLE
            drawerPanel.animate().translationX(0f).setDuration(180L).start()
        }
        drawerScrim.alpha = 0f
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(180L).start()
        refreshDrawer()
        backCallback.isEnabled = true
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerPanel.animate().translationX(-drawerPanel.width.toFloat())
            .setDuration(150L)
            .withEndAction { if (!drawerOpen) drawerPanel.visibility = View.GONE }
            .start()
        drawerScrim.animate().alpha(0f).setDuration(150L)
            .withEndAction { if (!drawerOpen) drawerScrim.visibility = View.GONE }
            .start()
        backCallback.isEnabled = current != AppScreen.CARDIO
    }

    private fun refreshDrawer() {
        // Sober shell: the selected row is drawn with a lighter border and a
        // white label; the accent stays for the cardio measurement only.
        val selectedStroke = ContextCompat.getColor(this, R.color.text_primary)
        val base = ContextCompat.getColor(this, R.color.card)
        val stroke = ContextCompat.getColor(this, R.color.card_stroke)
        listOf(
            menuCardio to AppScreen.CARDIO,
            menuMusic to AppScreen.MUSIC,
            menuConfig to AppScreen.CONFIG
        ).forEach { (button, screen) ->
            val selected = screen == current
            if (button is MaterialButton) {
                button.strokeColor = android.content.res.ColorStateList.valueOf(
                    if (selected) selectedStroke else stroke
                )
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(base)
            }
            button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
        findViewById<TextView>(R.id.drawerVersion).text = "MemOnly ${versionName()}"
    }

    // ---- screens --------------------------------------------------------

    private fun currentOf(savedInstanceState: Bundle?): AppScreen =
        savedInstanceState?.getInt(KEY_SCREEN)?.let { AppScreen.fromId(it) } ?: AppScreen.last(this)

    private fun select(screen: AppScreen) {
        current = screen
        if (drawerOpen) closeDrawer()
        titleText.setText(screen.title)
        AppScreen.activate(this, screen)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentHost, screen.create(), screen.name)
            .commit()
        backCallback.isEnabled = drawerOpen || current != AppScreen.CARDIO
        refreshBadge()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCREEN, current.ordinal)
    }

    // ---- glasses badge --------------------------------------------------

    private val badgeTicker = object : Runnable {
        override fun run() {
            refreshBadge()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun refreshBadge() {
        val linked = GlassesHudClient.isLinked()
        badge.setText(
            if (linked) R.string.badge_glasses_on else R.string.badge_glasses_off
        )
        badge.setTextColor(
            ContextCompat.getColor(this, if (linked) R.color.ok else R.color.text_secondary)
        )
        if (drawerOpen) refreshDrawer()
    }

    // ---- launch link ----------------------------------------------------

    private fun maybeAutoConnect() {
        if (!ConfigFragment.autoConnectEnabled(this)) return
        if (!GlassesHudClient.hasConfirmed(this)) return
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) autoConnect() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun autoConnect() {
        if (!GlassesHudClient.savedAddress(this).isNullOrBlank()) {
            MusicMirrorService.connectOnly(this)
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
        }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    companion object {
        private const val KEY_SCREEN = "screen"
    }
}
