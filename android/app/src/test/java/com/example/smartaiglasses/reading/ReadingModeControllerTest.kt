package com.example.smartaiglasses.reading

import com.example.smartaiglasses.stream.DeviceEventCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingModeControllerTest {
    @Test
    fun eventsTransitionBetweenIdleReadOnceContinuousAndStopped() {
        val controller = ReadingModeController()

        assertEquals(ReadingMode.IDLE, controller.mode)
        controller.accept(DeviceEventCommand.READ)
        assertEquals(ReadingMode.READ_ONCE, controller.mode)
        controller.accept(DeviceEventCommand.CONT)
        assertEquals(ReadingMode.CONTINUOUS, controller.mode)
        controller.accept(DeviceEventCommand.STOP)
        assertEquals(ReadingMode.IDLE, controller.mode)
    }

    @Test
    fun completingAnOlderReadDoesNotCancelANewerMode() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val oldReadRevision = controller.revision

        controller.accept(DeviceEventCommand.CONT)
        controller.finishReadOnce(oldReadRevision)

        assertEquals(ReadingMode.CONTINUOUS, controller.mode)
    }

    @Test
    fun completionReturnsTheCurrentReadOnceModeToIdle() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val revision = controller.revision

        controller.finishReadOnce(revision)

        assertEquals(ReadingMode.IDLE, controller.mode)
    }
}
