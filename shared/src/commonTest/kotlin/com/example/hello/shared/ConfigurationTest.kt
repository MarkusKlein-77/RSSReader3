package com.example.hello.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigurationTest {
    @Test
    fun normalizesServerUrl() {
        assertEquals("https://miniflux.example.com", normalizeServerUrl("  https://miniflux.example.com///  "))
        assertEquals("http://localhost:8080/miniflux", normalizeServerUrl("http://localhost:8080/miniflux/"))
    }

    @Test
    fun rejectsInvalidServerUrl() {
        assertNull(normalizeServerUrl("miniflux.example.com"))
        assertNull(normalizeServerUrl("https:///missing-host"))
        assertNull(normalizeServerUrl("https://user@miniflux.example.com"))
    }
}