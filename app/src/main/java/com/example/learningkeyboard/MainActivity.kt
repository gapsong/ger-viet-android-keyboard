package com.example.learningkeyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.*
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.io.FileOutputStream
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

    // ML Kit Translation
    private lateinit var translator: Translator
    private var isModelDownloaded = false
    private lateinit var translationResult: TextView

    // Manual Translation Mode
    private var isTranslationMode = false
    private val translationBuffer = StringBuilder()
    private lateinit var translationInputView: TextView
    private lateinit var downloadButton: TextView

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

    companion object {
        init {
            System.loadLibrary("learningkeyboard")
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupMLKit()
    }

    private fun setupMLKit() {
        if (::translator.isInitialized) {
            translator.close()
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.GERMAN)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
        translator = Translation.getClient(options)

        // Start download immediately
        startModelDownload()
    }

    private fun startModelDownload() {
        if (::downloadButton.isInitialized) {
             downloadButton.text = "..."
             downloadButton.isEnabled = false
        }
        if (::translationResult.isInitialized) {
             translationResult.text = "Modell wird geladen..."
             translationResult.setTextColor(Color.YELLOW)
        }

        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isModelDownloaded = true
                Log.d("TranslationModel", "Model downloaded.")
                handler.post {
                    if (::downloadButton.isInitialized) downloadButton.visibility = View.GONE
                    if (::translationResult.isInitialized) {
                        translationResult.text = if (isTranslationMode) "Bereit. Tippe etwas..." else "Bereit (DE -> VI)"
                        translationResult.setTextColor(Color.GREEN)
                    }
                    autoTranslate()
                }
            }
            .addOnFailureListener { e ->
                isModelDownloaded = false
                Log.e("TranslationModel", "Download failed: ${e.message}")
                handler.post {
                    if (::downloadButton.isInitialized) {
                        downloadButton.visibility = View.VISIBLE
                        downloadButton.text = "Laden"
                        downloadButton.isEnabled = true
                    }
                    if (::translationResult.isInitialized) {
                        translationResult.text = "Download nötig"
                        translationResult.setTextColor(Color.RED)
                    }
                }
            }
    }

    private fun autoTranslate() {
        val textToTranslate = if (isTranslationMode) {
            translationBuffer.toString()
        } else {
            val ic = currentInputConnection
            if (ic == null) return
            ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        }

        if (textToTranslate.isNotBlank()) {
            if (!isModelDownloaded && ::translationResult.isInitialized) {
                 translationResult.text = "Lade Modell & übersetze..."
                 translationResult.setTextColor(Color.YELLOW)
            }

            translator.translate(textToTranslate)
                .addOnSuccessListener { result ->
                    if (::translationResult.isInitialized) {
                        translationResult.text = result
                        translationResult.setTextColor(Color.CYAN)
                    }
                }
                .addOnFailureListener { e ->
                    if (::translationResult.isInitialized) {
                        translationResult.text = "Fehler: ${e.localizedMessage}"
                        translationResult.setTextColor(Color.RED)
                    }
                }
        } else {
            if (::translationResult.isInitialized) {
                if (isTranslationMode) {
                    translationResult.text = "Tippe zum Übersetzen..."
                    translationResult.setTextColor(Color.GRAY)
                } else {
                    translationResult.text = if (isModelDownloaded) "Bereit (DE -> VI)" else "Warte auf Modell..."
                    translationResult.setTextColor(if (isModelDownloaded) Color.GREEN else Color.LTGRAY)
                }
            }
        }
    }


    private fun copyAssetToInternalStorage(fileName: String): String {
        val file = File(filesDir, fileName)
        if (!file.exists()) {
            assets.open(fileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShifted = false
        updateButtonLabels()
        autoTranslate()
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
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    startX = e.x; startY = e.y
                }
                if (e.action == MotionEvent.ACTION_UP) {
                    val dx = e.x - startX; val dy = e.y - startY
                    currentMarks.add(if (hypot(dx, dy) > 30) classifyStroke(dx, dy) else Mark.TAP)
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

        // Translation Display Bar
        keyboardRoot.addView(createTranslationBar())

        Config.layouts[currentPage]?.forEachIndexed { rIdx, row ->
            val rowL = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; weightSum = 10f }
            row.forEachIndexed { kIdx, key ->
                val weight = when { key == "SPACE" -> 3f; rIdx >= 2 && (kIdx == 0 || kIdx == row.size - 1) -> 1.5f; else -> 1f }
                val btn = Button(this).apply {
                    isAllCaps = false; tag = key; setTextColor(Color.WHITE); textSize = 14f; setPadding(0,0,0,0)
                    val heightPx = (64 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(0, heightPx, weight).apply { setMargins(1,1,1,1) }
                    setOnTouchListener { v, e ->
                        if (e.action == MotionEvent.ACTION_DOWN) {
                            v.performClick()
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

    private fun createTranslationBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFF222222.toInt())
        }

        // Download Button
        downloadButton = TextView(this).apply {
            text = "Laden"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF009900.toInt())
                cornerRadius = 8f
            }
            visibility = if (isModelDownloaded) View.GONE else View.VISIBLE
            setOnClickListener { startModelDownload() }
        }
        bar.addView(downloadButton)

        // Manual Translation Toggle
        val toggleBtn = TextView(this).apply {
            text = if (isTranslationMode) "M" else "A" // M: Manual, A: Auto
            setTextColor(if (isTranslationMode) Color.GREEN else Color.LTGRAY)
            textSize = 14f
            setPadding(16, 8, 16, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF444444.toInt())
                cornerRadius = 8f
            }
            setOnClickListener {
                isTranslationMode = !isTranslationMode
                translationBuffer.clear()
                text = if (isTranslationMode) "M" else "A"
                setTextColor(if (isTranslationMode) Color.GREEN else Color.LTGRAY)
                translationInputView.visibility = if (isTranslationMode) View.VISIBLE else View.GONE
                translationInputView.text = ""
                translationResult.text = if (isTranslationMode) "Tippe zum Übersetzen..." else ""
            }
        }
        bar.addView(toggleBtn)

        // Wrapper for Input and Result
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }

        translationInputView = TextView(this).apply {
            text = translationBuffer.toString()
            setTextColor(Color.WHITE)
            textSize = 16f
            visibility = if (isTranslationMode) View.VISIBLE else View.GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        translationResult = TextView(this).apply {
            text = "Übersetzung wird geladen..."
            setTextColor(Color.CYAN)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.START
        }

        textContainer.addView(translationInputView)
        textContainer.addView(translationResult)
        bar.addView(textContainer)

        return bar
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

    private fun updatePreviewText() {
        val res = calculateChar()
        if (isTranslationMode) return
        if (res.isNotEmpty()) currentInputConnection?.setComposingText(if (isShifted) res.uppercase() else res, 1)
        autoTranslate()
    }

    private fun commitGesture() {
        val res = calculateChar()
        val text = if (isShifted) res.uppercase() else res

        if (isTranslationMode) {
            translationBuffer.append(text)
            if (::translationInputView.isInitialized) translationInputView.text = translationBuffer.toString()
        } else {
            currentInputConnection?.commitText(text, 1)
        }

        if (isShifted) { isShifted = false; updateButtonLabels() }
        autoTranslate()
    }

    private fun classifyStroke(dx: Float, dy: Float) = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }.let { a -> when (a) { in 22.5..67.5 -> Mark.UP_RIGHT; in 67.5..112.5 -> Mark.UP; in 112.5..157.5 -> Mark.UP_LEFT; in 157.5..202.5 -> Mark.LEFT; in 202.5..247.5 -> Mark.DOWN_LEFT; in 247.5..292.5 -> Mark.DOWN; in 292.5..337.5 -> Mark.DOWN_RIGHT; else -> Mark.RIGHT } }
    private fun startDel() { stopDel(); deleteRunnable = object : Runnable { override fun run() { handleKeyPress("⌫"); handler.postDelayed(this, 50) } }; handler.postDelayed(deleteRunnable!!, 400) }
    private fun stopDel() { deleteRunnable?.let { handler.removeCallbacks(it) }; deleteRunnable = null }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return

        // Page Navigation
        if (key in setOf("1/3", "2/3", "3/3", "abc")) {
            currentPage = when(key) { "1/3"->2; "2/3"->3; else->1 }
            handler.post { createKeyboardLayout() }
            return
        }

        if (isTranslationMode) {
             when (key) {
                "⌫" -> {
                    if (translationBuffer.isNotEmpty()) {
                        translationBuffer.deleteCharAt(translationBuffer.length - 1)
                    }
                }
                "SPACE" -> translationBuffer.append(" ")
                "↵" -> {
                    val textToCommit = if (::translationResult.isInitialized &&
                                           translationResult.text.isNotEmpty() &&
                                           !translationResult.text.equals("Übersetzung wird geladen...") &&
                                           !translationResult.text.startsWith("Error")) {
                        translationResult.text.toString()
                    } else {
                        translationBuffer.toString()
                    }
                    ic.commitText(textToCommit, 1)
                    translationBuffer.clear()
                    translationResult.text = ""
                }
                "⇧" -> { isShifted = !isShifted; updateButtonLabels() }
                else -> {
                    translationBuffer.append(if (isShifted && key.length == 1) key.uppercase() else key)
                    if (isShifted) { isShifted = false; updateButtonLabels() }
                }
            }
            if (::translationInputView.isInitialized) {
                translationInputView.text = translationBuffer.toString()
            }
            autoTranslate()
            return
        }

        // Standard Mode
        when (key) {
            "⌫" -> ic.deleteSurroundingText(1, 0)
            "SPACE" -> ic.commitText(" ", 1)
            "↵" -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            "⇧" -> { isShifted = !isShifted; updateButtonLabels() }
            else -> { ic.commitText(if (isShifted && key.length == 1) key.uppercase() else key, 1); if (isShifted) { isShifted = false; updateButtonLabels() } }
        }
        autoTranslate()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        autoTranslate()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::translator.isInitialized) {
            translator.close()
        }
    }
}