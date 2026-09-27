package com.example.hello.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun App(configurationStore: ConfigurationStore) {
    val initialConfiguration = remember(configurationStore) { configurationStore.load() }
    var configuration by remember(configurationStore) { mutableStateOf(initialConfiguration) }
    var showConfiguration by remember(configurationStore) {
        mutableStateOf(initialConfiguration == null)
    }
    var serverUrl by remember { mutableStateOf(initialConfiguration?.serverUrl.orEmpty()) }
    var accessToken by remember { mutableStateOf(initialConfiguration?.accessToken.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("RSS Reader", style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = {
                        serverUrl = configuration?.serverUrl.orEmpty()
                        accessToken = configuration?.accessToken.orEmpty()
                        validationError = null
                        showConfiguration = true
                    },
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Configure Miniflux")
                }
            }

            if (configuration == null) {
                Text("Configure a Miniflux server to get started.")
            } else {
                Text("Configured server: ${configuration?.serverUrl}")
            }
        }

        if (showConfiguration) {
            AlertDialog(
                onDismissRequest = { if (configuration != null) showConfiguration = false },
                title = { Text("Miniflux configuration") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = {
                                serverUrl = it
                                validationError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Server URL") },
                            placeholder = { Text("https://miniflux.example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = accessToken,
                            onValueChange = {
                                accessToken = it
                                validationError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Access token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        configurationStore.storageNotice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        validationError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val normalizedUrl = normalizeServerUrl(serverUrl)
                            when {
                                normalizedUrl == null -> validationError =
                                    "Enter a valid HTTP or HTTPS server URL."
                                accessToken.isBlank() -> validationError =
                                    "Enter the Miniflux access token."
                                else -> {
                                    val updatedConfiguration = MinifluxConfiguration(
                                        serverUrl = normalizedUrl,
                                        accessToken = accessToken.trim(),
                                    )
                                    try {
                                        configurationStore.save(updatedConfiguration)
                                        configuration = updatedConfiguration
                                        showConfiguration = false
                                        validationError = null
                                    } catch (_: Exception) {
                                        validationError = "Could not save the configuration on this device."
                                    }
                                }
                            }
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Row {
                        if (configuration != null) {
                            TextButton(
                                onClick = {
                                    try {
                                        configurationStore.clear()
                                        configuration = null
                                        serverUrl = ""
                                        accessToken = ""
                                        validationError = null
                                    } catch (_: Exception) {
                                        validationError = "Could not clear the saved configuration."
                                    }
                                },
                            ) {
                                Text("Forget")
                            }
                            TextButton(onClick = { showConfiguration = false }) {
                                Text("Cancel")
                            }
                        }
                    }
                },
            )
        }
    }
}