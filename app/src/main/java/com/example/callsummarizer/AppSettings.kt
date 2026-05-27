package com.example.callsummarizer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User-configurable settings. API keys live in EncryptedSharedPreferences (AES-256 GCM, keyed by
 * AndroidKeyStore); non-secret config (model names, language code, reply script) lives in plain prefs.
 */
class AppSettings(
    context: Context,
) {
    private val secure: SharedPreferences =
        run {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    private val plain: SharedPreferences =
        context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    var deepgramKey: String
        get() = secure.getString(KEY_DEEPGRAM, "").orEmpty()
        set(value) = secure.edit { putString(KEY_DEEPGRAM, value.trim()) }

    var openrouterKey: String
        get() = secure.getString(KEY_OPENROUTER, "").orEmpty()
        set(value) = secure.edit { putString(KEY_OPENROUTER, value.trim()) }

    var deepgramModel: String
        get() =
            plain
                .getString(KEY_DG_MODEL, DEFAULT_DEEPGRAM_MODEL)
                .orEmpty()
                .ifBlank { DEFAULT_DEEPGRAM_MODEL }
        set(value) = plain.edit { putString(KEY_DG_MODEL, value.trim().ifBlank { DEFAULT_DEEPGRAM_MODEL }) }

    var openrouterModel: String
        get() =
            plain
                .getString(KEY_OR_MODEL, DEFAULT_OPENROUTER_MODEL)
                .orEmpty()
                .ifBlank { DEFAULT_OPENROUTER_MODEL }
        set(value) = plain.edit { putString(KEY_OR_MODEL, value.trim().ifBlank { DEFAULT_OPENROUTER_MODEL }) }

    var sourceLanguage: String
        get() =
            plain
                .getString(KEY_LANG, DEFAULT_LANGUAGE)
                .orEmpty()
                .ifBlank { DEFAULT_LANGUAGE }
        set(value) = plain.edit { putString(KEY_LANG, value.trim().lowercase().ifBlank { DEFAULT_LANGUAGE }) }

    var replyScript: ReplyScript
        get() = ReplyScript.fromId(plain.getString(KEY_SCRIPT, ReplyScript.LATIN.id))
        set(value) = plain.edit { putString(KEY_SCRIPT, value.id) }

    fun hasKeys(): Boolean = deepgramKey.isNotBlank() && openrouterKey.isNotBlank()

    companion object {
        private const val SECURE_FILE = "secure_prefs"
        private const val PLAIN_FILE = "config_prefs"
        private const val KEY_DEEPGRAM = "deepgram_key"
        private const val KEY_OPENROUTER = "openrouter_key"
        private const val KEY_DG_MODEL = "deepgram_model"
        private const val KEY_OR_MODEL = "openrouter_model"
        private const val KEY_LANG = "source_language"
        private const val KEY_SCRIPT = "reply_script"

        const val DEFAULT_DEEPGRAM_MODEL = "nova-3"
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"
        const val DEFAULT_LANGUAGE = "bg"
    }
}

enum class ReplyScript(
    val id: String,
    val displayName: String,
) {
    LATIN("latin", "Latin (transliterated)"),
    NATIVE("native", "Native (same script as source language)"),
    CYRILLIC("cyrillic", "Cyrillic"),
    PERSIAN("persian", "Persian / Arabic script"),
    ;

    companion object {
        fun fromId(id: String?): ReplyScript = values().firstOrNull { it.id == id } ?: LATIN
    }
}
