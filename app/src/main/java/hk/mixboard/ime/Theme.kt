package hk.mixboard.ime

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.TypedValue

/** Keyboard colours. Follows the phone's light / dark setting. */
class Theme private constructor(val dark: Boolean, private val metrics: DisplayMetrics) {
    val board = if (dark) 0xFF1D2024.toInt() else 0xFFE3E6EA.toInt()
    val key = if (dark) 0xFF3A3E44.toInt() else 0xFFFFFFFF.toInt()
    val fnKey = if (dark) 0xFF2A2D32.toInt() else 0xFFC5CAD1.toInt()
    val keyPressed = if (dark) 0xFF555A62.toInt() else 0xFFB1B7C0.toInt()
    val keyShadow = if (dark) 0xFF101215.toInt() else 0xFFA6ACB5.toInt()
    val text = if (dark) 0xFFECEFF3.toInt() else 0xFF1A1D21.toInt()
    val subText = if (dark) 0xFF99A0AA.toInt() else 0xFF68707A.toInt()
    val accent = if (dark) 0xFF2E9C83.toInt() else 0xFF0E7A64.toInt() // jade
    val accentText = if (dark) 0xFF5FD3B5.toInt() else 0xFF0E7A64.toInt()
    val onAccent = 0xFFFFFFFF.toInt()
    val divider = if (dark) 0xFF33373D.toInt() else 0xFFCCD1D7.toInt()

    fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, metrics)
    fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics)

    companion object {
        fun from(context: Context): Theme {
            val res = context.resources
            val night = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return Theme(night, res.displayMetrics)
        }
    }
}
