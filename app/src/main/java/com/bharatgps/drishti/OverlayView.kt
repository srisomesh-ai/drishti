package com.bharatgps.drishti

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class Box(val rect: RectF, val label: String, val color: Int) // rect normalised 0..1

/** Draws boxes over the camera, for sighted helpers / demos. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var boxes: List<Box> = emptyList()
    private var imgW = 3; private var imgH = 4

    private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f }
    private val text = Paint().apply { color = Color.BLACK; textSize = 42f; isFakeBoldText = true }
    private val bg = Paint().apply { style = Paint.Style.FILL }

    fun update(list: List<Box>, w: Int, h: Int) {
        boxes = list; imgW = w; imgH = h
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // PreviewView uses FIT_CENTER, so map the image the same way
        val scale = minOf(width.toFloat() / imgW, height.toFloat() / imgH)
        val dw = imgW * scale; val dh = imgH * scale
        val ox = (width - dw) / 2f; val oy = (height - dh) / 2f
        for (b in boxes) {
            val r = RectF(ox + b.rect.left * dw, oy + b.rect.top * dh, ox + b.rect.right * dw, oy + b.rect.bottom * dh)
            stroke.color = b.color
            canvas.drawRect(r, stroke)
            val tw = text.measureText(b.label)
            bg.color = b.color
            canvas.drawRect(r.left, r.top - 52f, r.left + tw + 16f, r.top, bg)
            canvas.drawText(b.label, r.left + 8f, r.top - 12f, text)
        }
    }
}
