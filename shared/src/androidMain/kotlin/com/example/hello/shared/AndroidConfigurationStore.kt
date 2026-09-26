package com.example.hello.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AndroidConfigurationStore(context: Context) : ConfigurationStore {
    override val storageNotice: String? = null

    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "miniflux_configuration",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): MinifluxConfiguration? {
        val serverUrl = preferences.getString(SERVER_URL_KEY, null) ?: return null
        val accessToken = preferences.getString(ACCESS_TOKEN_KEY, null) ?: return null
        return MinifluxConfiguration(serverUrl, accessToken)
    }

    override fun save(configuration: MinifluxConfiguration) {
        preferences.edit()
            .putString(SERVER_URL_KEY, configuration.serverUrl)
            .putString(ACCESS_TOKEN_KEY, configuration.accessToken)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(SERVER_URL_KEY)
            .remove(ACCESS_TOKEN_KEY)
            .apply()
    }

    private companion object {
        const val SERVER_URL_KEY = "server_url"
        const val ACCESS_TOKEN_KEY = "access_token"
    }
}