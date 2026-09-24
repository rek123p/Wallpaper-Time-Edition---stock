package com.rek123p.walltimeedition

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var languageGroup: RadioGroup
    private lateinit var timeFormatGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        languageGroup = findViewById(R.id.languageGroup)
        timeFormatGroup = findViewById(R.id.timeFormatGroup)

        // Reflect the currently saved settings BEFORE attaching the
        // listeners below, so restoring the saved state doesn't immediately
        // re-trigger a (redundant, harmless, but pointless) save.
        loadCurrentSettings()

        languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val language = when (checkedId) {
                R.id.languageEn -> WallpaperPrefs.LANGUAGE_EN
                R.id.languagePl -> WallpaperPrefs.LANGUAGE_PL
                else -> WallpaperPrefs.LANGUAGE_SYSTEM
            }
            WallpaperPrefs.prefs(this).edit {
                putString(WallpaperPrefs.KEY_LANGUAGE, language)
            }
        }

        timeFormatGroup.setOnCheckedChangeListener { _, checkedId ->
            val format = when (checkedId) {
                R.id.format12h -> WallpaperPrefs.TIME_FORMAT_12H
                else -> WallpaperPrefs.TIME_FORMAT_24H
            }
            WallpaperPrefs.prefs(this).edit {
                putString(WallpaperPrefs.KEY_TIME_FORMAT, format)
            }
        }

        val setWallpaperButton = findViewById<Button>(R.id.setWallpaperButton)
        setWallpaperButton.setOnClickListener {
            setLiveWallpaper()
        }
    }

    private fun loadCurrentSettings() {
        when (WallpaperPrefs.getLanguage(this)) {
            WallpaperPrefs.LANGUAGE_EN -> languageGroup.check(R.id.languageEn)
            WallpaperPrefs.LANGUAGE_PL -> languageGroup.check(R.id.languagePl)
            else -> languageGroup.check(R.id.languageSystem)
        }

        when (WallpaperPrefs.getTimeFormat(this)) {
            WallpaperPrefs.TIME_FORMAT_12H -> timeFormatGroup.check(R.id.format12h)
            else -> timeFormatGroup.check(R.id.format24h)
        }
    }

    private fun setLiveWallpaper() {
        val component = ComponentName(this, WallTimeWallpaperService::class.java)

        try {
            // Opens the system's live wallpaper preview/settings screen directly for this wallpaper
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                // Fallback - the system's general live wallpaper picker
                val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                startActivity(fallbackIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    getString(R.string.wallpaper_chooser_not_found),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}