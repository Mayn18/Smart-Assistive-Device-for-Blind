package com.example.smartaiglasses.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URL

class MJPEGStreamReader {

    fun startStream(
        ip: String,
        onFrame: (Bitmap) -> Unit,
        onCommand: (String) -> Unit
    ) {

        val conn = URL("http://$ip/stream").openConnection()

        val input = BufferedInputStream(conn.getInputStream(), 65536)

        val buffer = ByteArrayOutputStream(65536)

        var prev = 0
        var cur: Int

        val markerBuffer = StringBuilder()

        while (true) {

            cur = input.read()

            if (cur == -1) break

            // Detect text markers
            if (cur in 32..126) {
                markerBuffer.append(cur.toChar())

                if (markerBuffer.length > 32)
                    markerBuffer.deleteCharAt(0)

                val marker = markerBuffer.toString()

                when {
                    marker.contains("BTN:READ") -> {
                        Log.d("ESPBTN", "READ")
                        onCommand("READ")
                        markerBuffer.clear()
                    }

                    marker.contains("BTN:CONT") -> {
                        Log.d("ESPBTN", "CONT")
                        onCommand("CONT")
                        markerBuffer.clear()
                    }

                    marker.contains("BTN:STOP") -> {
                        Log.d("ESPBTN", "STOP")
                        onCommand("STOP")
                        markerBuffer.clear()
                    }
                }
            }

            if (prev == 0xFF && cur == 0xD8) {
                buffer.reset()
                buffer.write(0xFF)
            }

            buffer.write(cur)

            if (prev == 0xFF && cur == 0xD9) {

                val bytes = buffer.toByteArray()

                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.let { onFrame(it) }
            }

            prev = cur
        }
    }
}