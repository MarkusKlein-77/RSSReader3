package com.example.hello.shared

import java.util.prefs.Preferences

class DesktopConfigurationStore : ConfigurationStore {
    override val storageNotice = "The access token is stored in user preferences and is not encrypted by this app."

    private val preferences = Preferences.userNodeForPackage(DesktopConfigurationStore::class.java)

    override fun load(): MinifluxConfiguration? {
        val serverUrl = preferences.get(SERVER_URL_KEY, null) ?: return null
        val accessToken = preferences.get(ACCESS_TOKEN_KEY, null) ?: return null
        return MinifluxConfiguration(serverUrl, accessToken)
    }

    override fun save(configuration: MinifluxConfiguration) {
        preferences.put(SERVER_URL_KEY, configuration.serverUrl)
        preferences.put(ACCESS_TOKEN_KEY, configuration.accessToken)
    }

    override fun clear() {
        preferences.remove(SERVER_URL_KEY)
        preferences.remove(ACCESS_TOKEN_KEY)
    }

    private companion object {
        const val SERVER_URL_KEY = "server_url"
        const val ACCESS_TOKEN_KEY = "access_token"
    }
}