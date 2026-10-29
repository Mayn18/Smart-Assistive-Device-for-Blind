package com.example.smartaiglasses.stream

/** A live connection that can be closed. */
fun interface EventSocket {
    fun close()
}

/** Callbacks from one socket. All must be delivered on the same thread that owns the connection. */
interface EventSocketListener {
    fun onOpen()
    fun onMessage(text: String)

    /** The socket closed or failed; either way it is gone and must be replaced. */
    fun onTerminated()
}

/** Opens a socket. May report failure synchronously through [EventSocketListener.onTerminated]. */
fun interface EventSocketFactory {
    fun open(listener: EventSocketListener): EventSocket
}

fun interface Cancellable {
    fun cancel()
}

/** Runs [action] after [delayMs] on the thread that owns the connection. */
fun interface DelayedExecutor {
    fun postDelayed(delayMs: Long, action: Runnable): Cancellable
}

/**
 * Keeps the button-event connection alive: when it drops, it is re-opened with exponential
 * backoff until [stop] is called. Without this, one Wi-Fi blip permanently disabled the
 * camera's physical button while the video stream quietly recovered.
 *
 * Pure Kotlin so the tricky parts (stale callbacks from replaced sockets, stop() racing a
 * pending retry, synchronous failures) are unit-tested on the JVM. Not thread-safe: every
 * method and every listener callback must run on one thread (the app uses the main thread).
 */
class ReconnectingConnection(
    private val factory: EventSocketFactory,
    private val executor: DelayedExecutor,
    private val onCommand: (DeviceEventCommand) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val backoff: ReconnectBackoff = ReconnectBackoff()
) {
    private class Attempt {
        var socket: EventSocket? = null
    }

    private var attempt: Attempt? = null
    private var pendingRetry: Cancellable? = null
    private var running = false
    private var connected = false

    /** Starts (or restarts) the connection. Any previous connection is closed first. */
    fun start() {
        stop()
        running = true
        backoff.reset()
        openAttempt()
    }

    /** Closes the connection and cancels any pending retry. Does not notify [onConnectionChanged]. */
    fun stop() {
        running = false
        pendingRetry?.cancel()
        pendingRetry = null
        val old = attempt
        attempt = null
        connected = false
        old?.socket?.close()
    }

    private fun openAttempt() {
        val current = Attempt()
        attempt = current

        val socket = factory.open(object : EventSocketListener {
            override fun onOpen() {
                if (attempt !== current) return
                backoff.reset()
                setConnected(true)
            }

            override fun onMessage(text: String) {
                if (attempt !== current) return
                DeviceEventCommand.parse(text)?.let(onCommand)
            }

            override fun onTerminated() {
                if (attempt !== current) return
                attempt = null
                current.socket?.close()
                setConnected(false)
                scheduleRetry()
            }
        })

        // The factory may already have reported termination before returning; then this
        // attempt is no longer current and the socket it returned is dead.
        if (attempt === current) current.socket = socket else socket.close()
    }

    private fun scheduleRetry() {
        if (!running) return
        pendingRetry = executor.postDelayed(backoff.nextDelayMs()) {
            pendingRetry = null
            if (running) openAttempt()
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        onConnectionChanged(value)
    }
}
