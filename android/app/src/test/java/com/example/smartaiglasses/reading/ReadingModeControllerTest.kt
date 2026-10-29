package com.example.smartaiglasses.reading

import com.example.smartaiglasses.stream.DeviceEventCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun aReadKeepsTryingOnLaterFramesWhenTheFirstOnesHaveNoText() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val revision = controller.revision

        repeat(ReadingModeController.MAX_READ_ONCE_ATTEMPTS - 1) {
            assertFalse(controller.finishReadAttempt(revision, foundText = false))
            assertEquals(ReadingMode.READ_ONCE, controller.mode)
            assertEquals("revision must not change between attempts", revision, controller.revision)
        }
    }

    @Test
    fun aReadEndsAsSoonAsTextIsFoundAndDoesNotReportFailure() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val revision = controller.revision
        controller.finishReadAttempt(revision, foundText = false)
        controller.finishReadAttempt(revision, foundText = false)

        val gaveUp = controller.finishReadAttempt(revision, foundText = true)

        assertFalse(gaveUp)
        assertEquals(ReadingMode.IDLE, controller.mode)
    }

    @Test
    fun aReadGivesUpAfterTheMaximumAttemptsAndReportsItExactlyOnce() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val revision = controller.revision

        val results = List(ReadingModeController.MAX_READ_ONCE_ATTEMPTS) {
            controller.finishReadAttempt(revision, foundText = false)
        }

        assertEquals(
            List(ReadingModeController.MAX_READ_ONCE_ATTEMPTS - 1) { false } + true,
            results
        )
        assertEquals(ReadingMode.IDLE, controller.mode)
        // A late report for the finished read is stale and must not report a second failure.
        assertFalse(controller.finishReadAttempt(revision, foundText = false))
    }

    @Test
    fun aNewReadStartsWithAFreshAttemptBudget() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        repeat(ReadingModeController.MAX_READ_ONCE_ATTEMPTS - 1) {
            controller.finishReadAttempt(controller.revision, foundText = false)
        }

        controller.accept(DeviceEventCommand.READ)
        val revision = controller.revision
        repeat(ReadingModeController.MAX_READ_ONCE_ATTEMPTS - 1) {
            assertFalse(controller.finishReadAttempt(revision, foundText = false))
        }

        assertEquals(ReadingMode.READ_ONCE, controller.mode)
    }

    @Test
    fun staleAttemptReportsAreIgnoredAfterTheModeChanges() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val oldRevision = controller.revision
        controller.accept(DeviceEventCommand.CONT)

        val gaveUp = controller.finishReadAttempt(oldRevision, foundText = false)

        assertFalse(gaveUp)
        assertEquals(ReadingMode.CONTINUOUS, controller.mode)
    }

    @Test
    fun aFrameFromAnEarlierReadCannotEndALaterRead() {
        // Press Read, then press it again while the first frame is still being recognised.
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.READ)
        val firstReadRevision = controller.revision
        controller.accept(DeviceEventCommand.READ)

        // The first read's frame finishes with text: it must not end the second read.
        val gaveUp = controller.finishReadAttempt(firstReadRevision, foundText = true)

        assertFalse(gaveUp)
        assertEquals(ReadingMode.READ_ONCE, controller.mode)
    }

    @Test
    fun attemptReportsNeverAffectContinuousOrIdleModes() {
        val controller = ReadingModeController()
        controller.accept(DeviceEventCommand.CONT)
        assertFalse(controller.finishReadAttempt(controller.revision, foundText = false))
        assertEquals(ReadingMode.CONTINUOUS, controller.mode)

        controller.stop()
        assertFalse(controller.finishReadAttempt(controller.revision, foundText = false))
        assertEquals(ReadingMode.IDLE, controller.mode)
    }
}
