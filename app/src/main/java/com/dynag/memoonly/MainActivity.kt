package com.dynag.memoonly

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * MemOnly — one app, two tabs.
 *
 * The workout tab mirrors heart rate and GPS metrics onto the glasses through
 * the cardio plugin, the music tab mirrors the phone's now-playing session
 * through the music plugin. Both tabs drive their own foreground service; the
 * activity only switches which tab is on screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tabs = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            show(CardioFragment(), R.id.tab_cardio)
        }
        tabs.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_cardio -> {
                    show(CardioFragment(), R.id.tab_cardio)
                    true
                }
                R.id.tab_music -> {
                    show(MusicFragment(), R.id.tab_music)
                    true
                }
                else -> false
            }
        }
    }

    private fun show(fragment: Fragment, tag: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentHost, fragment, tag.toString())
            .commit()
    }
}
