package hk.mixboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Emoji picker that takes the place of the keys: category tabs on top, a scrolling
 * grid, and ABC / space / ⌫ underneath. The first tab is what you used recently.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val theme: Theme,
    private val listener: Listener,
) : LinearLayout(context) {

    interface Listener {
        fun onEmoji(text: String)
        fun onEmojiBack()
        fun onEmojiSpace()
        fun onEmojiDelete()
    }

    var haptics = true

    /** Most recent first. Shown on the first tab the next time the panel opens. */
    var recent: List<String> = emptyList()

    private val tabs = ArrayList<TextView>()
    private val grid = EmojiGridView(context, theme) { pick(it) }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var deleting = false
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (!deleting) return
            listener.onEmojiDelete()
            uiHandler.postDelayed(this, 60)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.board)

        val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val icons = listOf("🕘") + EmojiData.CATEGORIES.map { it.icon }
        icons.forEachIndexed { i, icon ->
            val t = TextView(context).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
                background = flat()
                setOnClickListener { show(i) }
            }
            tabs.add(t)
            tabRow.addView(t, LayoutParams(0, theme.dp(38f).toInt(), 1f))
        }
        addView(tabRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val pad = theme.dp(3f).toInt()
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, pad, pad, theme.dp(7f).toInt())
        }
        val h = theme.dp(44f).toInt()
        bottom.addView(key("ABC", fn = true) { listener.onEmojiBack() }, slot(h, 1.5f))
        bottom.addView(key("", fn = false) { listener.onEmojiSpace() }, slot(h, 5f))
        bottom.addView(deleteKey(), slot(h, 1.5f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Opens on recents when there are any, otherwise on smileys. */
    fun open() = show(if (recent.isEmpty()) 1 else 0)

    private fun show(i: Int) {
        grid.setItems(if (i == 0) recent else EmojiData.CATEGORIES[i - 1].emoji)
        scroll.scrollTo(0, 0)
        tabs.forEachIndexed { j, t -> t.alpha = if (j == i) 1f else 0.4f }
    }

    private fun pick(text: String) {
        buzz()
        listener.onEmoji(text)
    }

    private fun slot(h: Int, weight: Float) = LayoutParams(0, h, weight).apply {
        val m = theme.dp(3f).toInt()
        setMargins(m, 0, m, 0)
    }

    private fun key(label: String, fn: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(theme.text)
        background = keyBackground(if (fn) theme.fnKey else theme.key)
        setOnClickListener {
            buzz()
            onClick()
        }
    }

    /** Deletes once on touch, then keeps deleting while held. */
    @SuppressLint("ClickableViewAccessibility")
    private fun deleteKey(): TextView {
        val k = key("⌫", fn = true) {}
        k.textSize = 20f
        k.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    buzz()
                    listener.onEmojiDelete()
                    deleting = true
                    uiHandler.postDelayed(repeatDelete, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    deleting = false
                    uiHandler.removeCallbacks(repeatDelete)
                }
            }
            true
        }
        return k
    }

    private fun keyBackground(color: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(theme.keyPressed))
        addState(intArrayOf(), rounded(color))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = theme.dp(7f)
    }

    private fun flat() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressed))
        addState(intArrayOf(), ColorDrawable(0))
    }

    private fun buzz() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDetachedFromWindow() {
        deleting = false
        uiHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}

/** Emoji in equal square cells, as many columns as fit. Lives inside a ScrollView. */
@SuppressLint("ViewConstructor")
class EmojiGridView(
    context: Context,
    private val theme: Theme,
    private val onPick: (String) -> Unit,
) : View(context) {

    private var items: List<String> = emptyList()
    private var columns = 1
    private var cell = theme.dp(46f)
    private var pressed = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = theme.sp(26f)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyPressed }

    fun setItems(list: List<String>) {
        items = list
        pressed = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        columns = (w / theme.dp(46f)).toInt().coerceAtLeast(1)
        cell = w / columns.toFloat()
        val rows = (items.size + columns - 1) / columns
        setMeasuredDimension(w, (rows * cell).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.board)
        if (items.isEmpty()) return
        // Only the rows the ScrollView is showing.
        val clip = canvas.clipBounds
        val first = (clip.top / cell).toInt().coerceAtLeast(0) * columns
        val last = minOf(items.size, ((clip.bottom / cell).toInt() + 1) * columns)
        val centre = -(textPaint.descent() + textPaint.ascent()) / 2f
        val inset = theme.dp(2f)
        val radius = theme.dp(7f)
        for (i in first until last) {
            val x = (i % columns) * cell
            val y = (i / columns) * cell
            if (i == pressed) {
                canvas.drawRoundRect(x + inset, y + inset, x + cell - inset, y + cell - inset, radius, radius, pressedPaint)
            }
            canvas.drawText(items[i], x + cell / 2f, y + cell / 2f + centre, textPaint)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x < 0f || y < 0f || x >= width) return -1
        val i = (y / cell).toInt() * columns + (x / cell).toInt()
        return if (i in items.indices) i else -1
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
                val was = pressed
                pressed = -1
                invalidate()
                if (i >= 0 && i == was) onPick(items[i])
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}
