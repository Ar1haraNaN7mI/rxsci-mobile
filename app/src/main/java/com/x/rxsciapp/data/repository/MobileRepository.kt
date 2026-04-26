package com.x.rxsciapp.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.supervisorScope
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
        const val TAG = "RxSciLanScan"
        const val LAN_SCAN_TIMEOUT_MS = 15_000L
        const val LAN_SCAN_CONCURRENCY = 64
        const val TCP_CONNECT_TIMEOUT_MS = 500
        const val ICMP_TIMEOUT_MS = 1000
        const val UDP_BROADCAST_PORT = 8766
        const val UDP_LISTEN_TIMEOUT_MS = 4000
        val PROBE_PORTS = listOf(8765, 80, 443, 22, 8080)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()
    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
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
                    Log.d(TAG, "settings changed: baseUrl=${current.baseUrl} token=${current.token.take(4)}...")
                    if (current.baseUrl.isBlank() || current.token.isBlank()) {
                        realtimeClient.disconnect()
                        _connectionState.value = ConnectionState.Offline
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

    suspend fun deleteSession(sessionId: String) {
        database.withTransaction {
            database.messageDao().deleteMessagesForSession(sessionId)
            database.sessionDao().deleteSession(sessionId)
        }
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

    fun connectWith(s: ConnectionSettings) {
        Log.d(TAG, "connectWith: baseUrl=${s.baseUrl} token=${s.token.take(4)}... clientId=${s.clientId}")
        if (s.baseUrl.isNotBlank()) {
            _connectionState.value = ConnectionState.Connecting
            realtimeClient.connect(s, socketListener)
        } else {
            realtimeClient.disconnect()
            _connectionState.value = ConnectionState.Offline
            Log.d(TAG, "connectWith: skipped, baseUrl is blank")
        }
    }

    fun reconnect() {
        scope.launch {
            val current = settingsStore.settings.first()
            connectWith(current)
        }
    }

    suspend fun saveAndConnect(baseUrl: String, token: String, deviceName: String) {
        val current = settings.first()
        val updated = current.copy(
            baseUrl = baseUrl,
            token = token,
            deviceName = deviceName,
            clientId = current.clientId.ifBlank { UUID.randomUUID().toString() },
        )
        settingsStore.save(updated)
        connectWith(updated)
    }

    suspend fun scanLan(port: Int = 8765): Result<LanScanResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val log = StringBuilder()
                val (localIp, hosts) = localSubnetHosts(log)
                Log.d(TAG, "scanLan: localIp=$localIp, hosts=${hosts.size}")
                log.appendLine("[scan] localIp=$localIp, hosts=${hosts.size}")

                if (hosts.isEmpty()) {
                    log.appendLine("[scan] ABORT: no hosts to scan")
                    return@runCatching LanScanResult(
                        debugLog = log.toString(),
                        localIp = localIp,
                    )
                }

                val semaphore = Semaphore(LAN_SCAN_CONCURRENCY)
                val found = java.util.concurrent.ConcurrentLinkedQueue<LanDevice>()
                val debugHits = java.util.concurrent.ConcurrentLinkedQueue<String>()

                withTimeoutOrNull(LAN_SCAN_TIMEOUT_MS) {
                    supervisorScope {
                        hosts.map { host ->
                            async {
                                semaphore.withPermit {
                                    val device = probeLanDevice(host, port)
                                    if (device != null) {
                                        found.add(device)
                                        val msg = "[hit] ${device.host} alive=${device.aliveBy} ports=${device.openPorts} rxsci=${device.rxsciServer != null}"
                                        debugHits.add(msg)
                                        Log.d(TAG, msg)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } ?: log.appendLine("[scan] TIMEOUT after ${LAN_SCAN_TIMEOUT_MS}ms")

                val devices = found.toList()
                    .distinctBy { it.host }
                    .sortedBy { it.host }
                debugHits.forEach { log.appendLine(it) }
                log.appendLine("[scan] done: ${devices.size} device(s)")
                Log.d(TAG, "scanLan done: ${devices.size} devices")

                LanScanResult(
                    devices = devices,
                    servers = devices.mapNotNull { it.rxsciServer }
                        .distinctBy { it.baseUrl }
                        .sortedBy { it.host },
                    localIp = localIp,
                    hostsScanned = hosts.size,
                    debugLog = log.toString(),
                )
            }
        }
    }

    suspend fun discoverLanServers(port: Int = 8765): Result<List<DiscoveredServer>> {
        return scanLan(port).map { it.servers }
    }

    suspend fun listenForBroadcast(): List<DiscoveredServer> {
        return withContext(Dispatchers.IO) {
            val servers = mutableListOf<DiscoveredServer>()
            val seen = mutableSetOf<String>()
            runCatching {
                DatagramSocket(UDP_BROADCAST_PORT).use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = UDP_LISTEN_TIMEOUT_MS
                    val buf = ByteArray(1024)
                    val deadline = System.currentTimeMillis() + UDP_LISTEN_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buf, buf.size)
                        runCatching {
                            socket.receive(packet)
                            val data = String(packet.data, 0, packet.length)
                            val json = JSONObject(data)
                            if (json.optString("service") == "rxsci-mobile") {
                                val host = packet.address.hostAddress ?: return@runCatching
                                if (seen.add(host)) {
                                    val port = json.optInt("port", 8765)
                                    val baseUrl = "http://$host:$port"
                                    Log.d(TAG, "[udp] beacon from $host:$port")
                                    servers.add(
                                        DiscoveredServer(
                                            baseUrl = baseUrl,
                                            host = host,
                                            port = port,
                                            name = json.optString("name").ifBlank { "RxSci" },
                                            version = json.optString("version").ifBlank { "?" },
                                            token = json.optString("token"),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            servers
        }
    }

    suspend fun autoDiscover(): DiscoveredServer? {
        Log.d(TAG, "autoDiscover: starting UDP listen + subnet scan")
        val udpDeferred = scope.async(Dispatchers.IO) { listenForBroadcast() }
        val scanDeferred = scope.async(Dispatchers.IO) {
            scanLan().getOrNull()?.servers.orEmpty()
        }
        val udpServers = udpDeferred.await()
        if (udpServers.isNotEmpty()) {
            scanDeferred.cancel()
            Log.d(TAG, "autoDiscover: found via UDP: ${udpServers.first().baseUrl}")
            return udpServers.first()
        }
        val scanServers = scanDeferred.await()
        Log.d(TAG, "autoDiscover: scan found ${scanServers.size} server(s)")
        return scanServers.firstOrNull()
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

    private fun localSubnetHosts(log: StringBuilder): Pair<String, List<String>> {
        val localIp = findLocalWifiIpv4(log)
        if (localIp.isNullOrBlank()) {
            log.appendLine("[hosts] no local IP found")
            return "" to emptyList()
        }
        val parts = localIp.split(".")
        if (parts.size != 4) {
            log.appendLine("[hosts] bad IP format: $localIp")
            return localIp to emptyList()
        }
        val prefix = parts.take(3).joinToString(".")
        val hosts = (0..255).map { "$prefix.$it" }
        log.appendLine("[hosts] subnet=$prefix.0/24, total=${hosts.size}")
        return localIp to hosts
    }

    @Suppress("DEPRECATION")
    private fun findLocalWifiIpv4(log: StringBuilder): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        if (cm != null) {
            try {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val isWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    val lp = cm.getLinkProperties(network)
                    val addrs = lp?.linkAddresses?.mapNotNull { it.toIpv4Host() }.orEmpty()
                    log.appendLine("[net] network=$network wifi=$isWifi addrs=$addrs")
                    Log.d(TAG, "[net] network=$network wifi=$isWifi addrs=$addrs")
                    if (isWifi && addrs.isNotEmpty()) return addrs.first()
                }
            } catch (e: Exception) {
                log.appendLine("[net] allNetworks error: ${e.message}")
            }
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val lp = cm.getLinkProperties(activeNet)
                val addrs = lp?.linkAddresses?.mapNotNull { it.toIpv4Host() }.orEmpty()
                log.appendLine("[net] activeNetwork=$activeNet addrs=$addrs")
                Log.d(TAG, "[net] activeNetwork=$activeNet addrs=$addrs")
                if (addrs.isNotEmpty()) return addrs.first()
            }
        } else {
            log.appendLine("[net] ConnectivityManager is null")
        }
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }
                log.appendLine("[net] iface=${iface.name} up=${iface.isUp} addrs=$addrs")
                Log.d(TAG, "[net] iface=${iface.name} addrs=$addrs")
                if (addrs.isNotEmpty()) return@runCatching addrs.first()
            }
            null
        }.getOrNull()
    }

    private fun LinkAddress.toIpv4Host(): String? {
        val inetAddress = address as? Inet4Address ?: return null
        if (inetAddress.isLoopbackAddress || inetAddress.isLinkLocalAddress) return null
        return inetAddress.hostAddress
    }

    private fun probeLanDevice(host: String, rxsciPort: Int): LanDevice? {
        val openPorts = mutableListOf<Int>()
        for (port in PROBE_PORTS) {
            if (canConnect(host, port)) openPorts.add(port)
        }
        val icmpOk = if (openPorts.isEmpty()) canReachIcmp(host) else false

        if (openPorts.isEmpty() && !icmpOk) return null

        val rxsciServer = if (openPorts.contains(rxsciPort)) {
            probeMobileServer(host, rxsciPort)
        } else null

        val aliveBy = when {
            rxsciServer != null -> "rxsci"
            openPorts.isNotEmpty() -> "tcp"
            else -> "icmp"
        }
        return LanDevice(
            host = host,
            aliveBy = aliveBy,
            openPorts = openPorts,
            rxsciServer = rxsciServer,
        )
    }

    private fun canConnect(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)
    }

    private fun canReachIcmp(host: String): Boolean {
        return runCatching {
            InetAddress.getByName(host).isReachable(ICMP_TIMEOUT_MS)
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
                    token = json.optString("token"),
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
