package com.dynag.memoonly

import android.content.Context

/**
 * Small preferences store shared by the activity, the fragments and the
 * services. The glasses link is owned by whichever service connected first, so
 * the music mirror has to know if the user is currently on the music tab to
 * turn a track frame into a screen switch.
 */
object UiState {
    const val PREFS = "memo_ui"
    const val PREF_LOG = "glasses_log"
    const val PREF_SCREEN = "screen"

    fun log(context: Context, line: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val previous = prefs.getString(PREF_LOG, "").orEmpty()
        val next = (previous + "$stamp $line\n").takeLast(MAX_LOG_CHARS)
        prefs.edit().putString(PREF_LOG, next).apply()
    }

    fun clearLog(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_LOG, "").apply()
    }

    fun logText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_LOG, "").orEmpty()

    fun setScreen(context: Context, screen: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREF_SCREEN, screen).apply()
    }

    fun screen(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(PREF_SCREEN, GmFrame.SCREEN_CARDIO)

    private const val MAX_LOG_CHARS = 16000
}
