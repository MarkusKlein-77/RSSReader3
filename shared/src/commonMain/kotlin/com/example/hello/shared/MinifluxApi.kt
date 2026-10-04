package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ArticleHeader(
    val id: Long,
    val title: String,
    val publishedAt: String,
    val url: String,
    val imageUrl: String?,
    val sourceTitle: String,
    val sourceIconUrl: String?,
)

class MinifluxApi(
    private val client: HttpClient = HttpClient(),
) {
    suspend fun validate(configuration: MinifluxConfiguration) {
        request(configuration, "v1/me")
    }

    suspend fun markArticleRead(configuration: MinifluxConfiguration, articleId: Long) {
        val payload = """{"entry_ids":[$articleId],"status":"read"}"""
        requestWithBody(configuration, "v1/entries", payload)
    }

    suspend fun markAllArticlesRead(configuration: MinifluxConfiguration, articleIds: List<Long>) {
        if (articleIds.isEmpty()) return
        val payload = """{"entry_ids":[${articleIds.joinToString()}],"status":"read"}"""
        requestWithBody(configuration, "v1/entries", payload)
    }

    suspend fun refreshFeeds(configuration: MinifluxConfiguration) {
        request(configuration, "v1/feeds/refresh", put = true)
    }

    suspend fun loadArticles(
        configuration: MinifluxConfiguration,
        includeRead: Boolean,
    ): List<ArticleHeader> {
        val statusFilter = if (includeRead) "" else "status=unread&"
        val response = request(
            configuration,
            "v1/entries?${statusFilter}order=published_at&direction=desc&limit=100",
        )
        val entries = Json.parseToJsonElement(response).jsonObject["entries"]?.jsonArray
            ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element.jsonObject
            val id = entry["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val title = entry["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val feed = entry["feed"] as? JsonObject
            val siteUrl = feed?.get("site_url")?.jsonPrimitive?.contentOrNull
                ?: feed?.get("feed_url")?.jsonPrimitive?.contentOrNull
            val content = entry["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            ArticleHeader(
                id = id,
                title = title,
                publishedAt = entry["published_at"]?.jsonPrimitive?.content.orEmpty(),
                url = entry["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                imageUrl = findImageUrl(entry, content),
                sourceTitle = feed?.get("title")?.jsonPrimitive?.contentOrNull ?: "RSS",
                sourceIconUrl = faviconUrl(siteUrl),
            )
        }
    }

    private fun findImageUrl(entry: JsonObject, content: String): String? {
        val enclosures = entry["enclosures"] as? JsonArray
        val enclosureImage = enclosures?.firstNotNullOfOrNull { element ->
            val enclosure = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val mimeType = enclosure["mime_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (mimeType.startsWith("image/", ignoreCase = true)) {
                enclosure["url"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }
        if (!enclosureImage.isNullOrBlank()) return enclosureImage
        return imageSourcePattern.find(content)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.takeIf(String::isNotBlank)
    }

    private fun faviconUrl(siteUrl: String?): String? {
        val url = siteUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, authorityEnd)
        if (authority.isBlank()) return null
        return "${url.substring(0, schemeEnd)}://$authority/favicon.ico"
    }

    private companion object {
        val imageSourcePattern = Regex(
            """<img\b[^>]*\bsrc\s*=\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE,
        )
    }

    fun close() {
        client.close()
    }

    private suspend fun request(
        configuration: MinifluxConfiguration,
        path: String,
        put: Boolean = false,
    ): String {
        val url = "${configuration.serverUrl.trimEnd('/')}/$path"
        val response = if (put) {
            client.put(url) { header("X-Auth-Token", configuration.accessToken) }
        } else {
            client.get(url) { header("X-Auth-Token", configuration.accessToken) }
        }
        if (response.status.value !in 200..299) {
            throw MinifluxRequestException(
                when (response.status.value) {
                    401, 403 -> "Miniflux rejected the access token. Check your configuration."
                    else -> "Miniflux returned HTTP ${response.status.value}. Try again later."
                },
            )
        }
        return response.bodyAsText()
    }

    private suspend fun requestWithBody(
        configuration: MinifluxConfiguration,
        path: String,
        payload: String,
    ): String {
        val url = "${configuration.serverUrl.trimEnd('/')}/$path"
        val response = client.put(url) {
            header("X-Auth-Token", configuration.accessToken)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (response.status.value !in 200..299) {
            throw MinifluxRequestException(
                when (response.status.value) {
                    401, 403 -> "Miniflux rejected the access token. Check your configuration."
                    else -> "Miniflux returned HTTP ${response.status.value}. Try again later."
                },
            )
        }
        return response.bodyAsText()
    }
}

class MinifluxRequestException(message: String) : Exception(message)