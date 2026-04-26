package com.x.rxsciapp.data.remote

import com.x.rxsciapp.model.ConnectionSettings
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MobileRealtimeClient {
    interface Listener {
        fun onConnecting()
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onTextMessage(text: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null

    fun connect(settings: ConnectionSettings, listener: Listener) {
        disconnect()
        val wsUrl = toWebSocketUrl(settings.baseUrl) ?: run {
            listener.onDisconnected("Server URL is invalid")
            return
        }
        listener.onConnecting()
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onConnected()
                    webSocket.send(
                        """
                        {
                          "type":"auth",
                          "token":${json(settings.token)},
                          "client_id":${json(settings.clientId)},
                          "device_name":${json(settings.deviceName)},
                          "subscribe_all":true
                        }
                        """.trimIndent()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onTextMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onDisconnected(reason.ifBlank { "Connection closed" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onDisconnected(t.message ?: "Network failure")
                }
            },
        )
    }

    fun send(text: String): Boolean = webSocket?.send(text) == true

    fun disconnect() {
        webSocket?.close(1000, "client reset")
        webSocket = null
    }

    private fun toWebSocketUrl(baseUrl: String): String? {
        val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val scheme = when (url.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            "ws", "wss" -> url.scheme
            else -> return null
        }
        return url.newBuilder()
            .scheme(scheme)
            .addPathSegments("mobile/ws")
            .build()
            .toString()
    }

    private fun json(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
