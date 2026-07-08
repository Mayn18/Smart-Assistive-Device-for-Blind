package com.example.smartaiglasses.stream

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val TAG = "ESP32Discovery"

class ESP32Discovery {

    suspend fun discover(): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->

                socket.broadcast = true

                val message = "WHO_IS_ESP32_CAM".toByteArray()

                val packet = DatagramPacket(
                    message,
                    message.size,
                    InetAddress.getByName("255.255.255.255"),
                    4210
                )

                socket.send(packet)

                val buffer = ByteArray(256)
                val response = DatagramPacket(buffer, buffer.size)

                socket.soTimeout = 3000

                socket.receive(response)

                val reply = String(response.data, 0, response.length)

                if (reply.startsWith("ESP32_CAM:")) {
                    val ip = reply.removePrefix("ESP32_CAM:").trim()
                    Log.d(TAG, "ESP32 Found: $ip")
                    return@withContext ip
                }

            }

        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed: ${e.message}")
        }

        return@withContext null
    }
}