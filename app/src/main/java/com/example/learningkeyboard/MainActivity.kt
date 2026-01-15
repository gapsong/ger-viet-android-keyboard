package com.example.learningkeyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.math.atan2
import kotlin.math.hypot

class MainActivity : InputMethodService() {
    private var currentPage = 1
    private var isShifted = false
    private lateinit var keyboardRoot: LinearLayout
    private lateinit var frameLayout: FrameLayout
    private lateinit var overlayView: View
    private val buttonList = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private var gestureDelayRunnable: Runnable? = null

    private var primaryHeldKey: String? = null
    private val currentMarks = mutableListOf<Mark>()
    private var startX = 0f
    private var startY = 0f

    enum class Mark { TAP, UP_RIGHT, DOWN_RIGHT, UP_LEFT, DOWN_LEFT, UP, DOWN, LEFT, RIGHT }

    object Config {
        val supported = setOf("a", "e", "i", "o", "u", "y", "d", "s")
        val layouts = mapOf(
            1 to arrayOf(arrayOf("q","w","e","r","t","y","u","i","o","p"), arrayOf("a","s","d","f","g","h","j","k","l"), arrayOf("⇧","z","x","c","v","b","n","m","⌫"), arrayOf("1/3","?","!","SPACE",",",".","↵")),
            2 to arrayOf(arrayOf("1","2","3","4","5","6","7","8","9","0"), arrayOf("@","#","%","&","*","/","+","-","="), arrayOf("abc","\"","'","§","$","€","£","¥","⌫"), arrayOf("2/3",";",":","SPACE",",",".","↵")),
            3 to arrayOf(arrayOf("^^",":-)",";-)",":-D","XD",":-P",":-*","<3",":-/",":-("), arrayOf("(",")","[","]","{","}","<",">","/","\\"), arrayOf("abc",".",",","-","~","|","^","°","⌫"), arrayOf("3/3",";",":","SPACE",",",".","↵"))
        )
        val mods = mapOf(
            "a" to mapOf(Mark.LEFT to "â", Mark.RIGHT to "ă"), 
            "e" to mapOf(Mark.LEFT to "ê"), 
            "o" to mapOf(Mark.LEFT to "ô", Mark.RIGHT to "ơ"), 
            "u" to mapOf(Mark.RIGHT to "ư"), 
            "d" to mapOf(Mark.RIGHT to "đ")
        )
        val umlaute = mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß")
        val tones = mapOf("a" to "áàảãạ", "â" to "ấầẩẫậ", "ă" to "ắằẳẵặ", "e" to "éèẻẽẹ", "ê" to "ếềểễệ", "i" to "íìỉĩị", "o" to "óòỏõọ", "ô" to "ốồổỗộ", "ơ" to "ớờởỡợ", "u" to "úùủũụ", "ư" to "ứừửữự", "y" to "ýỳỷỹỵ")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShifted = false
        updateButtonLabels()
    }

