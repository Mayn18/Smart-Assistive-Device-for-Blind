package com.example.smartaiglasses.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.*
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

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
            async { runCatching { latinRec.process(image).await() }.getOrNull() },
            async { runCatching { devanagariRec.process(image).await() }.getOrNull() },
            async { runCatching { chineseRec.process(image).await() }.getOrNull() },
            async { runCatching { japaneseRec.process(image).await() }.getOrNull() },
            async { runCatching { koreanRec.process(image).await() }.getOrNull() }
        )

        results.filterNotNull().maxByOrNull { scoreResult(it) }
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