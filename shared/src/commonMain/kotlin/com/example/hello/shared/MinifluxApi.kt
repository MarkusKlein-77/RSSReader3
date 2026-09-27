package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ArticleHeader(
    val id: Long,
    val title: String,
    val publishedAt: String,
)

class MinifluxApi {
    private val client = HttpClient()

    suspend fun validate(configuration: MinifluxConfiguration) {
        request(configuration, "v1/me")
    }

    suspend fun refreshArticles(configuration: MinifluxConfiguration): List<ArticleHeader> {
        request(configuration, "v1/refresh", post = true)
        delay(5_000)
        val response = request(
            configuration,
            "v1/entries?status=unread&order=published_at&direction=desc&limit=100",
        )
        val entries = Json.parseToJsonElement(response).jsonObject["entries"]?.jsonArray
            ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element.jsonObject
            val id = entry["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val title = entry["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ArticleHeader(
                id = id,
                title = title,
                publishedAt = entry["published_at"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.sortedByDescending(ArticleHeader::publishedAt)
    }

    fun close() {
        client.close()
    }

    private suspend fun request(
        configuration: MinifluxConfiguration,
        path: String,
        post: Boolean = false,
    ): String {
        val url = "${configuration.serverUrl.trimEnd('/')}/$path"
        val response = if (post) {
            client.post(url) { header("X-Auth-Token", configuration.accessToken) }
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
}

class MinifluxRequestException(message: String) : Exception(message)