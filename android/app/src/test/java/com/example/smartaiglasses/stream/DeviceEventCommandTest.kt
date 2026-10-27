package com.example.smartaiglasses.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceEventCommandTest {
    @Test
    fun parsesSupportedDeviceEvents() {
        assertEquals(DeviceEventCommand.READ, DeviceEventCommand.parse("READ"))
        assertEquals(DeviceEventCommand.CONT, DeviceEventCommand.parse("CONT"))
        assertEquals(DeviceEventCommand.STOP, DeviceEventCommand.parse("STOP"))
    }

    @Test
    fun rejectsUnknownOrMalformedEvents() {
        assertNull(DeviceEventCommand.parse("START"))
        assertNull(DeviceEventCommand.parse(" read "))
        assertNull(DeviceEventCommand.parse(""))
    }
}