    override fun onCreateInputView(): View {
        frameLayout = FrameLayout(this).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
        keyboardRoot = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF333333.toInt())
        }
        overlayView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM)
            setBackgroundColor(0x44000000.toInt())
            visibility = View.INVISIBLE
            isHapticFeedbackEnabled = true
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    startX = e.x; startY = e.y
                }
                if (e.action == MotionEvent.ACTION_UP) {
                    val dx = e.x - startX; val dy = e.y - startY
                    currentMarks.add(if (hypot(dx, dy) > 30) classifyStroke(dx, dy) else Mark.TAP)
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    updatePreviewText()
                }
                true
            }
        }
        frameLayout.addView(keyboardRoot); frameLayout.addView(overlayView)
        createKeyboardLayout()
        return frameLayout
    }

    private fun createKeyboardLayout() {
        keyboardRoot.removeAllViews(); buttonList.clear()
        val legend = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(0, 12, 0, 12); setBackgroundColor(0xFF222222.toInt())
            listOf("↗ ´", "↘ `", "↑ ?", "↓ ~", "• Dot", "← ^", "→ ˘").forEach {
                addView(TextView(this@MainActivity).apply { text = it; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(12, 0, 12, 0) })
            }
        }
        keyboardRoot.addView(legend)
        Config.layouts[currentPage]?.forEachIndexed { rIdx, row ->
            val rowL = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; weightSum = 10f }
            row.forEachIndexed { kIdx, key ->
                val weight = when { key == "SPACE" -> 3f; rIdx >= 2 && (kIdx == 0 || kIdx == row.size - 1) -> 1.5f; else -> 1f }
                val btn = Button(this).apply {
                    isAllCaps = false; tag = key; setTextColor(Color.WHITE); textSize = 14f; setPadding(0,0,0,0)
                    isHapticFeedbackEnabled = true
                    layoutParams = LinearLayout.LayoutParams(0, -2, weight).apply { setMargins(1,1,1,1) }
                    setOnTouchListener { v, e -> 
                        if (e.action == MotionEvent.ACTION_DOWN) {
                            v.performClick()
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                        }
                        handlePrimaryTouch(key, e); true 
                    }
                }
                buttonList.add(btn); rowL.addView(btn)
            }
            keyboardRoot.addView(rowL)
        }
        updateButtonLabels()
        keyboardRoot.post { overlayView.layoutParams.height = keyboardRoot.height; overlayView.requestLayout() }
    }

    private fun updateButtonLabels() {
        buttonList.forEach { b ->
            val k = b.tag as String
            b.text = if (isShifted && k.length == 1 && k[0].isLetter() && currentPage == 1) k.uppercase() else k
            b.setBackgroundColor(if (k == "⇧" && isShifted) 0xFF888888.toInt() else 0xFF444444.toInt())
        }
    }

    private fun handlePrimaryTouch(key: String, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (primaryHeldKey != null) finalize()
                primaryHeldKey = key; currentMarks.clear()
                gestureDelayRunnable?.let { handler.removeCallbacks(it) }
                if (key == "⌫") { handleKeyPress(key); startDel() }
                else if (Config.supported.contains(key.lowercase()) && currentPage == 1) {
                    updatePreviewText()
                    gestureDelayRunnable = Runnable { if (primaryHeldKey == key) overlayView.visibility = View.VISIBLE }
                    handler.postDelayed(gestureDelayRunnable!!, 100L)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (primaryHeldKey == key) finalize()
        }
    }

    private fun finalize() {
        val k = primaryHeldKey ?: return
        handler.removeCallbacks(gestureDelayRunnable ?: Runnable{}); gestureDelayRunnable = null
        val wasGesture = overlayView.visibility == View.VISIBLE
        overlayView.visibility = View.INVISIBLE
        if (k == "⌫") stopDel()
        else if (Config.supported.contains(k.lowercase()) && currentPage == 1 && wasGesture) commitGesture()
        else handleKeyPress(k)
        primaryHeldKey = null
    }

    private fun calculateChar(): String {
        val base = primaryHeldKey?.lowercase() ?: return ""
        var res = base
        if (currentMarks.contains(Mark.LEFT)) res = Config.mods[res]?.get(Mark.LEFT) ?: res
        if (currentMarks.contains(Mark.RIGHT)) res = Config.mods[res]?.get(Mark.RIGHT) ?: res
        if (currentMarks.count { it == Mark.TAP } >= 2) res = Config.umlaute[res] ?: res
        val toneIdx = when { currentMarks.contains(Mark.UP_RIGHT)->1; currentMarks.contains(Mark.DOWN_RIGHT)->2; currentMarks.contains(Mark.UP)->3; currentMarks.contains(Mark.DOWN)->4; currentMarks.contains(Mark.TAP) && !currentMarks.contains(Mark.LEFT)->5; else->0 }
        return if (toneIdx > 0) Config.tones[res]?.getOrNull(toneIdx - 1)?.toString() ?: res else res
    }

    private fun updatePreviewText() { val res = calculateChar(); if (res.isNotEmpty()) currentInputConnection?.setComposingText(if (isShifted) res.uppercase() else res, 1) }
    private fun commitGesture() { val res = calculateChar(); currentInputConnection?.commitText(if (isShifted) res.uppercase() else res, 1); if (isShifted) { isShifted = false; updateButtonLabels() } }
    private fun classifyStroke(dx: Float, dy: Float) = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }.let { a -> when (a) { in 22.5..67.5 -> Mark.UP_RIGHT; in 67.5..112.5 -> Mark.UP; in 112.5..157.5 -> Mark.UP_LEFT; in 157.5..202.5 -> Mark.LEFT; in 202.5..247.5 -> Mark.DOWN_LEFT; in 247.5..292.5 -> Mark.DOWN; in 292.5..337.5 -> Mark.DOWN_RIGHT; else -> Mark.RIGHT } }
    private fun startDel() { stopDel(); deleteRunnable = object : Runnable { override fun run() { handleKeyPress("⌫"); handler.postDelayed(this, 50) } }; handler.postDelayed(deleteRunnable!!, 400) }
    private fun stopDel() { deleteRunnable?.let { handler.removeCallbacks(it) }; deleteRunnable = null }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> ic.deleteSurroundingText(1, 0)
            "SPACE" -> ic.commitText(" ", 1)
            "1/3", "2/3", "3/3", "abc" -> { currentPage = when(key) { "1/3"->2; "2/3"->3; else->1 }; handler.post { createKeyboardLayout() } }
            "↵" -> {
                // Send standard Enter key events which are less likely to trigger auto-hiding
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            "⇧" -> { isShifted = !isShifted; updateButtonLabels() }
            else -> { ic.commitText(if (isShifted && key.length == 1) key.uppercase() else key, 1); if (isShifted) { isShifted = false; updateButtonLabels() } }
        }
    }
}