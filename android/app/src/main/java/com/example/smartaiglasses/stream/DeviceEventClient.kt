package com.example.smartaiglasses.stream

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class DeviceEventClient {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect(ip: String, onCommand: (String) -> Unit) {
        close()
        val request = Request.Builder()
            .url("ws://$ip:81/")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "READ" || text == "CONT" || text == "STOP") {
                    onCommand(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@DeviceEventClient.webSocket = null
            }
        })
    }

    fun close() {
        webSocket?.close(1000, "Activity stopped")
        webSocket = null
    }
}