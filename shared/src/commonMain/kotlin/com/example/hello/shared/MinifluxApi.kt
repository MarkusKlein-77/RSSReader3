package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

private const val HOSTNAME_CAUSE_CHAIN_LIMIT = 5

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
        val payload = """{"entry_ids":[${articleIds.joinToString(separator = ",")}],"status":"read"}"""
        requestWithBody(configuration, "v1/entries", payload)
    }

    suspend fun refreshFeeds(configuration: MinifluxConfiguration) {
        request(configuration, "v1/feeds/refresh", put = true)
    }

    suspend fun hasFeedsDueForRefresh(
        configuration: MinifluxConfiguration,
        now: Instant = Clock.System.now(),
    ): Boolean {
        val response = request(configuration, "v1/feeds")
        val feeds = Json.parseToJsonElement(response).jsonArray
        return feeds.any { element ->
            val feed = element as? JsonObject ?: return@any false
            val disabled = (feed["disabled"] as? JsonPrimitive)?.contentOrNull == "true"
            if (disabled) return@any false

            val nextCheckAt = (feed["next_check_at"] as? JsonPrimitive)
                ?.contentOrNull
                ?.let(::parseInstantOrNull)
            if (nextCheckAt != null) {
                nextCheckAt < now
            } else {
                val checkedAt = (feed["checked_at"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.let(::parseInstantOrNull)
                checkedAt == null || checkedAt <= now - 10.minutes
            }
        }
    }

    suspend fun loadArticles(
        configuration: MinifluxConfiguration,
        includeRead: Boolean,
    ): List<ArticleHeader> {
        val statusFilter = if (includeRead) "" else "status=unread&"
        val articles = mutableListOf<ArticleHeader>()
        val seenIds = mutableSetOf<Long>()
        var offset = 0

        while (true) {
            val response = request(
                configuration,
                "v1/entries?${statusFilter}order=published_at&direction=desc&limit=$ARTICLE_PAGE_SIZE&offset=$offset",
            )
            val root = Json.parseToJsonElement(response).jsonObject
            val entries = root["entries"] as? JsonArray
                ?: throw MinifluxRequestException("Miniflux returned an invalid article list.")
            val total = root["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val previousCount = articles.size

            entries.forEach { element ->
                val entry = element.jsonObject
                val id = entry["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                val title = entry["title"]?.jsonPrimitive?.content ?: return@forEach
                val feed = entry["feed"] as? JsonObject
                val siteUrl = feed?.get("site_url")?.jsonPrimitive?.contentOrNull
                    ?: feed?.get("feed_url")?.jsonPrimitive?.contentOrNull
                val content = entry["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (seenIds.add(id)) {
                    articles += ArticleHeader(
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

            if (entries.isEmpty() || articles.size == previousCount) break
            offset += entries.size
            if ((total != null && offset >= total) ||
                (total == null && entries.size < ARTICLE_PAGE_SIZE)
            ) {
                break
            }
        }

        return articles
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
        const val ARTICLE_PAGE_SIZE = 100
        const val HOSTNAME_RETRY_DELAY_MILLIS = 1_000L
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
        return retryHostnameResolution {
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
            response.bodyAsText()
        }
    }

    private suspend fun requestWithBody(
        configuration: MinifluxConfiguration,
        path: String,
        payload: String,
    ): String {
        val url = "${configuration.serverUrl.trimEnd('/')}/$path"
        return retryHostnameResolution {
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
            response.bodyAsText()
        }
    }

    private suspend fun <T> retryHostnameResolution(operation: suspend () -> T): T {
        var retried = false
        while (true) {
            try {
                return operation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (retried || !isHostnameResolutionFailure(exception)) throw exception
                retried = true
                delay(HOSTNAME_RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun parseInstantOrNull(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: IllegalArgumentException) {
        null
    }

}

class MinifluxRequestException(message: String) : Exception(message)

private fun isHostnameResolutionFailure(exception: Throwable): Boolean {
    var current: Throwable? = exception
    repeat(HOSTNAME_CAUSE_CHAIN_LIMIT) {
        val message = current?.message.orEmpty()
        if (
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("No address associated with hostname", ignoreCase = true) ||
            message.contains("UnknownHostException", ignoreCase = true)
        ) {
            return true
        }
        current = current?.cause
    }
    return false
}

fun formatMinifluxError(exception: Exception, fallback: String): String {
    return if (isHostnameResolutionFailure(exception)) {
        "Could not resolve the Miniflux server hostname. Check the server URL and your DNS or network connection, then retry."
    } else {
        exception.message.orEmpty().ifBlank { fallback }
    }
}