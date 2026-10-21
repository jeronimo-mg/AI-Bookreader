package com.example.epubstoryteller.data

import android.content.Context
import android.content.SharedPreferences

class ReadingProgressStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("epub_storyteller_prefs", Context.MODE_PRIVATE)

    fun saveBookPosition(bookId: String, paragraphIndex: Int) {
        prefs.edit()
            .putInt("pos_$bookId", paragraphIndex)
            .putLong("updated_$bookId", System.currentTimeMillis())
            .putString("last_opened_book_id", bookId)
            .apply()
    }

    fun getSavedBookPosition(bookId: String): Int {
        return prefs.getInt("pos_$bookId", 0).coerceAtLeast(0)
    }

    fun hasSavedPosition(bookId: String): Boolean {
        return prefs.contains("pos_$bookId")
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString("gemini_api_key", apiKey.trim()).apply()
    }

    fun getApiKey(): String {
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    fun saveVoiceName(voiceName: String) {
        prefs.edit().putString("storyteller_voice_name", voiceName.trim()).apply()
    }

    fun getVoiceName(): String {
        return prefs.getString("storyteller_voice_name", "Sulafat") ?: "Sulafat"
    }
}
