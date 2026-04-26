package com.x.rxsciapp.data.remote

import android.util.Log
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
    private var generation = 0

    fun connect(settings: ConnectionSettings, listener: Listener) {
        generation++
        val myGen = generation
        webSocket?.cancel()
        webSocket = null

        val wsUrl = toWebSocketUrl(settings.baseUrl) ?: run {
            Log.e(TAG, "connect: invalid URL '${settings.baseUrl}'")
            listener.onDisconnected("Server URL is invalid")
            return
        }
        Log.d(TAG, "connect[$myGen]: wsUrl=$wsUrl")
        listener.onConnecting()
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (myGen != generation) return
                    Log.d(TAG, "onOpen[$myGen]: connected")
                    listener.onConnected()
                    val authPayload = """
                        {
                          "type":"auth",
                          "token":${json(settings.token)},
                          "client_id":${json(settings.clientId)},
                          "device_name":${json(settings.deviceName)},
                          "subscribe_all":true
                        }
                    """.trimIndent()
                    Log.d(TAG, "onOpen[$myGen]: sending auth")
                    webSocket.send(authPayload)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (myGen != generation) return
                    Log.d(TAG, "onMessage[$myGen]: ${text.take(200)}")
                    listener.onTextMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (myGen != generation) return
                    Log.d(TAG, "onClosed[$myGen]: code=$code reason=$reason")
                    listener.onDisconnected(reason.ifBlank { "Connection closed" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (myGen != generation) return
                    Log.e(TAG, "onFailure[$myGen]: ${t.message}", t)
                    listener.onDisconnected(t.message ?: "Network failure")
                }
            },
        )
    }

    fun send(text: String): Boolean = webSocket?.send(text) == true

    fun disconnect() {
        generation++
        webSocket?.cancel()
        webSocket = null
    }

    private fun toWebSocketUrl(baseUrl: String): String? {
        val raw = baseUrl.trim().trimEnd('/')
        val httpUrl = raw
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .toHttpUrlOrNull() ?: return null
        return httpUrl.newBuilder()
            .addPathSegments("mobile/ws")
            .build()
            .toString()
    }

    private fun json(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    companion object {
        private const val TAG = "RxSciWS"
    }
}
