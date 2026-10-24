package com.pixivdownloader.app

import android.content.Context

/** Stores the Pixiv PHPSESSID in app-private storage (replaces config_token.json). */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("pixiv", Context.MODE_PRIVATE)

    fun get(): String = prefs.getString(KEY, "").orEmpty()

    /** Accepts a bare value or a pasted "PHPSESSID=...;" cookie string. Returns what was stored. */
    fun save(raw: String): String {
        val clean = raw.trim().removePrefix("PHPSESSID=").substringBefore(';').trim()
        prefs.edit().putString(KEY, clean).apply()
        return clean
    }

    companion object {
        private const val KEY = "PHPSESSID"
    }
}
