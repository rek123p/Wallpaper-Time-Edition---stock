package com.rek123p.walltimeedition

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Central place for the wallpaper's settings (language, time format), shared
 * between MainActivity (which writes them) and WallTimeWallpaperService
 * (which reads them) - keeps both sides using the exact same keys/values.
 */
object WallpaperPrefs {

    private const val PREFS_NAME = "wallpaper_settings"

    const val KEY_LANGUAGE = "language"
    const val KEY_TIME_FORMAT = "time_format"

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_EN = "en"
    const val LANGUAGE_PL = "pl"

    const val TIME_FORMAT_24H = "24"
    const val TIME_FORMAT_12H = "12"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM

    fun getTimeFormat(context: Context): String =
        prefs(context).getString(KEY_TIME_FORMAT, TIME_FORMAT_24H) ?: TIME_FORMAT_24H

    /** Resolves a stored language code to an explicit Locale for date/time formatting. */
    fun resolveLocale(languageCode: String): Locale = when (languageCode) {
        LANGUAGE_EN -> Locale.ENGLISH
        LANGUAGE_PL -> Locale.Builder().setLanguage("pl").setRegion("PL").build()
        else -> Locale.getDefault()
    }
}