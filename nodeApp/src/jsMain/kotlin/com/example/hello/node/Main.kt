package com.example.hello.node

import com.example.hello.logic.HelloState

fun main() {
    val state = HelloState()
    state.increment()
    println("Hello from Kotlin/JS on Node.js. Count: ${state.count}")
}