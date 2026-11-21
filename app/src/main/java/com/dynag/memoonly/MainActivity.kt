package com.dynag.memoonly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * MemOnly — one app, three tabs.
 *
 * The workout tab mirrors heart rate and GPS metrics onto the glasses through
 * the cardio plugin, the music tab mirrors the phone's now-playing session
 * through the music plugin, and the config tab owns the single glasses
 * connection every tab shares. All of them log into one journal shown on the
 * config tab, whichever service produced the line.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: BottomNavigationView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) autoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // One journal for both glasses clients: a cardio line and a music line
        // must be readable side by side on the config tab.
        GlassesHudClient.setJournalSink { line ->
            UiState.log(applicationContext, line)
        }
        tabs = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            show(CardioFragment(), R.id.tab_cardio)
            UiState.setScreen(this, GmFrame.SCREEN_CARDIO)
            GlassesHudClient.selectScreen(GmFrame.SCREEN_CARDIO)
            maybeAutoConnect()
        }
        tabs.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_cardio -> {
                    show(CardioFragment(), R.id.tab_cardio)
                    UiState.setScreen(this, GmFrame.SCREEN_CARDIO)
                    GlassesHudClient.selectScreen(GmFrame.SCREEN_CARDIO)
                    true
                }
                R.id.tab_config -> {
                    show(ConfigFragment(), R.id.tab_config)
                    true
                }
                R.id.tab_music -> {
                    show(MusicFragment(), R.id.tab_music)
                    UiState.setScreen(this, GmFrame.SCREEN_MUSIC)
                    GlassesHudClient.selectScreen(GmFrame.SCREEN_MUSIC)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Re-open the remembered glasses once on launch, so the user connects them
     * a single time. The config tab is where the first pick happens.
     */
    /** Open the config tab from another tab's "connect glasses" button. */
    fun openConfigTab() {
        tabs.selectedItemId = R.id.tab_config
    }

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

    private fun show(fragment: Fragment, tag: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentHost, fragment, tag.toString())
            .commit()
    }
}
