package com.example.hello.shared

import kotlinx.browser.window

class BrowserConfigurationStore : ConfigurationStore {
    override val storageNotice = "Browser storage is readable by scripts on this site."

    private val storage = window.localStorage

    override fun load(): MinifluxConfiguration? {
        val serverUrl = storage.getItem(SERVER_URL_KEY) ?: return null
        val accessToken = storage.getItem(ACCESS_TOKEN_KEY) ?: return null
        return MinifluxConfiguration(serverUrl, accessToken)
    }

    override fun save(configuration: MinifluxConfiguration) {
        storage.setItem(SERVER_URL_KEY, configuration.serverUrl)
        storage.setItem(ACCESS_TOKEN_KEY, configuration.accessToken)
    }

    override fun clear() {
        storage.removeItem(SERVER_URL_KEY)
        storage.removeItem(ACCESS_TOKEN_KEY)
    }

    private companion object {
        const val SERVER_URL_KEY = "rss-reader.miniflux.server-url"
        const val ACCESS_TOKEN_KEY = "rss-reader.miniflux.access-token"
    }
}