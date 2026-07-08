package com.example.smartaiglasses.vision

import android.graphics.*
import com.google.mlkit.vision.text.Text

object BoundingBoxDrawer {

    fun draw(bitmap: Bitmap, result: Text): Bitmap {

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(out)

        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.GREEN
            strokeWidth = 3f
        }

        for (block in result.textBlocks) {

            block.boundingBox?.let { box ->
                canvas.drawRect(box, stroke)
            }
        }

        return out
    }
}