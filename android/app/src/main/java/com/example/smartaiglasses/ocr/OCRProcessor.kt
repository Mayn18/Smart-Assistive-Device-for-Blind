package com.example.smartaiglasses.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.*
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

private const val TAG = "OCRProcessor"

class OCRProcessor {

    private val latinRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagariRec = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )
    private val chineseRec = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val japaneseRec = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )
    private val koreanRec = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    suspend fun process(bitmap: Bitmap): Text? = coroutineScope {

        val image = InputImage.fromBitmap(bitmap, 0)

        val results = awaitAll(
            async { recognize("Latin", latinRec, image) },
            async { recognize("Devanagari", devanagariRec, image) },
            async { recognize("Chinese", chineseRec, image) },
            async { recognize("Japanese", japaneseRec, image) },
            async { recognize("Korean", koreanRec, image) }
        )

        results.filterNotNull().maxByOrNull { scoreResult(it) }
    }

    private suspend fun recognize(
        script: String,
        recognizer: TextRecognizer,
        image: InputImage
    ): Text? = try {
        recognizer.process(image).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$script text recognition failed", e)
        null
    }

    private fun scoreResult(r: Text): Float {
        var s = 0f

        for (block in r.textBlocks) {
            for (line in block.lines) {
                s += line.text.length * 0.3f
                line.confidence?.let { s += it * 10f }
            }
        }

        return s
    }

    fun close() {
        latinRec.close()
        devanagariRec.close()
        chineseRec.close()
        japaneseRec.close()
        koreanRec.close()
    }
}