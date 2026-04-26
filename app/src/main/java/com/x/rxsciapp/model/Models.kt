package com.x.rxsciapp.model

data class ConnectionSettings(
    val baseUrl: String = "",
    val token: String = "",
    val clientId: String = "",
    val deviceName: String = "",
)

data class DiscoveredServer(
    val baseUrl: String,
    val host: String,
    val port: Int,
    val name: String,
    val version: String,
)

data class LanDevice(
    val host: String,
    val aliveBy: String,
    val openPorts: List<Int>,
    val rxsciServer: DiscoveredServer?,
)

data class LanScanResult(
    val devices: List<LanDevice>,
    val servers: List<DiscoveredServer>,
)

data class SessionItem(
    val sessionId: String,
    val title: String,
    val sourceChannel: String,
    val chatId: String,
    val lastMessage: String,
    val updatedAt: String,
    val writable: Boolean,
    val archived: Boolean,
)

data class AttachmentItem(
    val attachmentId: String,
    val messageId: String,
    val name: String,
    val kind: String,
    val size: Long,
    val downloadPath: String?,
    val localUri: String?,
)

data class MessageItem(
    val messageId: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val sourceChannel: String,
    val replyTo: String?,
    val clientMessageId: String?,
    val deliveryState: String,
    val statusType: String?,
    val attachments: List<AttachmentItem>,
)

data class DraftAttachment(
    val uri: String,
    val name: String,
    val size: Long,
    val kind: String,
)

sealed interface ConnectionState {
    data object Offline : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val reason: String) : ConnectionState
}
