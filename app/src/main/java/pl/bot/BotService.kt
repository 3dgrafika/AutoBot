package pl.bot
import android.accessibilityservice.*
import android.graphics.*
import android.os.*
import android.view.*
import android.view.WindowManager.LayoutParams as LP
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.combine
import kotlin.math.hypot

class BotService : AccessibilityService() {
    companion object { var inst: BotService? = null }
    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { Engine.stop() }
    override fun onDestroy() { Engine.stop(); inst = null; super.onDestroy() }

    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        suspendCancellableCoroutine { c ->
            val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1))).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!ok && c.isActive) c.resume(false)
        }

    /** Zrzut ekranu bez MediaProjection (Android 11+). System ogranicza częstotliwość do ok. 1/s. */
    suspend fun shot(): Bitmap? {
        ui.post { stopV?.alpha = 0f; launcher?.alpha = 0f }; delay(120)
        try { return rawShot() } finally { ui.post { stopV?.alpha = 1f; launcher?.alpha = 1f } }
    }
    private suspend fun rawShot(): Bitmap? = suspendCancellableCoroutine { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val b = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close(); if (c.isActive) c.resume(b)
            }
            override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
        })
    }

    private var stopV: View? = null
    fun showStop() = ui.post {
        if (stopV != null || launcher != null) return@post
        val b = Button(this).apply { text = "■ STOP"; setOnClickListener { Engine.stop() } }
        wm.addView(b, LP(-2, -2, LP.TYPE_ACCESSIBILITY_OVERLAY, LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END }); stopV = b
    }
    fun hideStop() = ui.post { stopV?.let { wm.removeView(it) }; stopV = null }

    private var launcher: View? = null

    /** Floating START BOT overlay; the selected algorithms run only after the user presses START BOT. */
    fun showBotLauncher(scripts: List<Script>) = ui.post {
        launcher?.let { old -> (old.tag as? Job)?.cancel(); runCatching { wm.removeView(old) } }
        launcher = null
        stopV?.let { runCatching { wm.removeView(it) } }; stopV = null
        val lp = LP(-2, -2, LP.TYPE_ACCESSIBILITY_OVERLAY, LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 180 }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
            setBackgroundColor(0xEE20252B.toInt())
        }
        var ox = 0; var oy = 0; var dx = 0f; var dy = 0f
        val handle = TextView(this).apply {
            text = "☰"; textSize = 20f; setTextColor(Color.WHITE); setPadding(16, 8, 16, 8)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; dx = e.rawX; dy = e.rawY }
                    MotionEvent.ACTION_MOVE -> { lp.x = (ox + e.rawX - dx).toInt(); lp.y = (oy + e.rawY - dy).toInt(); runCatching { wm.updateViewLayout(panel, lp) } }
                }
                true
            }
        }
        val start = Button(this).apply {
            text = "▶ START BOT"
            setOnClickListener {
                if (Engine.running.value) Engine.stop()
                else Engine.startSequence(applicationContext, scripts)
            }
        }
        val pause = Button(this).apply {
            text = "⏸"
            visibility = View.GONE
            setOnClickListener { Engine.paused.value = !Engine.paused.value }
        }
        val close = Button(this).apply {
            text = "✕"
            setOnClickListener { Engine.stop(); hideBotLauncher() }
        }
        listOf(handle, start, pause, close).forEach { panel.addView(it) }
        panel.tag = CoroutineScope(Dispatchers.Main).launch {
            combine(Engine.running, Engine.paused) { r, p -> r to p }.collect { (r, p) ->
                start.text = if (r) "■ STOP" else "▶ START BOT"
                pause.visibility = if (r) View.VISIBLE else View.GONE
                pause.text = if (p) "▶ Wznów" else "⏸ Pauza"
            }
        }
        wm.addView(panel, lp)
        launcher = panel
    }

    fun hideBotLauncher() = ui.post {
        launcher?.let { view ->
            (view.tag as? Job)?.cancel()
            runCatching { wm.removeView(view) }
        }
        launcher = null
    }

    /** Najpierw mały panel z przyciskiem START: można przełączyć się na docelową aplikację, nagrywanie rusza po jego naciśnięciu. */
    fun record(done: (List<Step>) -> Unit) = ui.post {
        val panel = LinearLayout(this)
        panel.addView(Button(this).apply { text = "● START NAGRYWANIA"; setOnClickListener { wm.removeView(panel); beginRecording(done) } })
        panel.addView(Button(this).apply { text = "✕"; setOnClickListener { wm.removeView(panel) } })
        wm.addView(panel, LP(-2, -2, LP.TYPE_ACCESSIBILITY_OVERLAY, LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END })
    }

    /** Nakładka łapie gest, zapisuje go i od razu odtwarza pod spodem, więc można normalnie nawigować po aplikacji. */
    private fun beginRecording(done: (List<Step>) -> Unit) {
        val steps = mutableListOf<Step>(); var lastUp = 0L; var t0 = 0L; var sx = 0f; var sy = 0f
        val root = FrameLayout(this)
        val lp = LP(-1, -1, LP.TYPE_ACCESSIBILITY_OVERLAY, LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        val v = View(this).apply { setBackgroundColor(0x22FF0000) }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; t0 = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis(); val dur = (now - t0).coerceAtLeast(1)
                    if (lastUp > 0 && t0 - lastUp > 50) steps.add(Step("wait", mutableMapOf("ms" to (t0 - lastUp).toString())))
                    lastUp = now
                    val ex = e.rawX; val ey = e.rawY
                    steps.add(if (hypot(ex - sx, ey - sy) < 30) Step("tap", mutableMapOf("x" to sx.toInt().toString(), "y" to sy.toInt().toString(), "ms" to dur.toString()))
                    else Step("swipe", mutableMapOf("x1" to sx.toInt().toString(), "y1" to sy.toInt().toString(), "x2" to ex.toInt().toString(), "y2" to ey.toInt().toString(), "ms" to dur.toString())))
                    lp.flags = lp.flags or LP.FLAG_NOT_TOUCHABLE; wm.updateViewLayout(root, lp)
                    CoroutineScope(Dispatchers.Main).launch {
                        gesture(sx, sy, ex, ey, dur)
                        lp.flags = lp.flags and LP.FLAG_NOT_TOUCHABLE.inv(); wm.updateViewLayout(root, lp)
                    }
                }
            }; true
        }
        root.addView(v)
        root.addView(Button(this).apply { text = "■ Zakończ nagrywanie"
            setOnClickListener { wm.removeView(root); done(steps) } },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        wm.addView(root, lp)
    }
}
