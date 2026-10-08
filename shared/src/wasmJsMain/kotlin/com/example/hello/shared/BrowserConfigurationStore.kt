package com.example.hello.shared

import kotlinx.browser.window

class BrowserConfigurationStore : ConfigurationStore {
    override val storageNotice =
        "Credentials are saved in persistent cookies for this site and are readable by scripts running on it."

    private val storage = window.localStorage

    override fun load(): MinifluxConfiguration? {
        val serverUrl = readCookie(SERVER_URL_KEY)
        val accessToken = readCookie(ACCESS_TOKEN_KEY)
        if (serverUrl != null && accessToken != null) {
            return MinifluxConfiguration(serverUrl, accessToken)
        }

        val legacyServerUrl = storage.getItem(LEGACY_SERVER_URL_KEY) ?: return null
        val legacyAccessToken = storage.getItem(LEGACY_ACCESS_TOKEN_KEY) ?: return null
        val configuration = MinifluxConfiguration(legacyServerUrl, legacyAccessToken)
        save(configuration)
        storage.removeItem(LEGACY_SERVER_URL_KEY)
        storage.removeItem(LEGACY_ACCESS_TOKEN_KEY)
        return configuration
    }

    override fun save(configuration: MinifluxConfiguration) {
        writeCookie(SERVER_URL_KEY, configuration.serverUrl)
        writeCookie(ACCESS_TOKEN_KEY, configuration.accessToken)
    }

    override fun clear() {
        expireCookie(SERVER_URL_KEY)
        expireCookie(ACCESS_TOKEN_KEY)
        storage.removeItem(LEGACY_SERVER_URL_KEY)
        storage.removeItem(LEGACY_ACCESS_TOKEN_KEY)
        check(readCookie(SERVER_URL_KEY) == null && readCookie(ACCESS_TOKEN_KEY) == null) {
            "The saved browser cookies could not be cleared."
        }
    }

    private fun readCookie(name: String): String? {
        val cookie = window.document.cookie
            .split(';')
            .firstOrNull { it.trimStart().startsWith("$name=") }
            ?: return null
        return decodeCookieValue(cookie.trimStart().substringAfter('='))
    }

    private fun writeCookie(name: String, value: String) {
        window.document.cookie = "$name=${encodeCookieValue(value)}; Max-Age=$COOKIE_MAX_AGE_SECONDS; Path=/; SameSite=Lax$secureAttribute"
        check(readCookie(name) == value) {
            "The browser did not save the configuration cookie. Check the browser's cookie settings."
        }
    }

    private fun expireCookie(name: String) {
        window.document.cookie = "$name=; Max-Age=0; Path=/; SameSite=Lax$secureAttribute"
    }

    private val secureAttribute: String
        get() = if (window.location.protocol == "https:") "; Secure" else ""

    private fun encodeCookieValue(value: String): String = buildString {
        val hexDigits = "0123456789ABCDEF"
        for (byte in value.encodeToByteArray()) {
            val unsignedByte = byte.toInt() and 0xFF
            append('%')
            append(hexDigits[unsignedByte shr 4])
            append(hexDigits[unsignedByte and 0x0F])
        }
    }

    private fun decodeCookieValue(value: String): String? {
        if (value.length % 3 != 0) return null
        val bytes = ByteArray(value.length / 3)
        for (index in bytes.indices) {
            val offset = index * 3
            if (value[offset] != '%') return null
            val byte = value.substring(offset + 1, offset + 3).toIntOrNull(16) ?: return null
            bytes[index] = byte.toByte()
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private companion object {
        const val SERVER_URL_KEY = "rss-reader.miniflux.server-url"
        const val ACCESS_TOKEN_KEY = "rss-reader.miniflux.access-token"
        const val LEGACY_SERVER_URL_KEY = SERVER_URL_KEY
        const val LEGACY_ACCESS_TOKEN_KEY = ACCESS_TOKEN_KEY
        const val COOKIE_MAX_AGE_SECONDS = 31_536_000
    }
}