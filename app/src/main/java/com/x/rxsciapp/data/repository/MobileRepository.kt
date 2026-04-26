package com.x.rxsciapp.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.x.rxsciapp.data.local.AttachmentEntity
import com.x.rxsciapp.data.local.MessageEntity
import com.x.rxsciapp.data.local.MessageWithAttachments
import com.x.rxsciapp.data.local.MobileDatabase
import com.x.rxsciapp.data.local.SessionEntity
import com.x.rxsciapp.data.preferences.ConnectionSettingsStore
import com.x.rxsciapp.data.remote.MobileRealtimeClient
import com.x.rxsciapp.model.AttachmentItem
import com.x.rxsciapp.model.ConnectionSettings
import com.x.rxsciapp.model.ConnectionState
import com.x.rxsciapp.model.DiscoveredServer
import com.x.rxsciapp.model.DraftAttachment
import com.x.rxsciapp.model.LanDevice
import com.x.rxsciapp.model.LanScanResult
import com.x.rxsciapp.model.MessageItem
import com.x.rxsciapp.model.SessionItem
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

class MobileRepository(
    private val context: Context,
    private val database: MobileDatabase,
    private val settingsStore: ConnectionSettingsStore,
    private val realtimeClient: MobileRealtimeClient,
) {
    private companion object {
        const val LAN_SCAN_TIMEOUT_MS = 3_000L
        const val LAN_SCAN_CONCURRENCY = 96
        const val HOST_REACHABLE_TIMEOUT_MS = 350
        const val TCP_CONNECT_TIMEOUT_MS = 180
        val ALIVE_PROBE_PORTS = listOf(8765, 80, 443, 22, 445, 3389, 8080)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()
    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .callTimeout(1_200, TimeUnit.MILLISECONDS)
        .build()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    private val _settings = settingsStore.settings.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ConnectionSettings(),
    )

    val connectionState: StateFlow<ConnectionState> = _connectionState
    val settings: StateFlow<ConnectionSettings> = _settings

    init {
        scope.launch {
            settingsStore.settings
                .distinctUntilChanged()
                .collect { current ->
                    if (current.baseUrl.isBlank() || current.token.isBlank()) {
                        realtimeClient.disconnect()
                        _connectionState.value = ConnectionState.Offline
                    } else {
                        realtimeClient.connect(current, socketListener)
                    }
                }
        }
    }

    val sessions: Flow<List<SessionItem>> = database.sessionDao().observeSessions().map { items ->
        items.map {
            SessionItem(
                sessionId = it.sessionId,
                title = it.title,
                sourceChannel = it.sourceChannel,
                chatId = it.chatId,
                lastMessage = it.lastMessage,
                updatedAt = it.updatedAt,
                writable = it.writable,
                archived = it.archived,
            )
        }
    }

    fun messages(sessionId: String): Flow<List<MessageItem>> {
        return database.messageDao().observeMessages(sessionId).map { bundles ->
            bundles.map { bundle -> bundle.toModel() }
        }
    }

    fun session(sessionId: String): Flow<SessionItem?> {
        return database.sessionDao().observeSession(sessionId).map { session ->
            session?.let {
                SessionItem(
                    sessionId = it.sessionId,
                    title = it.title,
                    sourceChannel = it.sourceChannel,
                    chatId = it.chatId,
                    lastMessage = it.lastMessage,
                    updatedAt = it.updatedAt,
                    writable = it.writable,
                    archived = it.archived,
                )
            }
        }
    }

    suspend fun setSessionArchived(sessionId: String, archived: Boolean) {
        database.sessionDao().setArchived(sessionId, archived)
    }

    suspend fun downloadAttachment(attachment: AttachmentItem): Result<Uri> {
        attachment.localUri?.let {
            return Result.success(Uri.parse(it))
        }

        val path = attachment.downloadPath
            ?: return Result.failure(IOException("Attachment has no download path"))

        return runCatching {
            val current = settings.first()
            val baseUrl = current.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IOException("Invalid server URL")
            val downloadUrl = baseUrl.newBuilder()
                .addPathSegments(path.trimStart('/'))
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer ${current.token}")
                .build()

            val targetDir = File(context.cacheDir, "rxsci_downloads").apply { mkdirs() }
            val safeName = attachment.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val targetFile = File(targetDir, "${attachment.attachmentId}_$safeName")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed: ${response.code}")
                }
                response.body?.byteStream()?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
                    }
                } ?: throw IOException("Empty response")
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile,
            )
            database.messageDao().updateAttachmentLocalUri(
                attachment.attachmentId,
                uri.toString(),
            )
            uri
        }
    }

    suspend fun saveSettings(baseUrl: String, token: String, deviceName: String) {
        val current = settings.first()
        settingsStore.save(
            current.copy(
                baseUrl = baseUrl,
                token = token,
                deviceName = deviceName,
                clientId = current.clientId.ifBlank { UUID.randomUUID().toString() },
            )
        )
    }

    suspend fun scanLan(port: Int = 8765): Result<LanScanResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val hosts = localSubnetHosts()
                val semaphore = Semaphore(LAN_SCAN_CONCURRENCY)
                val devices = withTimeoutOrNull(LAN_SCAN_TIMEOUT_MS) {
                    hosts.map { host ->
                        async {
                            semaphore.withPermit {
                                probeLanDevice(host, port)
                            }
                        }
                    }.awaitAll()
                }.orEmpty()
                    .filterNotNull()
                    .distinctBy { it.host }
                    .sortedBy { it.host }
                LanScanResult(
                    devices = devices,
                    servers = devices.mapNotNull { it.rxsciServer }
                        .distinctBy { it.baseUrl }
                        .sortedBy { it.host },
                )
            }
        }
    }

    suspend fun discoverLanServers(port: Int = 8765): Result<List<DiscoveredServer>> {
        return scanLan(port).map { it.servers }
    }

    suspend fun createSession(): String {
        val chatId = UUID.randomUUID().toString().take(12)
        val sessionId = "mobile:$chatId"
        database.sessionDao().upsert(
            SessionEntity(
                sessionId = sessionId,
                title = "Mission ${chatId.takeLast(4)}",
                sourceChannel = "mobile",
                chatId = chatId,
                lastMessage = "",
                updatedAt = now(),
                writable = true,
                archived = false,
            )
        )
        return sessionId
    }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        attachments: List<Uri>,
    ): Result<Unit> {
        val writableSessionId = if (sessionId.isBlank()) createSession() else sessionId
        val draftId = "local_${UUID.randomUUID()}"
        val clientMessageId = UUID.randomUUID().toString()
        val draftAttachments = attachments.map { uri ->
            val doc = DocumentFile.fromSingleUri(context, uri)
            DraftAttachment(
                uri = uri.toString(),
                name = doc?.name ?: "attachment",
                size = doc?.length() ?: 0L,
                kind = guessKind(doc?.name.orEmpty()),
            )
        }

        upsertLocalDraft(
            sessionId = writableSessionId,
            messageId = draftId,
            clientMessageId = clientMessageId,
            content = text,
            attachments = draftAttachments,
        )

        return runCatching {
            val current = settings.first()
            val uploaded = draftAttachments.map { uploadAttachment(current, it) }
            val payload = JSONObject().apply {
                put("type", "send_message")
                put("session_id", writableSessionId)
                put("content", text)
                put("client_message_id", clientMessageId)
                put(
                    "attachments",
                    JSONArray().apply {
                        uploaded.forEach { item ->
                            put(
                                JSONObject().apply {
                                    put("upload_id", item)
                                }
                            )
                        }
                    }
                )
            }
            if (!realtimeClient.send(payload.toString())) {
                throw IOException("WebSocket is not connected")
            }
        }.onFailure {
            database.messageDao().upsert(
                MessageEntity(
                    messageId = draftId,
                    sessionId = writableSessionId,
                    role = "user",
                    content = text,
                    timestamp = now(),
                    sourceChannel = "mobile",
                    replyTo = null,
                    clientMessageId = clientMessageId,
                    deliveryState = "failed",
                    statusType = null,
                )
            )
        }
    }

    private suspend fun upsertLocalDraft(
        sessionId: String,
        messageId: String,
        clientMessageId: String,
        content: String,
        attachments: List<DraftAttachment>,
    ) {
        database.messageDao().upsert(
            MessageEntity(
                messageId = messageId,
                sessionId = sessionId,
                role = "user",
                content = content,
                timestamp = now(),
                sourceChannel = "mobile",
                replyTo = null,
                clientMessageId = clientMessageId,
                deliveryState = "sending",
                statusType = null,
            )
        )
        database.messageDao().deleteAttachmentsForMessage(messageId)
        database.messageDao().upsertAttachments(
            attachments.map {
                AttachmentEntity(
                    attachmentId = UUID.randomUUID().toString(),
                    messageId = messageId,
                    name = it.name,
                    kind = it.kind,
                    size = it.size,
                    downloadPath = null,
                    localUri = it.uri,
                )
            }
        )
    }

    private suspend fun uploadAttachment(
        settings: ConnectionSettings,
        attachment: DraftAttachment,
    ): String {
        val endpoint = settings.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("mobile/upload")
            ?.build()
            ?: throw IOException("Invalid server URL")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                attachment.name,
                UriRequestBody(
                    context = context,
                    uri = Uri.parse(attachment.uri),
                    contentType = "application/octet-stream".toMediaType(),
                    contentLength = attachment.size,
                )
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${settings.token}")
            .addHeader("X-Mobile-Client", settings.clientId)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload failed: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            return json.getString("upload_id")
        }
    }

    private fun localSubnetHosts(): List<String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return emptyList()
        val activeNetwork = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return emptyList()
        return linkProperties.linkAddresses
            .mapNotNull { it.toIpv4Host() }
            .flatMap { host ->
                val parts = host.split(".")
                if (parts.size != 4) return@flatMap emptyList()
                val prefix = parts.take(3).joinToString(".")
                (1..254).map { "$prefix.$it" }.filterNot { it == host }
            }
            .distinct()
    }

    private fun LinkAddress.toIpv4Host(): String? {
        val inetAddress = address as? Inet4Address ?: return null
        if (inetAddress.isLoopbackAddress || inetAddress.isLinkLocalAddress) return null
        return inetAddress.hostAddress
    }

    private fun probeLanDevice(host: String, rxsciPort: Int): LanDevice? {
        val rxsciServer = probeMobileServer(host, rxsciPort)
        val openPorts = ALIVE_PROBE_PORTS
            .distinct()
            .filter { port -> rxsciServer?.port == port || canConnect(host, port) }
        val reachable = rxsciServer != null ||
            openPorts.isNotEmpty() ||
            canReachHost(host)
        if (!reachable) return null

        val aliveBy = when {
            rxsciServer != null -> "rxsci"
            openPorts.isNotEmpty() -> "tcp"
            else -> "reachable"
        }
        return LanDevice(
            host = host,
            aliveBy = aliveBy,
            openPorts = openPorts,
            rxsciServer = rxsciServer,
        )
    }

    private fun canReachHost(host: String): Boolean {
        return runCatching {
            InetAddress.getByName(host).isReachable(HOST_REACHABLE_TIMEOUT_MS)
        }.getOrDefault(false)
    }

    private fun canConnect(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)
    }

    private fun probeMobileServer(host: String, port: Int): DiscoveredServer? {
        val baseUrl = "http://$host:$port"
        val request = Request.Builder()
            .url("$baseUrl/mobile/discover")
            .addHeader("Accept", "application/json")
            .build()
        return runCatching {
            discoveryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                if (json.optString("service") != "rxsci-mobile") return@runCatching null
                DiscoveredServer(
                    baseUrl = json.optString("base_url").ifBlank { baseUrl },
                    host = host,
                    port = port,
                    name = json.optString("name").ifBlank { "RxSci Mobile" },
                    version = json.optString("version").ifBlank { "unknown" },
                )
            }
        }.getOrNull()
    }

    private val socketListener = object : MobileRealtimeClient.Listener {
        override fun onConnecting() {
            _connectionState.value = ConnectionState.Connecting
        }

        override fun onConnected() {
            _connectionState.value = ConnectionState.Connected
        }

        override fun onDisconnected(reason: String) {
            _connectionState.value = ConnectionState.Error(reason)
        }

        override fun onTextMessage(text: String) {
            scope.launch {
                processIncoming(text)
            }
        }
    }

    private suspend fun processIncoming(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (root.optString("type")) {
            "auth_ok" -> _connectionState.value = ConnectionState.Connected
            "history_sync" -> {
                upsertSessions(root.optJSONArray("sessions"))
                val events = root.optJSONArray("events") ?: JSONArray()
                for (index in 0 until events.length()) {
                    processIncoming(events.getJSONObject(index).toString())
                }
            }

            "message" -> upsertMessageEvent(root)
            "thinking", "todo" -> upsertStatusEvent(root)
            "error" -> _connectionState.value =
                ConnectionState.Error(root.optString("message", "Server error"))
        }
    }

    private suspend fun upsertSessions(items: JSONArray?) {
        if (items == null) return
        val sessions = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val sessionId = item.optString("session_id")
                val existing = database.sessionDao().findSession(sessionId)
                add(
                    SessionEntity(
                        sessionId = sessionId,
                        title = item.optString("title"),
                        sourceChannel = item.optString("source_channel"),
                        chatId = item.optString("chat_id"),
                        lastMessage = item.optString("last_message"),
                        updatedAt = item.optString("updated_at"),
                        writable = item.optBoolean("writable"),
                        archived = existing?.archived ?: item.optBoolean("archived", false),
                    )
                )
            }
        }
        database.sessionDao().upsertAll(sessions)
    }

    private suspend fun upsertMessageEvent(root: JSONObject) {
        val session = root.optJSONObject("session") ?: return
        val message = root.optJSONObject("message") ?: return
        val sessionId = session.optString("session_id")
        val existingSession = database.sessionDao().findSession(sessionId)
        database.sessionDao().upsert(
            SessionEntity(
                sessionId = sessionId,
                title = session.optString("title"),
                sourceChannel = session.optString("source_channel"),
                chatId = session.optString("chat_id"),
                lastMessage = session.optString("last_message"),
                updatedAt = session.optString("updated_at"),
                writable = session.optBoolean("writable"),
                archived = existingSession?.archived ?: session.optBoolean("archived", false),
            )
        )

        val clientMessageId = message.optString("client_message_id").ifBlank { null }
        if (clientMessageId != null) {
            val local = database.messageDao().findByClientMessageId(sessionId, clientMessageId)
            if (local != null && local.messageId != message.optString("message_id")) {
                database.messageDao().deleteAttachmentsForMessage(local.messageId)
                database.messageDao().deleteMessage(local.messageId)
            }
        }

        val messageId = message.optString("message_id")
        database.messageDao().upsert(
            MessageEntity(
                messageId = messageId,
                sessionId = sessionId,
                role = message.optString("role"),
                content = message.optString("content"),
                timestamp = message.optString("timestamp"),
                sourceChannel = message.optString("source_channel"),
                replyTo = message.optString("reply_to").ifBlank { null },
                clientMessageId = clientMessageId,
                deliveryState = "sent",
                statusType = null,
            )
        )
        database.messageDao().deleteAttachmentsForMessage(messageId)
        val attachments = message.optJSONArray("attachments") ?: JSONArray()
        database.messageDao().upsertAttachments(
            buildList {
                for (index in 0 until attachments.length()) {
                    val item = attachments.optJSONObject(index) ?: continue
                    add(
                        AttachmentEntity(
                            attachmentId = item.optString("file_id").ifBlank {
                                UUID.randomUUID().toString()
                            },
                            messageId = messageId,
                            name = item.optString("name"),
                            kind = item.optString("kind"),
                            size = item.optLong("size"),
                            downloadPath = item.optString("download_path").ifBlank { null },
                            localUri = null,
                        )
                    )
                }
            }
        )
    }

    private suspend fun upsertStatusEvent(root: JSONObject) {
        val session = root.optJSONObject("session") ?: return
        val sessionId = session.optString("session_id")
        val existingSession = database.sessionDao().findSession(sessionId)
        database.sessionDao().upsert(
            SessionEntity(
                sessionId = sessionId,
                title = session.optString("title"),
                sourceChannel = session.optString("source_channel"),
                chatId = session.optString("chat_id"),
                lastMessage = session.optString("last_message"),
                updatedAt = session.optString("updated_at"),
                writable = session.optBoolean("writable"),
                archived = existingSession?.archived ?: session.optBoolean("archived", false),
            )
        )
        database.messageDao().upsert(
            MessageEntity(
                messageId = root.optString("event_id").ifBlank { UUID.randomUUID().toString() },
                sessionId = sessionId,
                role = "system",
                content = root.optString("content"),
                timestamp = root.optString("timestamp"),
                sourceChannel = session.optString("source_channel"),
                replyTo = null,
                clientMessageId = null,
                deliveryState = "sent",
                statusType = root.optString("type"),
            )
        )
    }

    private fun MessageWithAttachments.toModel(): MessageItem {
        return MessageItem(
            messageId = message.messageId,
            sessionId = message.sessionId,
            role = message.role,
            content = message.content,
            timestamp = message.timestamp,
            sourceChannel = message.sourceChannel,
            replyTo = message.replyTo,
            clientMessageId = message.clientMessageId,
            deliveryState = message.deliveryState,
            statusType = message.statusType,
            attachments = attachments.map {
                AttachmentItem(
                    attachmentId = it.attachmentId,
                    messageId = it.messageId,
                    name = it.name,
                    kind = it.kind,
                    size = it.size,
                    downloadPath = it.downloadPath,
                    localUri = it.localUri,
                )
            },
        )
    }

    private fun now(): String = java.time.OffsetDateTime.now().toString()

    private fun guessKind(name: String): String {
        val lower = name.lowercase()
        return if (
            lower.endsWith(".png") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".webp")
        ) {
            "image"
        } else {
            "file"
        }
    }

    private class UriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val contentType: okhttp3.MediaType,
        private val contentLength: Long,
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType = contentType

        override fun contentLength(): Long {
            return if (contentLength > 0L) contentLength else -1L
        }

        override fun writeTo(sink: BufferedSink) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                }
            } ?: throw IOException("Unable to read upload stream")
        }
    }
}
