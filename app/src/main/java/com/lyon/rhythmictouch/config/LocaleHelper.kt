package com.lyon.rhythmictouch.config

import android.content.Context
import android.os.Build
import java.util.Locale

object LocaleHelper {

    private const val PREF_NAME = "locale_pref"
    private const val KEY_LANGUAGE = "language"

    const val FOLLOW_SYSTEM = "system"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    fun getSavedLanguage(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, FOLLOW_SYSTEM) ?: FOLLOW_SYSTEM
    }

    fun saveLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun applyLocale(context: Context): Context {
        val lang = getSavedLanguage(context)
        if (lang == FOLLOW_SYSTEM) return context

        val locale = when (lang) {
            CHINESE -> Locale.CHINESE
            ENGLISH -> Locale.ENGLISH
            else -> return context
        }

        val config = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }

    fun getLanguageDisplayName(language: String): String = when (language) {
        CHINESE -> "中文"
        ENGLISH -> "English"
        else -> "Follow System"
    }
}
