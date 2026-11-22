package com.dynag.memoonly

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/**
 * Every app MemOnly hosts, plus the configuration that belongs to none of
 * them.
 *
 * MemOnly is meant to become the single entry point for the MemoMind glasses:
 * a new app only adds one entry here (label, colour, fragment factory) and one
 * row in the drawer, and the shell — drawer, title, glasses badge — picks it
 * up without any other change.
 */
enum class AppScreen(
    @StringRes val title: Int,
    @ColorRes val color: Int,
    val onGlasses: Boolean
) {
    CARDIO(R.string.app_cardio, R.color.cardio_color, onGlasses = true) {
        override fun create() = CardioFragment()
    },
    MUSIC(R.string.app_music, R.color.music_color, onGlasses = true) {
        override fun create() = MusicFragment()
    },
    CONFIG(R.string.app_config, R.color.config_color, onGlasses = false) {
        override fun create() = ConfigFragment()
    };

    abstract fun create(): androidx.fragment.app.Fragment

    /** The glasses screen this app owns, or -1 when it uses none. */
    val glassesScreen: Int
        get() = when (this) {
            CARDIO -> GmFrame.SCREEN_CARDIO
            MUSIC -> GmFrame.SCREEN_MUSIC
            CONFIG -> -1
        }

    companion object {
        const val PREF_LAST_SCREEN = "last_screen"

        fun fromId(value: Int): AppScreen = entries.firstOrNull { it.ordinal == value } ?: CARDIO

        /**
         * Make [target] the screen the glasses show. Shared by the shell (on a
         * drawer pick), the config tab (after a connect) and the services that
         * outlive the activity, so they all agree on one screen at a time.
         */
        fun activate(activity: Activity?, target: AppScreen) {
            (activity ?: return).getSharedPreferences(UiState.PREFS, Activity.MODE_PRIVATE)
                .edit().putInt(PREF_LAST_SCREEN, target.ordinal).apply()
            if (target.glassesScreen < 0) return
            UiState.setScreen(activity, target.glassesScreen)
            GlassesHudClient.selectScreen(target.glassesScreen)
            // A link that comes up just after the pick still needs the screen:
            // re-assert it once the plugin had time to announce itself.
            Handler(Looper.getMainLooper()).postDelayed({
                GlassesHudClient.selectScreen(target.glassesScreen)
            }, 1500L)
        }

        /** Screen to restore at launch: the one last shown. */
        fun last(activity: Activity): AppScreen =
            fromId(
                activity.getSharedPreferences(UiState.PREFS, Activity.MODE_PRIVATE)
                    .getInt(PREF_LAST_SCREEN, CARDIO.ordinal)
            )
    }
}
