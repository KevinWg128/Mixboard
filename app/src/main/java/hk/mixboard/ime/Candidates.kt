package hk.mixboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** [key] is what produced it: the typed code, the English word, or the preceding char (聯想). */
class Candidate(val text: String, val source: Source, val key: String) {
    enum class Source { RAW, QUICK, ENGLISH, ASSOC }
}

/** One-line strip of candidates, with a button that opens the full grid. */
@SuppressLint("ViewConstructor")
class CandidateBar(
    context: Context,
    private val theme: Theme,
    private val onPick: (Candidate) -> Unit,
    private val onToggleExpand: () -> Unit,
) : LinearLayout(context) {

    val barHeight = theme.dp(46f).toInt()
    private val itemPad = theme.dp(13f).toInt()

    private val strip = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val expand = TextView(context).apply {
        text = "▾"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(theme.subText)
        background = pressable()
        setOnClickListener { onToggleExpand() }
        visibility = INVISIBLE
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(theme.board)
        addView(scroll, LayoutParams(0, barHeight, 1f))
        addView(expand, LayoutParams(theme.dp(44f).toInt(), barHeight))
    }

    fun show(list: List<Candidate>, expanded: Boolean) {
        strip.removeAllViews()
        for (c in list.take(STRIP_MAX)) {
            strip.addView(item(c), LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        scroll.scrollTo(0, 0)
        expand.visibility = if (expanded || list.size > 5) VISIBLE else INVISIBLE
        expand.text = if (expanded) "▴" else "▾"
    }

    private fun item(c: Candidate): View = TextView(context).apply {
        text = c.text
        gravity = Gravity.CENTER
        setPadding(itemPad, 0, itemPad, 0)
        when (c.source) {
            Candidate.Source.RAW -> {
                textSize = 16f
                setTextColor(theme.subText)
            }
            Candidate.Source.ENGLISH -> {
                textSize = 20f
                setTextColor(theme.accentText)
            }
            else -> {
                textSize = 20f
                setTextColor(theme.text)
            }
        }
        background = pressable()
        setOnClickListener { onPick(c) }
    }

    private fun pressable() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressed))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private companion object {
        const val STRIP_MAX = 60
    }
}

/** Every candidate in a wrapping grid. Lives inside a ScrollView over the keys. */
@SuppressLint("ViewConstructor")
class CandidateGridView(
    context: Context,
    private val theme: Theme,
    private val onPick: (Candidate) -> Unit,
) : View(context) {

    private var items: List<Candidate> = emptyList()
    private val cells = ArrayList<RectF>()
    private val rowH = theme.dp(50f)
    private val columns = 7
    private var pressed = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = theme.sp(21f)
    }
    private val linePaint = Paint().apply {
        color = theme.divider
        strokeWidth = theme.dp(1f)
    }
    private val pressedPaint = Paint().apply { color = theme.keyPressed }

    fun setItems(list: List<Candidate>) {
        items = list
        pressed = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, layoutCells(w))
    }

    private fun layoutCells(w: Int): Int {
        cells.clear()
        if (w <= 0 || items.isEmpty()) return 0
        val cellW = w / columns.toFloat()
        var x = 0f
        var y = 0f
        for (c in items) {
            val need = textPaint.measureText(c.text) + theme.dp(20f)
            val span = ceil(need / cellW).toInt().coerceIn(1, columns)
            if (x + span * cellW > w + 0.5f) {
                x = 0f
                y += rowH
            }
            cells.add(RectF(x, y, x + span * cellW, y + rowH))
            x += span * cellW
        }
        return (y + rowH).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.board)
        val inset = theme.dp(10f)
        for (i in items.indices) {
            val r = cells.getOrNull(i) ?: break
            if (i == pressed) canvas.drawRect(r, pressedPaint)
            val item = items[i]
            textPaint.color = when (item.source) {
                Candidate.Source.ENGLISH -> theme.accentText
                Candidate.Source.RAW -> theme.subText
                else -> theme.text
            }
            val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(item.text, r.centerX(), baseline, textPaint)
            canvas.drawLine(r.left, r.bottom, r.right, r.bottom, linePaint)
            canvas.drawLine(r.right, r.top + inset, r.right, r.bottom - inset, linePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = indexAt(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x, e.y)
                val wasPressed = pressed
                pressed = -1
                invalidate()
                if (i >= 0 && i == wasPressed) onPick(items[i])
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun indexAt(x: Float, y: Float): Int = cells.indexOfFirst { it.contains(x, y) }
}
