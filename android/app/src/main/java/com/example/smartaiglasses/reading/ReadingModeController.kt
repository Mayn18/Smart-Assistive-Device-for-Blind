package com.example.smartaiglasses.reading

import com.example.smartaiglasses.stream.DeviceEventCommand

enum class ReadingMode {
    IDLE,
    READ_ONCE,
    CONTINUOUS
}

class ReadingModeController {
    @Volatile
    var mode: ReadingMode = ReadingMode.IDLE
        private set

    @Volatile
    var revision: Long = 0
        private set

    // Only touched from the main thread, like every state change here.
    private var readAttempts = 0

    fun accept(command: DeviceEventCommand) {
        mode = when (command) {
            DeviceEventCommand.READ -> ReadingMode.READ_ONCE
            DeviceEventCommand.CONT -> ReadingMode.CONTINUOUS
            DeviceEventCommand.STOP -> ReadingMode.IDLE
        }
        revision += 1
        readAttempts = 0
    }

    fun stop() {
        mode = ReadingMode.IDLE
        revision += 1
        readAttempts = 0
    }

    fun finishReadOnce(startedAtRevision: Long) {
        if (mode == ReadingMode.READ_ONCE && revision == startedAtRevision) {
            mode = ReadingMode.IDLE
            revision += 1
        }
    }

    /**
     * Reports the outcome of one processed frame during a [ReadingMode.READ_ONCE] read.
     *
     * The first frame after pressing Read is often unusable (the camera is still adjusting
     * exposure, or the page is mid-movement), so a read keeps trying on the following frames
     * until text is found or [MAX_READ_ONCE_ATTEMPTS] frames have come up empty.
     *
     * Returns true only when the read has just ended **without finding any text**, so the
     * caller can tell the user. Returns false when text was found, when it will try again, and
     * when the report is stale (the mode changed since the frame was taken).
     */
    fun finishReadAttempt(startedAtRevision: Long, foundText: Boolean): Boolean {
        if (mode != ReadingMode.READ_ONCE || revision != startedAtRevision) return false

        readAttempts += 1
        if (!foundText && readAttempts < MAX_READ_ONCE_ATTEMPTS) return false

        mode = ReadingMode.IDLE
        revision += 1
        readAttempts = 0
        return !foundText
    }

    companion object {
        const val MAX_READ_ONCE_ATTEMPTS = 6
    }
}
