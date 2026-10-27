package com.example.smartaiglasses.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MJPEGStreamReader {

    @Volatile private var activeConnection: HttpURLConnection? = null

    fun startStream(
        ip: String,
        onFrame: (Bitmap) -> Unit
    ) {
        val connection = URL("http://$ip/stream").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        activeConnection = connection

        try {
            BufferedInputStream(connection.inputStream, 65536).use { input ->
                val frame = ByteArrayOutputStream(65536)
                var previous = -1
                var capturingFrame = false

                while (!Thread.currentThread().isInterrupted) {
                    val current = input.read()
                    if (current == -1) break

                    if (!capturingFrame) {
                        if (previous == 0xFF && current == 0xD8) {
                            frame.reset()
                            frame.write(0xFF)
                            frame.write(0xD8)
                            capturingFrame = true
                        }
                    } else {
                        frame.write(current)

                        if (frame.size() > MAX_FRAME_BYTES) {
                            frame.reset()
                            capturingFrame = false
                        } else if (previous == 0xFF && current == 0xD9) {
                            val bytes = frame.toByteArray()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?.let(onFrame)
                            frame.reset()
                            capturingFrame = false
                        }
                    }

                    previous = current
                }
            }
        } finally {
            connection.disconnect()
            if (activeConnection === connection) activeConnection = null
        }
    }

    fun stop() {
        activeConnection?.disconnect()
    }

    private companion object {
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}