package com.x.rxsciapp.app

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.x.rxsciapp.data.repository.MobileRepository
import com.x.rxsciapp.model.AttachmentItem
import com.x.rxsciapp.model.ConnectionState
import com.x.rxsciapp.model.DiscoveredServer
import com.x.rxsciapp.model.MessageItem
import com.x.rxsciapp.model.SessionItem
import com.x.rxsciapp.ui.ChatViewModel
import com.x.rxsciapp.ui.RepositoryFactory
import com.x.rxsciapp.ui.SessionsViewModel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

@Composable
fun RxsciMobileApp(container: AppContainer) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    NavHost(
        navController = navController,
        startDestination = "sessions",
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                    )
                )
            ),
    ) {
        composable("sessions") {
            val viewModel: SessionsViewModel = viewModel(
                factory = RepositoryFactory { SessionsViewModel(container.repository) }
            )
            val sessions by viewModel.sessions.collectAsState()
            val connectionState by viewModel.connectionState.collectAsState()
            SessionsRoute(
                sessions = sessions,
                connectionState = connectionState,
                onOpenSettings = { navController.navigate("settings") },
                onOpenSession = { sessionId ->
                    val encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8.toString())
                    navController.navigate("chat/$encoded")
                },
                onCreateSession = {
                    viewModel.createSession { sessionId ->
                        val encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8.toString())
                        navController.navigate("chat/$encoded")
                    }
                },
                onSetArchived = viewModel::setArchived,
            )
        }

        composable(
            route = "chat/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = Uri.decode(entry.arguments?.getString("sessionId").orEmpty())
            val viewModel: ChatViewModel = viewModel(
                key = sessionId,
                factory = RepositoryFactory { ChatViewModel(container.repository, sessionId) }
            )
            val session by viewModel.session.collectAsState()
            val messages by viewModel.messages.collectAsState()
            ChatRoute(
                session = session,
                messages = messages,
                snackbarHostState = snackbarHostState,
                onBack = { navController.popBackStack() },
                onSendMessage = { text, attachments, onDone ->
                    viewModel.sendMessage(text, attachments, onDone)
                },
                onOpenAttachment = { attachment, onDone ->
                    viewModel.openAttachment(attachment, onDone)
                },
            )
        }

        composable("settings") {
            SettingsRoute(
                repository = container.repository,
                snackbarHostState = snackbarHostState,
                onBack = { navController.popBackStack() },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsRoute(
    sessions: List<SessionItem>,
    connectionState: ConnectionState,
    onOpenSettings: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val filteredSessions = remember(sessions, query, showArchived) {
        val normalizedQuery = query.trim().lowercase()
        sessions.filter { session ->
            val matchesArchive = session.archived == showArchived
            val matchesQuery = normalizedQuery.isBlank() ||
                session.title.lowercase().contains(normalizedQuery) ||
                session.sessionId.lowercase().contains(normalizedQuery) ||
                session.lastMessage.lowercase().contains(normalizedQuery)
            matchesArchive && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RxSci Command Grid", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Industrial relay for Android 11+",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ConnectionBadge(connectionState)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateSession) {
                Icon(Icons.Outlined.Add, contentDescription = "Create session")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Multi-session relay",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Write tasks into mobile sessions. All mirrored service traffic stays visible here for monitoring.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search sessions") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = !showArchived,
                            onClick = { showArchived = false },
                            label = { Text("Active") },
                        )
                        FilterChip(
                            selected = showArchived,
                            onClick = { showArchived = true },
                            label = { Text("Archived") },
                        )
                    }
                }
            }

            if (sessions.isEmpty()) {
                item {
                    EmptySessionsCard(onCreateSession = onCreateSession)
                }
            } else if (filteredSessions.isEmpty()) {
                item {
                    EmptyFilteredSessionsCard(showArchived = showArchived)
                }
            } else {
                items(filteredSessions, key = { it.sessionId }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onOpenSession(session.sessionId) },
                        onArchiveToggle = {
                            onSetArchived(session.sessionId, !session.archived)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySessionsCard(onCreateSession: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No active sessions", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Create the first mobile session, then connect the app to the Rxscientist service.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreateSession) {
                Text("Create mobile session")
            }
        }
    }
}

