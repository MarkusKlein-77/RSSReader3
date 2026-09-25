package com.example.hello.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hello.logic.HelloState

@Composable
fun App() {
    val state = remember { HelloState() }
    var count by remember { mutableStateOf(state.count) }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Hello from Kotlin Multiplatform", style = MaterialTheme.typography.headlineSmall)
            Text("This screen is shared by Android, JVM desktop, and WebAssembly.")
            Button(onClick = {
                state.increment()
                count = state.count
            }) {
                Text("Clicked $count times")
            }
        }
    }
}