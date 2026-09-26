package com.example.hello.shared

data class MinifluxConfiguration(
    val serverUrl: String,
    val accessToken: String,
)

interface ConfigurationStore {
    val storageNotice: String?

    fun load(): MinifluxConfiguration?
    fun save(configuration: MinifluxConfiguration)
    fun clear()
}

fun normalizeServerUrl(value: String): String? {
    val url = value.trim().trimEnd('/')
    val schemeLength = when {
        url.startsWith("https://", ignoreCase = true) -> "https://".length
        url.startsWith("http://", ignoreCase = true) -> "http://".length
        else -> return null
    }
    val authority = url.substring(schemeLength).substringBefore('/')
    if (authority.isBlank() || authority.any(Char::isWhitespace) || '@' in authority) {
        return null
    }
    return url
}