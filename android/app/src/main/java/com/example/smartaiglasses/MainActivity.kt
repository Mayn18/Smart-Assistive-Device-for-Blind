package com.example.smartaiglasses

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartaiglasses.ocr.OCRProcessor
import com.example.smartaiglasses.ocr.TextPolicy
import com.example.smartaiglasses.stream.ESP32Discovery
import com.example.smartaiglasses.stream.MJPEGStreamReader
import com.example.smartaiglasses.translate.TranslationManager
import com.example.smartaiglasses.tts.TTSManager
import com.example.smartaiglasses.vision.BoundingBoxDrawer
import kotlinx.coroutines.*
import java.net.URL

private const val TAG = "SmartGlasses"

private const val FRAME_INTERVAL_MS = 500L
private enum class ReadingMode {
    IDLE,
    READ_ONCE,
    CONTINUOUS
}

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var imageView: ImageView
    private lateinit var txtStatus: TextView
    private lateinit var txtLanguage: TextView
    private lateinit var btnRead: Button
    private lateinit var btnContinuous: Button
    private lateinit var btnStop: Button

    // Modules
    private lateinit var ttsManager: TTSManager
    private val ocrProcessor = OCRProcessor()
    private val textPolicy = TextPolicy()
    private val translationManager = TranslationManager()
    private val streamReader = MJPEGStreamReader()
    private val eventClient = DeviceEventClient()

    // Stream
    private var streamJob: Job? = null
    private var discoveryJob: Job? = null
    private var espIP: String? = null

    // State
    private var lastFrameTime = 0L

    private val espDiscovery = ESP32Discovery()

    @Volatile private var readingMode = ReadingMode.IDLE
    @Volatile private var processingFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        txtStatus = findViewById(R.id.txtStatus)
        txtLanguage = findViewById(R.id.txtLanguage)

        btnRead = findViewById(R.id.btnRead)
        btnContinuous = findViewById(R.id.btnContinuous)
        btnStop = findViewById(R.id.btnStop)

        ttsManager = TTSManager(this)

        btnRead.setOnClickListener {

            readingMode = ReadingMode.READ_ONCE
            textPolicy.reset()

            txtStatus.text = "Point at text and wait…"
        }

        btnContinuous.setOnClickListener {

            readingMode = ReadingMode.CONTINUOUS
            textPolicy.reset()

            txtStatus.text = "Continuous mode ON"
        }

        btnStop.setOnClickListener {

            readingMode = ReadingMode.IDLE
            ttsManager.stop()

            txtStatus.text = "Stopped"
        }

        discoveryJob = lifecycleScope.launch {
            discoverESP32()
        }
    }

    override fun onDestroy() {

        super.onDestroy()

        streamJob?.cancel()
        streamReader.stop()
        eventClient.close()

        ttsManager.shutdown()
        ocrProcessor.close()
        translationManager.close()
    }

    override fun onStart() {
        super.onStart()
        if (espIP != null && streamJob == null) {
            startStream()
        } else if (espIP == null && discoveryJob?.isActive != true) {
            discoveryJob = lifecycleScope.launch { discoverESP32() }
        }
    }

    override fun onStop() {
        discoveryJob?.cancel()
        discoveryJob = null
        readingMode = ReadingMode.IDLE
        streamJob?.cancel()
        streamJob = null
        streamReader.stop()
        eventClient.close()
        ttsManager.stop()
        super.onStop()
    }

    // ESP32 DISCOVERY

    private suspend fun discoverESP32() {

        txtStatus.text = "Searching for ESP32…"

        espIP = espDiscovery.discover()

        if (espIP == null) {
            espIP = "esp32cam.local"

            // Try connecting
            if (!canConnect(espIP!!)) {

                // FINAL fallback → ESP AP mode
                espIP = "192.168.4.1"
            }
        }

        Log.d(TAG, "ESP32 IP = $espIP")

        txtStatus.text = "Connected: $espIP — press Read or Continuous"

        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            startStream()
        }
    }

    private suspend fun canConnect(ip: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("http://$ip/stream")
            val conn = url.openConnection()
            conn.connectTimeout = 1000
            conn.getInputStream().close()
            true
        } catch (e: Exception) {
            false
        }
    }

    // STREAM

    private fun startStream() {
        val deviceIp = espIP ?: return

        eventClient.connect(deviceIp) { command ->
            runOnUiThread { handleDeviceCommand(command) }
        }

        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    streamReader.startStream(deviceIp) { bitmap ->
                        runOnUiThread { imageView.setImageBitmap(bitmap) }
                        processFrame(bitmap)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error ${e.message}")
                }

                if (isActive) delay(2000)
            }
        }
    }

    private fun handleDeviceCommand(command: String) {
        when (command) {
            "READ" -> {
                readingMode = ReadingMode.READ_ONCE
                txtStatus.text = "ESP Button: Read"
            }
            "CONT" -> {
                readingMode = ReadingMode.CONTINUOUS
                txtStatus.text = "ESP Button: Continuous"
            }
            "STOP" -> {
                readingMode = ReadingMode.IDLE
                ttsManager.stop()
                txtStatus.text = "ESP Button: Stop"
            }
        }
    }


    // FRAME GATE

    private fun processFrame(bitmap: Bitmap) {

        val now = System.currentTimeMillis()

        if (now - lastFrameTime < FRAME_INTERVAL_MS) return
        lastFrameTime = now

        if (readingMode == ReadingMode.IDLE) return
        if (processingFrame) return

        processingFrame = true

        lifecycleScope.launch {

            try {

                runOCR(bitmap)

            } catch (e: Exception) {

                Log.e(TAG, "OCR error ${e.message}")

            } finally {

                processingFrame = false

                if (readingMode == ReadingMode.READ_ONCE) readingMode = ReadingMode.IDLE
            }
        }
    }

    // OCR PIPELINE

    private suspend fun runOCR(bitmap: Bitmap) {

        val result = ocrProcessor.process(bitmap) ?: return
        val cleaned = textPolicy.clean(result.text)
        if (cleaned.length < 3) return

        runOnUiThread { txtStatus.text = "Found: ${cleaned.take(60)}" }

        val now = System.currentTimeMillis()
        if (!textPolicy.shouldSpeak(
                cleaned,
                now,
                readingMode == ReadingMode.READ_ONCE,
                ttsManager.isBusy()
            )) return

        val finalText = translationManager.translateToEnglish(cleaned)

        textPolicy.markSpoken(cleaned, now)

        runOnUiThread {
            txtStatus.text = "▶ ${finalText.take(60)}"
            ttsManager.speak(finalText)
            val boxed = BoundingBoxDrawer.draw(bitmap, result)
            imageView.setImageBitmap(boxed)
        }
    }
}