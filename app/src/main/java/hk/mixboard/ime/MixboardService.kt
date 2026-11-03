package hk.mixboard.ime

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import hk.mixboard.ime.Candidate.Source
import java.io.File
import kotlin.math.abs

/**
 * Kaiboard-style mixed keyboard.
 *
 * Letters go straight into the text box (as composing text) so English needs no
 * extra step. At the same time the bar shows 速成 characters for the same letters,
 * plus Chinese for English words (toy → 玩具). Tap one to swap the letters for it;
 * press space to keep the English.
 */
class MixboardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var dict: Dictionary
    private lateinit var user: UserDict
    private val main = Handler(Looper.getMainLooper())

    private var keyboard: KeyboardView? = null
    private var bar: CandidateBar? = null
    private var grid: CandidateGridView? = null
    private var gridScroll: ScrollView? = null

    private val buffer = StringBuilder()          // letters typed since the last commit
    private var candidates: List<Candidate> = emptyList()
    private var expanded = false
    private var lastChinese: String? = null       // last Chinese char committed, for learning 聯想
    private var pendingSpace = false              // we just committed "word " via space
    private var englishOnly = false               // password / email fields
    private var noLearning = false                // field asked for incognito
    private var lastComposeAt = 0L

    private var useAssoc = true
    private var useEnglish = true
    private var smartPunct = true
    private var haptics = true

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        user = UserDict(File(filesDir, "user_dict.tsv"))
        user.load()
        readPrefs()
        dict = Dictionary(assets)
        dict.loadAsync { main.post { if (buffer.isNotEmpty()) refreshCandidates() } }
    }

    override fun onDestroy() {
        user.save()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Never take over the whole screen in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val theme = Theme.from(this)
        val bar = CandidateBar(this, theme, ::pick, ::toggleExpand)
        val kb = KeyboardView(this, theme, this)
        val grid = CandidateGridView(this, theme, ::pick)
        val gridScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(theme.board)
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        val frame = FrameLayout(this).apply {
            addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(gridScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        this.bar = bar
        this.keyboard = kb
        this.grid = grid
        this.gridScroll = gridScroll
        expanded = false
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.board)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar.barHeight))
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        readPrefs()
        buffer.setLength(0)
        lastChinese = null
        pendingSpace = false

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        englishOnly = password || (cls == InputType.TYPE_CLASS_TEXT && variation in EMAIL_VARIATIONS)
        noLearning = password || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        keyboard?.let {
            it.haptics = haptics
            it.setMode(if (numeric) Layouts.SYMBOLS else Layouts.LETTERS)
            it.resetShift()
            it.enterLabel = enterLabelFor(info)
        }
        showCandidates(emptyList())
    }

    override fun onFinishInput() {
        super.onFinishInput()
        buffer.setLength(0)
        lastChinese = null
        pendingSpace = false
        showCandidates(emptyList())
        user.save()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (buffer.isEmpty()) return
        // You tapped somewhere else in the text: keep the letters as English.
        // (A report with no composing region that arrives right after typing is just late, so ignore it.)
        val movedAway = if (candidatesEnd == -1) {
            SystemClock.uptimeMillis() - lastComposeAt > STALE_MS
        } else {
            newSelStart != candidatesEnd || newSelEnd != candidatesEnd
        }
        if (movedAway) {
            buffer.setLength(0)
            currentInputConnection?.finishComposingText()
            lastChinese = null
            showCandidates(emptyList())
        }
    }

    // ---------------------------------------------------------------- keys

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        pendingSpace = false
        if (!englishOnly && text.length == 1 && text[0].isAsciiLetter()) {
            buffer.append(text)
            compose(ic)
            refreshCandidates()
            return
        }
        commitBuffer(ic)
        ic.commitText(text, 1)
        lastChinese = null
        showCandidates(emptyList())
    }

    override fun onAction(action: Int, longPress: Boolean) {
        val ic = currentInputConnection ?: return
        val spaced = pendingSpace
        pendingSpace = false
        when (action) {
            Layouts.DELETE -> delete(ic)
            Layouts.SPACE -> space(ic)
            Layouts.ENTER -> enter(ic)
            Layouts.COMMA -> punct(ic, "，", ",", longPress, spaced)
            Layouts.PERIOD -> punct(ic, "。", ".", longPress, spaced)
            Layouts.SWITCH -> switchKeyboard(longPress)
        }
    }

    override fun onCursorSwipeStart() {
        val ic = currentInputConnection ?: return
        if (buffer.isNotEmpty()) {
            ic.finishComposingText()
            buffer.setLength(0)
        }
        pendingSpace = false
        lastChinese = null
        showCandidates(emptyList())
    }

    override fun onCursorMove(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    private fun delete(ic: InputConnection) {
        if (buffer.isNotEmpty()) {
            buffer.setLength(buffer.length - 1)
            if (buffer.isEmpty()) ic.commitText("", 1) else compose(ic)
            refreshCandidates()
            return
        }
        lastChinese = null
        if (candidates.isNotEmpty()) showCandidates(emptyList())
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun space(ic: InputConnection) {
        if (buffer.isNotEmpty()) {
            ic.commitText(buffer.toString() + " ", 1)
            buffer.setLength(0)
            pendingSpace = true
        } else {
            ic.commitText(" ", 1)
        }
        lastChinese = null
        showCandidates(emptyList())
    }

    private fun enter(ic: InputConnection) {
        commitBuffer(ic)
        lastChinese = null
        showCandidates(emptyList())
        val info = currentInputEditorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendKeyChar('\n')
        } else {
            ic.performEditorAction(action)
        }
    }

    /**
     * Smart punctuation: full-width (，。) when the line has Chinese in it,
     * half-width (, .) on an all-English line. Long-press gives the other one.
     */
    private fun punct(ic: InputConnection, full: String, half: String, longPress: Boolean, spaced: Boolean) {
        commitBuffer(ic)
        if (spaced) ic.deleteSurroundingText(1, 0) // "busy ," -> "busy,"
        var useHalf = smartPunct && lineIsEnglish(ic)
        if (longPress) useHalf = !useHalf
        ic.commitText(if (useHalf) half else full, 1)
        lastChinese = null
        showCandidates(emptyList())
    }

    private fun lineIsEnglish(ic: InputConnection): Boolean {
        val line = ic.getTextBeforeCursor(60, 0)?.toString()?.substringAfterLast('\n') ?: return false
        if (line.isBlank()) return false
        return line.none { it.isCjkOrWide() }
    }

    private fun switchKeyboard(longPress: Boolean) {
        if (longPress || !switchToNextInputMethod(false)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private fun compose(ic: InputConnection) {
        ic.setComposingText(buffer.toString(), 1)
        lastComposeAt = SystemClock.uptimeMillis()
    }

    private fun commitBuffer(ic: InputConnection) {
        if (buffer.isEmpty()) return
        ic.commitText(buffer.toString(), 1)
        buffer.setLength(0)
    }

    // ---------------------------------------------------------------- candidates

    private fun refreshCandidates() {
        if (buffer.isEmpty()) {
            showCandidates(emptyList())
            return
        }
        val raw = buffer.toString()
        val code = raw.lowercase()
        val list = ArrayList<Candidate>()
        list.add(Candidate(raw, Source.RAW, raw))
        if (code.length <= 2) {
            user.rank("q:$code", dict.quick(code)).mapTo(list) { Candidate(it, Source.QUICK, code) }
        }
        if (useEnglish && code.length >= 2) {
            user.rank("e:$code", dict.english(code)).mapTo(list) { Candidate(it, Source.ENGLISH, code) }
        }
        showCandidates(list)
    }

    private fun pick(c: Candidate) {
        val ic = currentInputConnection ?: return
        pendingSpace = false
        if (c.source == Source.RAW) {
            commitBuffer(ic)
            lastChinese = null
            showCandidates(emptyList())
            return
        }
        ic.commitText(c.text, 1)
        buffer.setLength(0)
        if (!noLearning) {
            when (c.source) {
                Source.QUICK -> {
                    user.learn("q:${c.key}", c.text)
                    lastChinese?.let { user.learn("a:$it", c.text) }
                }
                Source.ENGLISH -> user.learn("e:${c.key}", c.text)
                Source.ASSOC -> user.learn("a:${c.key}", c.text)
                Source.RAW -> Unit
            }
        }
        val last = c.text.lastChar()
        lastChinese = last
        if (useAssoc) showAssoc(last) else showCandidates(emptyList())
    }

    private fun showAssoc(ch: String) {
        showCandidates(user.rank("a:$ch", dict.associate(ch)).map { Candidate(it, Source.ASSOC, ch) })
    }

    private fun showCandidates(list: List<Candidate>) {
        candidates = list
        if (expanded) setExpanded(false) else bar?.show(list, false)
    }

    private fun toggleExpand() = setExpanded(!expanded)

    private fun setExpanded(on: Boolean) {
        expanded = on && candidates.isNotEmpty()
        gridScroll?.visibility = if (expanded) View.VISIBLE else View.GONE
        keyboard?.visibility = if (expanded) View.INVISIBLE else View.VISIBLE
        if (expanded) {
            grid?.setItems(candidates)
            gridScroll?.scrollTo(0, 0)
        }
        bar?.show(candidates, expanded)
    }

    // ---------------------------------------------------------------- settings

    private fun readPrefs() {
        useAssoc = prefs.getBoolean(Prefs.ASSOC, true)
        useEnglish = prefs.getBoolean(Prefs.ENGLISH, true)
        smartPunct = prefs.getBoolean(Prefs.SMART_PUNCT, true)
        haptics = prefs.getBoolean(Prefs.HAPTICS, true)
        val wanted = prefs.getInt(Prefs.RESET_REQUESTED, 0)
        if (wanted != prefs.getInt(Prefs.RESET_DONE, 0)) {
            user.clear()
            prefs.edit().putInt(Prefs.RESET_DONE, wanted).apply()
        }
    }

    private fun enterLabelFor(info: EditorInfo): String {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "傳送"
            EditorInfo.IME_ACTION_SEARCH -> "搜尋"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一個"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "⏎"
        }
    }

    private companion object {
        const val STALE_MS = 400L

        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val EMAIL_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )

        fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        // CJK, full-width punctuation, and surrogate pairs (rare HK chars live there).
        fun Char.isCjkOrWide() = code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF ||
            code in 0xFF00..0xFFEF || isSurrogate()

        fun String.lastChar(): String = substring(Character.offsetByCodePoints(this, length, -1))
    }
}
