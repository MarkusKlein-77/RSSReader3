package com.example.hello.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class HelloStateTest {
    @Test
    fun incrementUpdatesCount() {
        val state = HelloState()

        state.increment()
        state.increment()

        assertEquals(2, state.count)
    }
}