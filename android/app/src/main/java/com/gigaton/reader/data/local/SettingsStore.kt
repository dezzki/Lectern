package com.gigaton.reader.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Server connection settings (base URL + API token), stored in Keystore-backed
 * encrypted preferences. The token is never logged or shown in the UI.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val FILE_NAME = "gigaton_secure_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "api_token"
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty().trimEnd('/')
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }
}