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
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinifluxApiTest {
    @Test
    fun detectsRecentFeedCheckFromLatestFeedTimestamp() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://miniflux.example.com/v1/feeds", request.url.toString())
            assertEquals("token-123", request.headers["X-Auth-Token"])
            respond(
                """[{"checked_at":"2026-10-12T09:00:00Z"},{"checked_at":"2026-10-12T09:55:00Z"}]""",
                status = HttpStatusCode.OK,
            )
        }

        val api = MinifluxApi(client = HttpClient(engine))
        val hasRecentCheck = api.hasRecentFeedCheck(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            now = Instant.parse("2026-10-12T10:00:00Z"),
        )

        assertTrue(hasRecentCheck)
    }

    @Test
    fun reportsNoRecentFeedCheckWhenLatestTimestampIsAtLeastTenMinutesOld() = runBlocking {
        val engine = MockEngine {
            respond(
                """[{"checked_at":"2026-10-12T09:49:59Z"},{"checked_at":"2026-10-12T09:50:00Z"}]""",
                status = HttpStatusCode.OK,
            )
        }

        val api = MinifluxApi(client = HttpClient(engine))
        val hasRecentCheck = api.hasRecentFeedCheck(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            now = Instant.parse("2026-10-12T10:00:00Z"),
        )

        assertFalse(hasRecentCheck)
    }

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
            assertEquals("https://miniflux.example.com/v1/entries", request.url.toString())
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
            assertEquals("{\"entry_ids\":[11,22,33],\"status\":\"read\"}", body?.text)
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
