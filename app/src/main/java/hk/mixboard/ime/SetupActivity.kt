package hk.mixboard.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Turn the keyboard on, try it, and change a few settings. */
class SetupActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var enableButton: Button
    private lateinit var chooseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val pad = dp(22)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(36), pad, pad)
        }
        col.addView(text("Mixboard", 30f, bold = true))
        col.addView(text("中英混合鍵盤。照打英文，上面會同時出速成字；撳字就變中文，撳空白鍵就保留英文。", 15f, top = 6))

        col.addView(heading("開始用"))
        enableButton = button { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        chooseButton = button { imm().showInputMethodPicker() }
        col.addView(enableButton)
        col.addView(chooseButton)
        col.addView(text("Android 會警告第三方鍵盤可能收集你打嘅字。Mixboard 冇網絡權限，打嘅嘢唔會離開部機。", 13f, top = 6, muted = true))

        col.addView(heading("試吓"))
        col.addView(EditText(this).apply {
            hint = "例如：hi → 我，toy → 玩具，打完 busy 撳空白鍵"
            minLines = 3
        })

        col.addView(heading("設定"))
        col.addView(toggle("聯想字", Prefs.ASSOC))
        col.addView(toggle("英漢詞典（toy → 玩具）", Prefs.ENGLISH))
        col.addView(toggle("智能標點（中文句出，。英文句出 , .）", Prefs.SMART_PUNCT))
        col.addView(toggle("按鍵震動", Prefs.HAPTICS))

        col.addView(heading("手勢"))
        col.addView(text("空白鍵左右拉：移游標\n長按 q–p：數字\n長按 ，。：轉全形／半形\n長按 🌐：揀輸入法\n⇧ 撳兩下：大楷鎖定", 14f, top = 2))

        col.addView(heading("學習記錄"))
        col.addView(text("Mixboard 會記住你揀過嘅字同聯想，下次排前啲。", 14f, top = 2))
        col.addView(button("清除學習記錄") { confirmReset() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // The input method picker is a dialog, so we only learn it closed via focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    private fun refresh() {
        val enabled = imm().enabledInputMethodList.any { it.packageName == packageName }
        val current = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val selected = current.startsWith("$packageName/")
        enableButton.text = if (enabled) "✓ 已喺設定開啟" else "1. 喺設定開啟 Mixboard"
        chooseButton.text = if (selected) "✓ 而家用緊 Mixboard" else "2. 轉用 Mixboard"
        chooseButton.isEnabled = enabled
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage("清除你揀字同聯想嘅習慣？內置字庫唔受影響。")
            .setPositiveButton("清除") { _, _ ->
                prefs.edit().putInt(Prefs.RESET_REQUESTED, prefs.getInt(Prefs.RESET_REQUESTED, 0) + 1).apply()
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false, top: Int = 0, muted: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (muted) alpha = 0.7f
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(top), 0, 0)
        }

    private fun heading(s: String) = text(s, 18f, bold = true, top = 26)

    private fun button(label: String = "", onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, key: String) = Switch(this).apply {
        text = label
        textSize = 16f
        isChecked = prefs.getBoolean(key, true)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
    }
}
