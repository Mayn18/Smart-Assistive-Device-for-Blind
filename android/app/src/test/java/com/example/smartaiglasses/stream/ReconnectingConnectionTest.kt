package com.example.smartaiglasses.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectingConnectionTest {

    private class FakeSocket(val listener: EventSocketListener) : EventSocket {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    /** Manual clock: tasks run only when the test advances time. */
    private class FakeExecutor : DelayedExecutor {
        private class Task(val dueMs: Long, val action: Runnable) {
            var cancelled = false
        }

        private val tasks = mutableListOf<Task>()
        private var nowMs = 0L
        val delays = mutableListOf<Long>()

        override fun postDelayed(delayMs: Long, action: Runnable): Cancellable {
            delays += delayMs
            val task = Task(nowMs + delayMs, action)
            tasks += task
            return Cancellable { task.cancelled = true }
        }

        fun pendingCount() = tasks.count { !it.cancelled }

        fun advance(ms: Long) {
            nowMs += ms
            while (true) {
                val due = tasks.filter { !it.cancelled && it.dueMs <= nowMs }.minByOrNull { it.dueMs } ?: return
                tasks.remove(due)
                due.action.run()
            }
        }
    }

    private class Harness(failSynchronously: Boolean = false) {
        val executor = FakeExecutor()
        val sockets = mutableListOf<FakeSocket>()
        val commands = mutableListOf<DeviceEventCommand>()
        val changes = mutableListOf<Boolean>()
        var failSynchronously = failSynchronously

        val connection = ReconnectingConnection(
            factory = EventSocketFactory { listener ->
                val socket = FakeSocket(listener)
                sockets += socket
                if (this.failSynchronously) listener.onTerminated()
                socket
            },
            executor = executor,
            onCommand = { commands += it },
            onConnectionChanged = { changes += it }
        )

        val latest get() = sockets.last()
    }

    @Test
    fun deliversKnownCommandsAndIgnoresUnknownOnes() {
        val h = Harness()
        h.connection.start()
        h.latest.listener.onOpen()

        h.latest.listener.onMessage("READ")
        h.latest.listener.onMessage("garbage")
        h.latest.listener.onMessage("STOP")

        assertEquals(listOf(DeviceEventCommand.READ, DeviceEventCommand.STOP), h.commands)
    }

    @Test
    fun reportsConnectedThenDisconnectedThenConnectedAgain() {
        val h = Harness()
        h.connection.start()
        h.latest.listener.onOpen()
        h.latest.listener.onTerminated()
        h.executor.advance(1_000)
        h.latest.listener.onOpen()

        assertEquals(listOf(true, false, true), h.changes)
        assertEquals(2, h.sockets.size)
    }

    @Test
    fun retriesWithGrowingDelaysAndResetsAfterASuccessfulConnection() {
        val h = Harness()
        h.connection.start()

        // Three failures in a row: 1 s, 2 s, 4 s.
        h.latest.listener.onTerminated(); h.executor.advance(1_000)
        h.latest.listener.onTerminated(); h.executor.advance(2_000)
        h.latest.listener.onTerminated(); h.executor.advance(4_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), h.executor.delays)

        // It finally connects, then drops again: the delay starts over at 1 s.
        h.latest.listener.onOpen()
        h.latest.listener.onTerminated()
        assertEquals(1_000L, h.executor.delays.last())
    }

    @Test
    fun doesNotReconnectBeforeTheDelayHasElapsed() {
        val h = Harness()
        h.connection.start()
        h.latest.listener.onTerminated()

        h.executor.advance(999)
        assertEquals(1, h.sockets.size)

        h.executor.advance(1)
        assertEquals(2, h.sockets.size)
    }

    @Test
    fun stopClosesTheSocketCancelsRetriesAndStaysSilent() {
        val h = Harness()
        h.connection.start()
        h.latest.listener.onOpen()
        val socket = h.latest

        h.connection.stop()

        assertTrue(socket.closed)
        assertEquals("stop() must not announce a disconnect", listOf(true), h.changes)

        // Late callbacks from the closed socket and the passage of time change nothing.
        socket.listener.onMessage("READ")
        socket.listener.onTerminated()
        h.executor.advance(60_000)
        assertTrue(h.commands.isEmpty())
        assertEquals(1, h.sockets.size)
        assertEquals(0, h.executor.pendingCount())
    }

    @Test
    fun stopWhileARetryIsPendingPreventsTheReconnect() {
        val h = Harness()
        h.connection.start()
        h.latest.listener.onTerminated()
        assertEquals(1, h.executor.pendingCount())

        h.connection.stop()
        h.executor.advance(60_000)

        assertEquals(1, h.sockets.size)
        assertEquals(0, h.executor.pendingCount())
    }

    @Test
    fun restartingReplacesTheOldConnectionAndIgnoresItsLateEvents() {
        val h = Harness()
        h.connection.start()
        val first = h.latest
        first.listener.onOpen()

        h.connection.start()
        val second = h.latest

        assertTrue(first.closed)
        assertFalse(second.closed)

        first.listener.onMessage("CONT")       // stale
        first.listener.onTerminated()          // stale: must not schedule a second connection
        h.executor.advance(60_000)

        assertTrue(h.commands.isEmpty())
        assertEquals(2, h.sockets.size)
        assertEquals(0, h.executor.pendingCount())
    }

    @Test
    fun repeatedTerminationOfTheSameSocketSchedulesOnlyOneRetry() {
        val h = Harness()
        h.connection.start()

        h.latest.listener.onTerminated()
        h.latest.listener.onTerminated()   // e.g. onClosing followed by onClosed

        assertEquals(1, h.executor.pendingCount())
        assertEquals(1, h.executor.delays.size)
    }

    @Test
    fun synchronousFailureInsideOpenBacksOffInsteadOfSpinning() {
        val h = Harness(failSynchronously = true)

        h.connection.start()

        // One attempt made, one retry scheduled, nothing recursed.
        assertEquals(1, h.sockets.size)
        assertEquals(1, h.executor.pendingCount())
        assertTrue("socket returned by a failed open must be closed", h.latest.closed)

        h.executor.advance(1_000)
        assertEquals(2, h.sockets.size)
        assertEquals(listOf(1_000L, 2_000L), h.executor.delays)
    }

    @Test
    fun recoversWhenTheDeviceComesBackAfterManyFailures() {
        val h = Harness()
        h.connection.start()
        repeat(10) {
            h.latest.listener.onTerminated()
            h.executor.advance(8_000)
        }

        h.latest.listener.onOpen()
        h.latest.listener.onMessage("READ")

        assertEquals(listOf(true), h.changes)
        assertEquals(listOf(DeviceEventCommand.READ), h.commands)
        assertTrue("delay must stay capped", h.executor.delays.all { it <= 8_000L })
    }
}
