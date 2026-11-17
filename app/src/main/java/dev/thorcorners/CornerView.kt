package dev.thorcorners

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View

enum class Corner(val gravity: Int) {
    TOP_LEFT(Gravity.TOP or Gravity.LEFT),
    TOP_RIGHT(Gravity.TOP or Gravity.RIGHT),
    BOTTOM_LEFT(Gravity.BOTTOM or Gravity.LEFT),
    BOTTOM_RIGHT(Gravity.BOTTOM or Gravity.RIGHT),
}

/**
 * One corner, drawn in a small square window exactly radius x radius in size.
 * The shape is the square minus a circle of the same radius centred on the inner corner,
 * which leaves the black "fillet" that makes the screen look rounded.
 */
@SuppressLint("ViewConstructor")
class CornerView(context: Context, private val corner: Corner) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private var path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild(w.toFloat())
    }

    private fun rebuild(r: Float) {
        path = Path()
        if (r <= 0f) return
        // Centre of the circle we cut out: the corner of this window that faces the screen centre.
        val (cx, cy) = when (corner) {
            Corner.TOP_LEFT -> r to r
            Corner.TOP_RIGHT -> 0f to r
            Corner.BOTTOM_LEFT -> r to 0f
            Corner.BOTTOM_RIGHT -> 0f to 0f
        }
        val square = Path().apply { addRect(0f, 0f, r, r, Path.Direction.CW) }
        val circle = Path().apply { addOval(RectF(cx - r, cy - r, cx + r, cy + r), Path.Direction.CW) }
        path.op(square, circle, Path.Op.DIFFERENCE)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }
}
