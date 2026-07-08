package com.example.smartaiglasses

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartaiglasses.ocr.OCRProcessor
import com.example.smartaiglasses.stream.ESP32Discovery
import com.example.smartaiglasses.stream.MJPEGStreamReader
import com.example.smartaiglasses.translate.TranslationManager
import com.example.smartaiglasses.tts.TTSManager
import com.example.smartaiglasses.vision.BoundingBoxDrawer
import kotlinx.coroutines.*
import java.net.URL

private const val TAG = "SmartGlasses"

private const val FRAME_INTERVAL_MS = 500L
private const val SPEECH_COOLDOWN_MS = 3000L
private const val MIN_TEXT_LENGTH = 3

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
    private val translationManager = TranslationManager()
    private val streamReader = MJPEGStreamReader()

    // Stream
    private var streamJob: Job? = null
    private var espIP: String? = null

    // State
    private var lastFrameTime = 0L
    private var lastSpeechTime = 0L
    private var lastSpokenText = ""

    private val espDiscovery = ESP32Discovery()

    @Volatile private var continuousMode = false
    @Volatile private var readOnceMode = false
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

            readOnceMode = true
            continuousMode = false

            lastSpokenText = ""
            lastSpeechTime = 0

            txtStatus.text = "Point at text and wait…"
        }

        btnContinuous.setOnClickListener {

            continuousMode = true
            readOnceMode = false

            lastSpokenText = ""
            lastSpeechTime = 0

            txtStatus.text = "Continuous mode ON"
        }

        btnStop.setOnClickListener {

            readOnceMode = false
            continuousMode = false

            txtStatus.text = "Stopped"
        }

        lifecycleScope.launch {
            discoverESP32()
        }
    }

    override fun onDestroy() {

        super.onDestroy()

        streamJob?.cancel()

        ttsManager.shutdown()
        ocrProcessor.close()
        translationManager.close()
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
                espIP = "10.60.140.253"
            }
        }

        Log.d(TAG, "ESP32 IP = $espIP")

        txtStatus.text = "Connected: $espIP — press Read or Continuous"

        startStream()
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

        streamJob = lifecycleScope.launch(Dispatchers.IO) {

            try {

                streamReader.startStream(
                    espIP!!,

                    onFrame = { bitmap ->

                        runOnUiThread {
                            imageView.setImageBitmap(bitmap)
                        }

                        processFrame(bitmap)
                    },

                    onCommand = { cmd ->

                        runOnUiThread {

                            when (cmd) {

                                "READ" -> {

                                    readOnceMode = true
                                    continuousMode = false
                                    txtStatus.text = "ESP Button: Read"
                                }

                                "CONT" -> {

                                    continuousMode = true
                                    readOnceMode = false
                                    txtStatus.text = "ESP Button: Continuous"
                                }

                                "STOP" -> {

                                    continuousMode = false
                                    readOnceMode = false
                                    txtStatus.text = "ESP Button: Stop"
                                }
                            }
                        }
                    }
                )

            } catch (e: Exception) {

                Log.e(TAG, "Stream error ${e.message}")

                delay(2000)

                startStream()
            }
        }
    }


    // FRAME GATE

    private fun processFrame(bitmap: Bitmap) {

        val now = System.currentTimeMillis()

        if (now - lastFrameTime < FRAME_INTERVAL_MS) return
        lastFrameTime = now

        if (!continuousMode && !readOnceMode) return
        if (processingFrame) return

        processingFrame = true

        lifecycleScope.launch {

            try {

                runOCR(bitmap)

            } catch (e: Exception) {

                Log.e(TAG, "OCR error ${e.message}")

            } finally {

                processingFrame = false

                if (readOnceMode) readOnceMode = false
            }
        }
    }

    // OCR PIPELINE

    private suspend fun runOCR(bitmap: Bitmap) {

        val result = ocrProcessor.process(bitmap) ?: return
        val cleaned = ocrProcessor.clean(result.text)
        if (cleaned.length < MIN_TEXT_LENGTH) return

        runOnUiThread { txtStatus.text = "Found: ${cleaned.take(60)}" }

        val now = System.currentTimeMillis()
        val same = cleaned == lastSpokenText
        val cooldown = now - lastSpeechTime < SPEECH_COOLDOWN_MS

        val shouldSpeak = when {
            readOnceMode -> true
            ttsManager.isBusy() -> false
            same && cooldown -> false
            else -> true
        }

        if (!shouldSpeak) return

        val finalText = translationManager.translateToEnglish(cleaned)

        lastSpokenText = cleaned
        lastSpeechTime = now

        runOnUiThread {
            txtStatus.text = "▶ ${finalText.take(60)}"
            ttsManager.speak(finalText)
            val boxed = BoundingBoxDrawer.draw(bitmap, result)
            imageView.setImageBitmap(boxed)
        }
    }
}