@Composable
private fun EmptyFilteredSessionsCard(showArchived: Boolean) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No matching sessions", style = MaterialTheme.typography.titleMedium)
            Text(
                if (showArchived) {
                    "Archived sessions matching the current search will appear here."
                } else {
                    "Active sessions matching the current search will appear here."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionItem,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title.ifBlank { session.sessionId },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = session.sessionId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AssistChip(
                    onClick = onClick,
                    label = { Text(if (session.writable) "Writable" else "Mirror") },
                    leadingIcon = {
                        Icon(
                            if (session.writable) Icons.Outlined.CloudDone else Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                IconButton(onClick = onArchiveToggle) {
                    Icon(
                        if (session.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = if (session.archived) {
                            "Restore session"
                        } else {
                            "Archive session"
                        },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = session.lastMessage.ifBlank { "No messages yet" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = session.sourceChannel.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = session.updatedAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRoute(
    session: SessionItem?,
    messages: List<MessageItem>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSendMessage: (String, List<Uri>, (Boolean) -> Unit) -> Unit,
    onOpenAttachment: (AttachmentItem, (Result<Uri>) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Uri>() }
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
    ) { uris ->
        attachments.addAll(uris)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
        }
        attachments.addAll(uris)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.title ?: "Session")
                        Text(
                            session?.sessionId.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            ChatComposer(
                input = input,
                attachments = attachments.toList(),
                writable = session?.writable != false,
                onInputChange = { input = it },
                onPickImages = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = { attachments.remove(it) },
                onSend = {
                    val payload = input.trim()
                    if (payload.isBlank() && attachments.isEmpty()) return@ChatComposer
                    onSendMessage(payload, attachments.toList()) { success ->
                        scope.launch {
                            if (success) {
                                input = ""
                                attachments.clear()
                            } else {
                                snackbarHostState.showSnackbar("Send failed")
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session?.writable == false) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(
                            "This mirrored session is visible on mobile but intentionally read-only.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            items(messages, key = { it.messageId }) { item ->
                MessageBubble(
                    message = item,
                    onAttachmentClick = { attachment ->
                        onOpenAttachment(attachment) { result ->
                            scope.launch {
                                result
                                    .onSuccess { uri ->
                                        runCatching {
                                            openAttachmentUri(context, uri, attachment.name)
                                        }.onFailure {
                                            snackbarHostState.showSnackbar(
                                                it.message ?: "No app can open this attachment"
                                            )
                                        }
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar(
                                            it.message ?: "Open attachment failed"
                                        )
                                    }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageItem,
    onAttachmentClick: (AttachmentItem) -> Unit,
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val alignment = when {
        isSystem -> Alignment.CenterHorizontally
        isUser -> Alignment.End
        else -> Alignment.Start
    }
    val containerColor = when {
        isSystem -> MaterialTheme.colorScheme.secondaryContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        isSystem -> MaterialTheme.colorScheme.onSecondaryContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = if (isUser) 24.dp else 8.dp,
                bottomEnd = if (isUser) 8.dp else 24.dp,
            ),
            color = containerColor,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (!message.statusType.isNullOrBlank()) {
                    Text(
                        text = message.statusType.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (message.content.isNotBlank()) {
                    Text(message.content, color = contentColor)
                }
                if (message.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.attachments.forEach { attachment ->
                            AssistChip(
                                onClick = { onAttachmentClick(attachment) },
                                label = { Text(attachment.name) },
                                leadingIcon = {
                                    Icon(
                                        if (attachment.kind == "image") Icons.Outlined.Image else Icons.Outlined.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${message.sourceChannel.uppercase()} - ${message.timestamp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatComposer(
    input: String,
    attachments: List<Uri>,
    writable: Boolean,
    onInputChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (Uri) -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (attachments.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.height((attachments.size.coerceAtMost(3) * 52).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.toString() }) { attachment ->
                        AssistChip(
                            onClick = { onRemoveAttachment(attachment) },
                            label = {
                                Text(
                                    attachment.lastPathSegment ?: attachment.toString(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.AttachFile, contentDescription = null)
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                enabled = writable,
                label = { Text(if (writable) "Dispatch task" else "Read-only mirror") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledIconButton(onClick = onPickImages, enabled = writable) {
                        Icon(Icons.Outlined.Image, contentDescription = "Pick image")
                    }
                    FilledIconButton(onClick = onPickFiles, enabled = writable) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = "Pick file")
                    }
                }
                Button(onClick = onSend, enabled = writable) {
                    Text("Send")
                }
            }
        }
    }
}

private fun openAttachmentUri(
    context: android.content.Context,
    uri: Uri,
    fileName: String,
) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Open attachment"))
}

@Composable
private fun DiscoveredServerCard(
    server: DiscoveredServer,
    onUse: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${server.baseUrl} - ${server.version}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onUse) {
                Text("Use")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoute(
    repository: MobileRepository,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val settings by repository.settings.collectAsState()
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var deviceName by rememberSaveable(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server base URL") },
                supportingText = { Text("Example: http://192.168.1.10:8765") },
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "LAN discovery",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Scan the current subnet for running RxSci mobile servers.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            enabled = !scanning,
                            onClick = {
                                scope.launch {
                                    scanning = true
                                    val result = repository.discoverLanServers()
                                    scanning = false
                                    result
                                        .onSuccess {
                                            discoveredServers = it
                                            snackbarHostState.showSnackbar(
                                                "Found ${it.size} server(s)"
                                            )
                                        }
                                        .onFailure {
                                            snackbarHostState.showSnackbar(
                                                it.message ?: "LAN scan failed"
                                            )
                                        }
                                }
                            },
                        ) {
                            if (scanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Scan")
                            }
                        }
                    }
                    if (discoveredServers.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            discoveredServers.forEach { server ->
                                DiscoveredServerCard(
                                    server = server,
                                    onUse = { baseUrl = server.baseUrl },
                                )
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Mobile token") },
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Device name") },
            )
            Button(
                onClick = {
                    scope.launch {
                        repository.saveSettings(baseUrl, token, deviceName)
                        snackbarHostState.showSnackbar("Settings saved")
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save and reconnect")
            }
        }
    }
}

@Composable
private fun ConnectionBadge(connectionState: ConnectionState) {
    val (label, icon) = when (connectionState) {
        ConnectionState.Connected -> "Live" to Icons.Outlined.CloudDone
        ConnectionState.Connecting -> "Linking" to Icons.Outlined.CloudOff
        ConnectionState.Offline -> "Offline" to Icons.Outlined.CloudOff
        is ConnectionState.Error -> "Fault" to Icons.Outlined.ErrorOutline
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
