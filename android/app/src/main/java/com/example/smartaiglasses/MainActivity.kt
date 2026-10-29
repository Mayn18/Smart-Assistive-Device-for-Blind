package com.example.smartaiglasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartaiglasses.ocr.OCRProcessor
import com.example.smartaiglasses.ocr.TextPolicy
import com.example.smartaiglasses.reading.ReadingMode
import com.example.smartaiglasses.reading.ReadingModeController
import com.example.smartaiglasses.stream.DeviceEventClient
import com.example.smartaiglasses.stream.DeviceEventCommand
import com.example.smartaiglasses.stream.ESP32Discovery
import com.example.smartaiglasses.stream.MJPEGStreamReader
import com.example.smartaiglasses.translate.TranslationManager
import com.example.smartaiglasses.tts.TTSManager
import com.example.smartaiglasses.vision.BoundingBoxDrawer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SmartGlasses"
private const val FRAME_INTERVAL_MS = 500L

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var txtStatus: TextView
    private lateinit var txtLanguage: TextView
    private lateinit var btnRead: Button
    private lateinit var btnContinuous: Button
    private lateinit var btnStop: Button
    private lateinit var checkSimulationMode: CheckBox

    private lateinit var ttsManager: TTSManager
    private val ocrProcessor = OCRProcessor()
    private val textPolicy = TextPolicy()
    private val translationManager = TranslationManager()
    private val streamReader = MJPEGStreamReader()
    private val eventClient = DeviceEventClient()
    private val espDiscovery = ESP32Discovery()
    private val readingMode = ReadingModeController()

    private var streamJob: Job? = null
    private var simulationJob: Job? = null
    private var processingJob: Job? = null
    private var discoveryJob: Job? = null
    private var espIP: String? = null
    private var sampleBitmap: Bitmap? = null
    private var simulationMode = true
    private var lastFrameTime = 0L

    private val processingFrame = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        txtStatus = findViewById(R.id.txtStatus)
        txtLanguage = findViewById(R.id.txtLanguage)
        btnRead = findViewById(R.id.btnRead)
        btnContinuous = findViewById(R.id.btnContinuous)
        btnStop = findViewById(R.id.btnStop)
        checkSimulationMode = findViewById(R.id.checkSimulationMode)

        ttsManager = TTSManager(this)
        simulationMode = checkSimulationMode.isChecked

        checkSimulationMode.setOnCheckedChangeListener { _, checked ->
            simulationMode = checked
            espIP = null
            startConfiguredSource()
        }
        btnRead.setOnClickListener { handleDeviceCommand(DeviceEventCommand.READ) }
        btnContinuous.setOnClickListener { handleDeviceCommand(DeviceEventCommand.CONT) }
        btnStop.setOnClickListener { handleDeviceCommand(DeviceEventCommand.STOP) }
    }

    override fun onStart() {
        super.onStart()
        startConfiguredSource()
    }

    override fun onStop() {
        discoveryJob?.cancel()
        discoveryJob = null
        readingMode.stop()
        cancelCurrentProcessing()
        stopSources()
        ttsManager.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cancelCurrentProcessing()
        discoveryJob?.cancel()
        stopSources()
        ttsManager.shutdown()
        ocrProcessor.close()
        translationManager.close()
        super.onDestroy()
    }

    private fun startConfiguredSource() {
        discoveryJob?.cancel()
        discoveryJob = null
        stopSources()
        readingMode.stop()
        cancelCurrentProcessing()
        ttsManager.stop()
        lastFrameTime = 0L

        if (simulationMode) {
            espIP = null
            startSimulation()
        } else if (espIP != null) {
            txtLanguage.text = ""
            txtStatus.text = getString(R.string.device_connected, espIP)
            setReadingControlsEnabled(true)
            startStream()
        } else {
            imageView.setImageDrawable(null)
            txtLanguage.text = ""
            setReadingControlsEnabled(false)
            discoveryJob = lifecycleScope.launch { discoverESP32() }
        }
    }

    private fun stopSources() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamJob?.cancel()
        streamJob = null
        simulationJob?.cancel()
        simulationJob = null
        streamReader.stop()
        eventClient.close()
    }

    private fun startSimulation() {
        val sample = BitmapFactory.decodeResource(resources, R.drawable.sample_text)
        if (sample == null) {
            txtLanguage.text = getString(R.string.simulation_badge)
            txtStatus.text = getString(R.string.sample_unavailable)
            setReadingControlsEnabled(false)
            return
        }

        sampleBitmap = sample
        imageView.setImageBitmap(sample)
        txtLanguage.text = getString(R.string.simulation_badge)
        txtStatus.text = getString(R.string.simulation_ready)
        setReadingControlsEnabled(true)
        simulationJob = lifecycleScope.launch {
            while (isActive) {
                if (readingMode.mode != ReadingMode.IDLE) {
                    processFrame(sample)
                }
                delay(1000L)
            }
        }
    }

    private fun setReadingControlsEnabled(enabled: Boolean) {
        btnRead.isEnabled = enabled
        btnContinuous.isEnabled = enabled
        btnStop.isEnabled = true
    }

    private suspend fun discoverESP32() {
        txtStatus.text = getString(R.string.searching_device)
        val candidates = linkedSetOf(
            espDiscovery.discover(),
            "esp32cam.local",
            "192.168.4.1"
        ).filterNotNull()

        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (simulationMode) return
            txtStatus.text = getString(R.string.checking_device, candidate)
            if (canConnect(candidate)) {
                espIP = candidate
                txtStatus.text = getString(R.string.device_connected, candidate)
                setReadingControlsEnabled(true)
                startStream()
                return
            }
        }

        espIP = null
        txtStatus.text = getString(R.string.device_unavailable)
        setReadingControlsEnabled(false)
    }

    private suspend fun canConnect(host: String): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("http://$host/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.contentType?.startsWith("application/json") != true
            ) {
                return@withContext false
            }

            val bytes = ByteArray(512)
            var length = 0
            connection.inputStream.use { input ->
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
            }
            String(bytes, 0, length, StandardCharsets.UTF_8)
                .contains("\"name\":\"smart-assistive-reader\"")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.d(TAG, "ESP32 health check failed for $host", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Invalid ESP32 address returned during discovery", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun startStream() {
        val deviceIp = espIP ?: return
        if (streamJob?.isActive == true) return

        // While connected to the glasses the phone is usually set down or pocketed. When the screen
        // timed out, onStop() stopped the stream and reading with no sound, so the person was left
        // waiting on a silent app. Keep the screen awake for as long as the device is in use.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        eventClient.connect(deviceIp, onConnectionChanged = ::onButtonLinkChanged) { command ->
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
                    Log.e(TAG, "Stream error", e)
                    if (isActive) {
                        runOnUiThread { txtStatus.text = getString(R.string.stream_retrying) }
                    }
                }

                if (isActive) delay(2000L)
            }
        }
    }

    /** The camera's physical button link came up or dropped. Said aloud, not just shown. */
    private fun onButtonLinkChanged(connected: Boolean) {
        val message = getString(
            if (connected) R.string.button_link_connected else R.string.button_link_lost
        )
        txtStatus.text = message
        ttsManager.announce(message)
    }

    private fun handleDeviceCommand(command: DeviceEventCommand) {
        readingMode.accept(command)
        when (command) {
            DeviceEventCommand.READ -> {
                textPolicy.reset()
                txtStatus.text = getString(
                    if (simulationMode) R.string.simulated_read else R.string.device_read
                )
                if (simulationMode) sampleBitmap?.let(::processFrame)
            }
            DeviceEventCommand.CONT -> {
                textPolicy.reset()
                txtStatus.text = getString(
                    if (simulationMode) R.string.simulated_continuous else R.string.device_continuous
                )
            }
            DeviceEventCommand.STOP -> {
                cancelCurrentProcessing()
                ttsManager.stop()
                txtStatus.text = getString(
                    if (simulationMode) R.string.simulated_stopped else R.string.device_stopped
                )
            }
        }
    }

    private fun cancelCurrentProcessing() {
        processingJob?.cancel()
        processingJob = null
    }

    private fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < FRAME_INTERVAL_MS) return
        lastFrameTime = now

        if (readingMode.mode == ReadingMode.IDLE ||
            !processingFrame.compareAndSet(false, true)
        ) return

        val startedAtRevision = readingMode.revision
        processingJob = lifecycleScope.launch {
            var foundText = false
            try {
                if (readingMode.revision == startedAtRevision &&
                    readingMode.mode != ReadingMode.IDLE
                ) {
                    foundText = runOCR(bitmap, startedAtRevision)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OCR processing failed", e)
                if (readingMode.revision == startedAtRevision) {
                    txtStatus.text = getString(
                        R.string.processing_failed,
                        e.localizedMessage ?: e.javaClass.simpleName
                    )
                }
            } finally {
                // A Read keeps trying on the next frames until text is found; only after the last
                // empty attempt does it end, and then the person is told (they may never look at
                // the screen, so a silent end looked exactly like a broken app).
                val gaveUp = readingMode.finishReadAttempt(startedAtRevision, foundText)
                if (gaveUp) {
                    txtStatus.text = getString(R.string.no_text_detected)
                    ttsManager.announce(getString(R.string.read_no_text_spoken))
                }
                processingJob = null
                processingFrame.set(false)
            }
        }
    }

    /**
     * Recognises text in [bitmap] and speaks it when the policy allows.
     * Returns true when readable text was found (whether or not it was spoken), false when this
     * frame had none. Stale work (the mode changed meanwhile) reports true so nothing retries it.
     */
    private suspend fun runOCR(bitmap: Bitmap, startedAtRevision: Long): Boolean {
        val result = ocrProcessor.process(bitmap)
        if (readingMode.revision != startedAtRevision ||
            readingMode.mode == ReadingMode.IDLE
        ) return true
        if (result == null) {
            txtStatus.text = getString(R.string.no_text_detected)
            return false
        }

        val cleaned = textPolicy.clean(result.text)
        if (cleaned.length < 3) return false

        txtStatus.text = getString(R.string.found_text, cleaned.take(60))

        val now = System.currentTimeMillis()
        if (!textPolicy.shouldSpeak(
                cleaned,
                now,
                readingMode.mode == ReadingMode.READ_ONCE,
                ttsManager.isBusy()
            )) return true

        var translationFailed = false
        val finalText = try {
            translationManager.translateToEnglish(cleaned)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed; using recognized text", e)
            translationFailed = true
            cleaned
        }

        currentCoroutineContext().ensureActive()
        if (readingMode.revision != startedAtRevision ||
            readingMode.mode == ReadingMode.IDLE
        ) return true

        textPolicy.markSpoken(cleaned, now)
        txtStatus.text = getString(
            if (translationFailed) R.string.speaking_recognized_text else R.string.speaking_text,
            finalText.take(60)
        )
        ttsManager.speak(finalText)
        imageView.setImageBitmap(BoundingBoxDrawer.draw(bitmap, result))
        return true
    }
}
