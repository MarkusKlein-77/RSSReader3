package com.example.hello.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource
import kotlinx.coroutines.launch

@Composable
fun App(configurationStore: ConfigurationStore) {
    val api = remember { MinifluxApi() }
    DisposableEffect(api) { onDispose { api.close() } }
    val scope = rememberCoroutineScope()
    val openArticle = rememberArticleOpener()
    val initialConfiguration = remember(configurationStore) { configurationStore.load() }
    var configuration by remember(configurationStore) { mutableStateOf(initialConfiguration) }
    var showConfiguration by remember(configurationStore) {
        mutableStateOf(initialConfiguration == null)
    }
    var serverUrl by remember { mutableStateOf(initialConfiguration?.serverUrl.orEmpty()) }
    var accessToken by remember { mutableStateOf(initialConfiguration?.accessToken.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var articles by remember { mutableStateOf<List<ArticleHeader>>(emptyList()) }
    var showAllArticles by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }

    suspend fun loadArticles(
        configurationToLoad: MinifluxConfiguration,
        includeRead: Boolean,
        refreshServer: Boolean,
    ) {
        isRefreshing = true
        refreshError = null
        try {
            articles = if (refreshServer) {
                api.refreshArticles(configurationToLoad, includeRead)
            } else {
                api.loadArticles(configurationToLoad, includeRead)
            }
        } catch (exception: Exception) {
            refreshError = exception.message ?: "Could not refresh articles. Check your connection."
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(configuration) {
        configuration?.let { loadArticles(it, showAllArticles, refreshServer = true) }
    }

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
                Row {
                    TextButton(
                        onClick = {
                            val includeRead = !showAllArticles
                            showAllArticles = includeRead
                            configuration?.let { selected ->
                                scope.launch {
                                    loadArticles(selected, includeRead, refreshServer = includeRead)
                                }
                            }
                        },
                        enabled = configuration != null && !isRefreshing,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(if (showAllArticles) "Unread articles" else "All articles")
                    }
                    IconButton(
                        onClick = {
                            configuration?.let { selected ->
                                scope.launch {
                                    loadArticles(selected, showAllArticles, refreshServer = true)
                                }
                            }
                        },
                        enabled = configuration != null && !isRefreshing,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh articles")
                    }
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
            }

            if (configuration == null) {
                Text("Configure a Miniflux server to get started.")
            } else {
                when {
                    isRefreshing -> Text("Refreshing articles...")
                    refreshError != null -> Text(refreshError!!, color = MaterialTheme.colorScheme.error)
                    articles.isEmpty() -> Text(
                        if (showAllArticles) "No articles." else "No unread articles.",
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(articles, key = ArticleHeader::id) { article ->
                        ArticleTile(article) {
                            article.url.takeIf(String::isNotBlank)?.let(openArticle)
                        }
                    }
                }
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
                            enabled = !isConnecting,
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
                            enabled = !isConnecting,
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
                        enabled = !isConnecting,
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
                                    scope.launch {
                                        isConnecting = true
                                        validationError = null
                                        try {
                                            api.validate(updatedConfiguration)
                                            configurationStore.save(updatedConfiguration)
                                            configuration = updatedConfiguration
                                            showConfiguration = false
                                        } catch (exception: Exception) {
                                            validationError = exception.message
                                                ?: "Could not connect to Miniflux. Check the URL and access token."
                                        } finally {
                                            isConnecting = false
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(if (isConnecting) "Connecting..." else "Connect")
                    }
                },
                dismissButton = {
                    Row {
                        if (configuration != null) {
                            TextButton(
                                enabled = !isConnecting,
                                onClick = {
                                    try {
                                        configurationStore.clear()
                                        configuration = null
                                        articles = emptyList()
                                        refreshError = null
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
                            TextButton(
                                enabled = !isConnecting,
                                onClick = { showConfiguration = false },
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ArticleTile(article: ArticleHeader, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(260.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                article.imageUrl?.takeIf(String::isNotBlank)?.let { imageUrl ->
                    KamelImage(
                        resource = asyncPainterResource(data = imageUrl),
                        contentDescription = article.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                            ),
                        ),
                )
                Text(
                    text = article.title,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    article.sourceIconUrl?.takeIf(String::isNotBlank)?.let { iconUrl ->
                        KamelImage(
                            resource = asyncPainterResource(data = iconUrl),
                            contentDescription = "${article.sourceTitle} icon",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = article.sourceTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = article.publishedAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}