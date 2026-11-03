package hk.mixboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onAction(action: Int, longPress: Boolean)
        fun onCursorSwipeStart()
        fun onCursorMove(steps: Int)
    }

    enum class Shift { OFF, ONCE, LOCKED }

    var mode = Layouts.LETTERS
        private set
    private var rows: List<List<Key>> = Layouts.forMode(mode)

    private var shift = Shift.OFF
    private var lastShiftTap = 0L

    var haptics = true
    var enterLabel = "⏎"
        set(value) {
            field = value
            invalidate()
        }

    private var rowHeight = theme.dp(54f)
    private val padTop = theme.dp(5f)
    private val padBottom = theme.dp(7f)
    private val padSide = theme.dp(3f)
    private val gap = theme.dp(3f)
    private val radius = theme.dp(7f)
    private val swipeStart = theme.dp(14f)
    private val swipeStep = theme.dp(11f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = theme.sp(10f)
        color = theme.subText
    }
    private val shadowRect = RectF()

    private val uiHandler = Handler(Looper.getMainLooper())

    // Touch state: one active key at a time; a second finger finishes the first key.
    private var downKey: Key? = null
    private var downPointer = -1
    private var downX = 0f
    private var longFired = false
    private var swiping = false
    private var swipeAnchor = 0f
    private var repeatCount = 0

    private val longPressRunnable = Runnable {
        val k = downKey ?: return@Runnable
        longFired = true
        buzz()
        val alt = k.longText
        if (alt != null) listener.onText(alt) else listener.onAction(k.action, true)
        invalidate()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (downKey?.action != Layouts.DELETE) return
            listener.onAction(Layouts.DELETE, false)
            repeatCount++
            // Speeds up the longer you hold it.
            val delay = when {
                repeatCount < 6 -> 90L
                repeatCount < 20 -> 50L
                else -> 25L
            }
            uiHandler.postDelayed(this, delay)
        }
    }

    fun setMode(m: Int) {
        mode = m
        rows = Layouts.forMode(m)
        layoutKeys(width)
        invalidate()
    }

    fun resetShift() {
        shift = Shift.OFF
        invalidate()
    }

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rowHeight = theme.dp(if (landscape) 40f else 54f)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (padTop + padBottom + rowHeight * 4).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys(w)

    private fun layoutKeys(w: Int) {
        if (w <= 0) return
        val unit = (w - padSide * 2) / 10f
        rows.forEachIndexed { r, row ->
            val total = row.fold(0f) { acc, k -> acc + k.width }
            var x = padSide + (10f - total) * unit / 2f
            val top = padTop + r * rowHeight
            row.forEachIndexed { i, k ->
                val right = x + k.width * unit
                k.rect.set(x + gap, top + gap, right - gap, top + rowHeight - gap)
                k.hit.set(
                    if (i == 0) 0f else x, top,
                    if (i == row.lastIndex) w.toFloat() else right, top + rowHeight,
                )
                x = right
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        val r = ((y - padTop) / rowHeight).toInt().coerceIn(0, rows.lastIndex)
        val row = rows[r]
        return row.firstOrNull { x >= it.hit.left && x < it.hit.right }
            ?: if (x < row.first().hit.left) row.first() else row.last()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.board)
        for (row in rows) for (k in row) drawKey(canvas, k)
    }

    private fun drawKey(c: Canvas, k: Key) {
        val pressed = k === downKey && !swiping
        val accented = k.action == Layouts.ENTER || (k.action == Layouts.SHIFT && shift == Shift.LOCKED)
        fill.color = theme.keyShadow
        shadowRect.set(k.rect)
        shadowRect.offset(0f, theme.dp(1f))
        c.drawRoundRect(shadowRect, radius, radius, fill)
        fill.color = when {
            pressed -> theme.keyPressed
            accented -> theme.accent
            k.isFunction -> theme.fnKey
            else -> theme.key
        }
        c.drawRoundRect(k.rect, radius, radius, fill)

        val text = displayLabel(k)
        labelPaint.color = when {
            accented && !pressed -> theme.onAccent
            k.action == Layouts.SHIFT && shift == Shift.ONCE -> theme.accentText
            k.action == Layouts.SPACE -> theme.subText
            else -> theme.text
        }
        labelPaint.textSize = when {
            k.action == Layouts.SPACE -> theme.sp(13f)
            k.action == Layouts.COMMA || k.action == Layouts.PERIOD -> theme.sp(22f)
            k.text != null && text.length <= 2 -> theme.sp(22f)
            k.action == Layouts.SHIFT || k.action == Layouts.DELETE -> theme.sp(20f)
            else -> theme.sp(15f)
        }
        labelPaint.typeface = if (k.action == Layouts.ENTER && text.length > 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val baseline = k.rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        c.drawText(text, k.rect.centerX(), baseline, labelPaint)
        k.hint?.let { c.drawText(it, k.rect.right - theme.dp(5f), k.rect.top + theme.sp(11f), hintPaint) }
    }

    private fun displayLabel(k: Key): String = when {
        k.action == Layouts.ENTER -> enterLabel
        k.action == Layouts.SHIFT -> if (shift == Shift.LOCKED) "⇪" else "⇧"
        k.text != null && mode == Layouts.LETTERS && shift != Shift.OFF -> k.label.uppercase()
        else -> k.label
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (swiping) return true
                if (downKey != null) release(fire = true)
                press(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(downPointer)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP ->
                if (e.getPointerId(e.actionIndex) == downPointer) release(fire = true)
            MotionEvent.ACTION_UP -> release(fire = true)
            MotionEvent.ACTION_CANCEL -> release(fire = false)
        }
        return true
    }

    private fun press(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val k = keyAt(x, e.getY(index)) ?: return
        downKey = k
        downPointer = e.getPointerId(index)
        downX = x
        longFired = false
        swiping = false
        repeatCount = 0
        buzz()
        if (k.action == Layouts.DELETE) {
            listener.onAction(Layouts.DELETE, false)
            uiHandler.postDelayed(repeatRunnable, 400)
        } else if (k.longText != null || k.action in Layouts.LONG_PRESS_ACTIONS) {
            uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val k = downKey ?: return
        if (k.action == Layouts.SPACE) {
            // Drag along the space bar to move the cursor.
            if (!swiping && abs(x - downX) > swipeStart) {
                swiping = true
                swipeAnchor = x
                listener.onCursorSwipeStart()
                invalidate()
            }
            if (swiping) {
                val steps = ((x - swipeAnchor) / swipeStep).toInt()
                if (steps != 0) {
                    listener.onCursorMove(steps)
                    swipeAnchor += steps * swipeStep
                }
            }
            return
        }
        // Sliding between character keys types the key you let go on.
        if (longFired || k.text == null) return
        val now = keyAt(x, y)
        if (now != null && now !== k && now.text != null) {
            uiHandler.removeCallbacks(longPressRunnable)
            downKey = now
            if (now.longText != null) uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            invalidate()
        }
    }

    private fun release(fire: Boolean) {
        val k = downKey ?: return
        uiHandler.removeCallbacks(longPressRunnable)
        uiHandler.removeCallbacks(repeatRunnable)
        downKey = null
        if (fire && !longFired && !swiping && k.action != Layouts.DELETE) dispatch(k)
        swiping = false
        invalidate()
    }

    private fun dispatch(k: Key) {
        val text = k.text
        if (text != null) {
            listener.onText(if (mode == Layouts.LETTERS && shift != Shift.OFF) text.uppercase() else text)
            if (shift == Shift.ONCE) shift = Shift.OFF
            return
        }
        when (k.action) {
            Layouts.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.ONCE && now - lastShiftTap < 350 -> Shift.LOCKED
                    shift == Shift.OFF -> Shift.ONCE
                    else -> Shift.OFF
                }
                lastShiftTap = now
            }
            Layouts.TO_SYMBOLS -> setMode(Layouts.SYMBOLS)
            Layouts.TO_MORE -> setMode(Layouts.MORE)
            Layouts.TO_LETTERS -> setMode(Layouts.LETTERS)
            else -> listener.onAction(k.action, false)
        }
    }

    private fun buzz() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 380L
    }
}
