package com.example.smartaiglasses.stream

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "ESP32Discovery"

class ESP32Discovery {

    suspend fun discover(): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250

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
                val deadline = System.nanoTime() + 3_000_000_000L
                while (System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    try {
                        response.setLength(buffer.size)
                        socket.receive(response)
                        val reply = String(response.data, 0, response.length)
                        if (!reply.startsWith("ESP32_CAM:")) continue

                        val ip = reply.removePrefix("ESP32_CAM:").trim()
                        if (!ip.matches(Regex("""(?:\d{1,3}\.){3}\d{1,3}""")) ||
                            ip.split('.').any { octet ->
                                octet.toIntOrNull()?.let { it !in 0..255 } != false
                            }
                        ) {
                            Log.w(TAG, "Ignoring invalid discovery response")
                            continue
                        }
                        Log.d(TAG, "ESP32 Found: $ip")
                        return@withContext ip
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                }
                Log.d(TAG, "Discovery timed out")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                Log.e(TAG, "Discovery failed: ${e.message}")
            }
        }
        null
    }
}