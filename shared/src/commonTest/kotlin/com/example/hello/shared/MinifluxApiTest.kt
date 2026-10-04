package com.example.hello.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinifluxApiTest {
    @Test
    fun refreshesConfiguredFeedsOnServer() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://miniflux.example.com/v1/feeds/refresh", request.url.toString())
            assertEquals("PUT", request.method.value)
            assertEquals("token-123", request.headers["X-Auth-Token"])
            respond("", status = HttpStatusCode.NoContent)
        }

        val api = MinifluxApi(client = HttpClient(engine))
        api.refreshFeeds(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
        )
    }

    @Test
    fun marksSingleEntryAsRead() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://miniflux.example.com/v1/entries/42", request.url.toString())
            assertEquals("token-123", request.headers["X-Auth-Token"])
            val body = request.body as? TextContent
            assertTrue(body != null)
            assertEquals("{\"entry_ids\":[42],\"status\":\"read\"}", body?.text)
            respond("", status = HttpStatusCode.NoContent, headers = headersOf("Content-Type", ContentType.Application.Json.toString()))
        }

        val api = MinifluxApi(client = HttpClient(engine))
        api.markArticleRead(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            articleId = 42,
        )
    }

    @Test
    fun marksMultipleEntriesAsRead() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://miniflux.example.com/v1/entries", request.url.toString())
            assertEquals("token-123", request.headers["X-Auth-Token"])
            val body = request.body as? TextContent
            assertTrue(body != null)
            assertTrue((body?.text ?: "").contains("\"entry_ids\":[11,22,33]"))
            assertTrue((body?.text ?: "").contains("\"status\":\"read\""))
            respond("", status = HttpStatusCode.NoContent, headers = headersOf("Content-Type", ContentType.Application.Json.toString()))
        }

        val api = MinifluxApi(client = HttpClient(engine))
        api.markAllArticlesRead(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            articleIds = listOf(11L, 22L, 33L),
        )
    }
}
