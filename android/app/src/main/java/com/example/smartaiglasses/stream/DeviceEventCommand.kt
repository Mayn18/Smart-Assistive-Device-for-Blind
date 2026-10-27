package com.example.smartaiglasses.stream

enum class DeviceEventCommand {
    READ,
    CONT,
    STOP;

    companion object {
        fun parse(message: String): DeviceEventCommand? =
            values().firstOrNull { it.name == message }
    }
}
