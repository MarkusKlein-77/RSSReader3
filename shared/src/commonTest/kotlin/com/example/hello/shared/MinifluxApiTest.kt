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
    fun translatesDnsResolutionErrorsIntoActionableMessage() {
        val message = formatMinifluxError(
            Exception("""Unable to resolve host "miniflux.example.com": No address associated with hostname"""),
            "Fallback message",
        )

        assertEquals(
            "Could not resolve the Miniflux server hostname. Check the server URL and your DNS or network connection, then retry.",
            message,
        )
    }

    @Test
    fun preservesOtherErrorsAndUsesFallbackWhenMessageIsEmpty() {
        assertEquals("Unauthorized", formatMinifluxError(Exception("Unauthorized"), "Fallback message"))
        assertEquals("Fallback message", formatMinifluxError(Exception(""), "Fallback message"))
    }

    @Test
    fun retriesTransientHostnameResolutionFailureOnce() = runBlocking {
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            if (attempts == 1) {
                throw Exception("""Unable to resolve host "miniflux.example.com": No address associated with hostname""")
            }
            respond("{}", status = HttpStatusCode.OK)
        }

        val api = MinifluxApi(client = HttpClient(engine))
        api.validate(
            MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
        )

        assertEquals(2, attempts)
        api.close()
    }

    @Test
    fun surfacesPersistentHostnameResolutionFailureAfterOneRetry() = runBlocking {
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            throw Exception("Connection failed", Exception("UnknownHostException: miniflux.example.com"))
        }

        val api = MinifluxApi(client = HttpClient(engine))
        var error: Exception? = null
        try {
            api.validate(
                MinifluxConfiguration(
                    serverUrl = "https://miniflux.example.com",
                    accessToken = "token-123",
                ),
            )
        } catch (exception: Exception) {
            error = exception
        }

        assertEquals(2, attempts)
        assertEquals(
            "Could not resolve the Miniflux server hostname. Check the server URL and your DNS or network connection, then retry.",
            error?.let { formatMinifluxError(it, "Fallback message") },
        )
        api.close()
    }

    @Test
    fun detectsDueFeedsFromNextCheckTime() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://miniflux.example.com/v1/feeds", request.url.toString())
            assertEquals("token-123", request.headers["X-Auth-Token"])
            respond(
                """[
                    {"checked_at":"2026-10-12T09:59:00Z","next_check_at":"2026-10-12T10:01:00Z"},
                    {"checked_at":"2026-10-12T09:59:00Z","next_check_at":"2026-10-12T09:59:59Z"}
                ]""",
                status = HttpStatusCode.OK,
            )
        }

        val api = MinifluxApi(client = HttpClient(engine))
        val hasFeedsDue = api.hasFeedsDueForRefresh(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            now = Instant.parse("2026-10-12T10:00:00Z"),
        )

        assertTrue(hasFeedsDue)
        api.close()
    }

    @Test
    fun doesNotRefreshFeedsWhoseNextCheckIsInTheFuture() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("https://miniflux.example.com/v1/feeds", request.url.toString())
            respond(
                """[
                    {"checked_at":"2026-10-12T09:00:00Z","next_check_at":"2026-10-12T10:01:00Z"},
                    {"checked_at":"2026-10-12T09:59:00Z","next_check_at":"2026-10-12T10:05:00Z","disabled":true}
                ]""",
                status = HttpStatusCode.OK,
            )
        }

        val api = MinifluxApi(client = HttpClient(engine))
        val hasFeedsDue = api.hasFeedsDueForRefresh(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            now = Instant.parse("2026-10-12T10:00:00Z"),
        )

        assertFalse(hasFeedsDue)
        api.close()
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
    fun loadsArticlesAcrossAllApiPages() = runBlocking {
        val requestedOffsets = mutableListOf<String?>()
        val engine = MockEngine { request ->
            assertEquals("100", request.url.parameters["limit"])
            assertEquals("unread", request.url.parameters["status"])
            val offset = request.url.parameters["offset"]
            requestedOffsets += offset
            when (offset) {
                "0" -> respond(
                    """{"total":3,"entries":[{"id":11,"title":"First"},{"id":22,"title":"Second"}]}""",
                    status = HttpStatusCode.OK,
                )
                "2" -> respond(
                    """{"total":3,"entries":[{"id":33,"title":"Third"}]}""",
                    status = HttpStatusCode.OK,
                )
                else -> error("Unexpected article offset: $offset")
            }
        }

        val api = MinifluxApi(client = HttpClient(engine))
        val articles = api.loadArticles(
            configuration = MinifluxConfiguration(
                serverUrl = "https://miniflux.example.com",
                accessToken = "token-123",
            ),
            includeRead = false,
        )

        assertEquals(listOf(11L, 22L, 33L), articles.map { it.id })
        assertEquals(listOf<String?>("0", "2"), requestedOffsets)
        api.close()
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
