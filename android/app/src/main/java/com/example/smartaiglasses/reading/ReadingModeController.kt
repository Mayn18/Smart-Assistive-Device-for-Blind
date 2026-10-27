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

    fun accept(command: DeviceEventCommand) {
        mode = when (command) {
            DeviceEventCommand.READ -> ReadingMode.READ_ONCE
            DeviceEventCommand.CONT -> ReadingMode.CONTINUOUS
            DeviceEventCommand.STOP -> ReadingMode.IDLE
        }
        revision += 1
    }

    fun stop() {
        mode = ReadingMode.IDLE
        revision += 1
    }

    fun finishReadOnce(startedAtRevision: Long) {
        if (mode == ReadingMode.READ_ONCE && revision == startedAtRevision) {
            mode = ReadingMode.IDLE
            revision += 1
        }
    }
}
