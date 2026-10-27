package com.example.smartaiglasses.stream

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class DeviceEventClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    fun connect(ip: String, onCommand: (DeviceEventCommand) -> Unit) {
        close()
        val request = Request.Builder()
            .url("ws://$ip:81/")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                DeviceEventCommand.parse(text)?.let(onCommand)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@DeviceEventClient.webSocket === webSocket) {
                    this@DeviceEventClient.webSocket = null
                }
            }
        })
    }

    fun close() {
        webSocket?.close(1000, "Activity stopped")
        webSocket = null
    }
}