package com.example.smartaiglasses.stream

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Receives the camera's button events (READ / CONT / STOP) over the WebSocket on port 81 and
 * keeps that link alive: it reconnects with backoff after a drop (see [ReconnectingConnection])
 * and uses WebSocket pings so a half-open connection, e.g. after the router vanished without
 * closing the socket, is noticed within about 20 seconds instead of never.
 *
 * Call every method from the main thread. [onCommand] and [onConnectionChanged] are delivered
 * on the main thread.
 */
class DeviceEventClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connection: ReconnectingConnection? = null

    /**
     * Connects to [ip] and keeps reconnecting until [close]. [onConnectionChanged] is called with
     * true when the link comes up and false when it is lost (not when [close] is called).
     *
     * [onCommand] is last on purpose: Kotlin binds a trailing lambda to the final parameter, so
     * the existing `connect(ip) { command -> ... }` call sites keep working unchanged.
     */
    fun connect(
        ip: String,
        onConnectionChanged: (Boolean) -> Unit = {},
        onCommand: (DeviceEventCommand) -> Unit
    ) {
        close()
        val request = Request.Builder()
            .url("ws://$ip:81/")
            .build()

        connection = ReconnectingConnection(
            factory = { listener -> openSocket(request, listener) },
            executor = { delayMs, action ->
                mainHandler.postDelayed(action, delayMs)
                Cancellable { mainHandler.removeCallbacks(action) }
            },
            onCommand = onCommand,
            onConnectionChanged = onConnectionChanged
        ).also { it.start() }
    }

    fun close() {
        connection?.stop()
        connection = null
    }

    private fun openSocket(request: Request, listener: EventSocketListener): EventSocket {
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            // OkHttp calls back on its own threads; hop to the main thread, which owns the state.
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post { listener.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post { listener.onMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The device started the close handshake. Answering is required or onClosed never
                // fires. Always answer 1000: echoing the peer's code can be illegal to send
                // (e.g. 1005 "no status") and would throw.
                webSocket.close(1000, null)
                mainHandler.post { listener.onTerminated() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post { listener.onTerminated() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post { listener.onTerminated() }
            }
        })
        return EventSocket { socket.close(1000, "Activity stopped") }
    }
}
