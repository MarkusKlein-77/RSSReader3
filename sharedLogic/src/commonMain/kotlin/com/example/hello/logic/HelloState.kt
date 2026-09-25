package com.example.hello.logic

class HelloState {
    var count: Int = 0
        private set

    fun increment() {
        count += 1
    }
}