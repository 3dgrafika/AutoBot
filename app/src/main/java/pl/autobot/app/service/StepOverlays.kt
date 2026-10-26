package pl.autobot.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import pl.autobot.app.data.Pt
import pl.autobot.app.engine.ClickMarkers
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Parametry okna nakładki na całym ekranie, przez które przechodzą dotknięcia (tylko rysowanie). */
private fun fullScreenPassThroughParams() = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
)

/* ═════════════ Znaczniki kliknięć widoczne podczas testu ═════════════ */

/**
 * Rysuje na ekranie miejsce, w którym bot właśnie kliknął (pulsujące kółko + współrzędne),
 * a dla przesunięcia – ścieżkę ze strzałką. Okno jest „przezroczyste” dla dotyku, więc nie przeszkadza
 * w klikaniu. Znaczniki znikają same po chwili albo na żądanie silnika (przed zrzutem ekranu).
 */
class ClickMarkerOverlay(private val ctx: Context, private val wm: WindowManager) : ClickMarkers {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var visible = false
    private var view: MarkerView? = null
    private val autoHide = Runnable {
        visible = false
        removeNow()
    }

    override fun show(points: List<Pt>, label: String) {
        if (points.isEmpty()) return
        visible = true
        handler.post { showNow(points, label) }
    }

    override fun clear(): Boolean {
        if (!visible) return false
        visible = false
        handler.post {
            handler.removeCallbacks(autoHide)
            removeNow()
        }
        return true
    }

    /** Natychmiastowe usunięcie (wołać z głównego wątku, np. przy wyłączaniu usługi). */
    fun dispose() {
        visible = false
        handler.removeCallbacks(autoHide)
        removeNow()
    }

    private fun showNow(points: List<Pt>, label: String) {
        var v = view
        if (v == null) {
            v = MarkerView(ctx)
            if (runCatching { wm.addView(v, fullScreenPassThroughParams()) }.isFailure) return
            view = v
        }
        v.set(points, label)
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, SHOW_MS)
    }

    private fun removeNow() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private companion object {
        const val SHOW_MS = 1600L
    }
}

private class MarkerView(private val ctx: Context) : View(ctx) {
    private var pts: List<Pt> = emptyList()
    private var label = ""
    private var t0 = 0L

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(235, 229, 57, 53) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.dp(3).toFloat() }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(255, 255, 214, 0); strokeWidth = ctx.dp(3).toFloat() }
    private val startDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(235, 46, 160, 67) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(220, 255, 214, 0); strokeWidth = ctx.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = ctx.dp(14).toFloat(); textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    fun set(points: List<Pt>, lbl: String) {
        pts = points
        label = lbl
        t0 = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (pts.isEmpty()) return
        val first = pts.first()
        val last = pts.last()
        val swipe = pts.size > 1
        if (swipe) {
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            c.drawPath(path, line)
            // grot na końcu ścieżki
            val prev = pts[pts.size - 2]
            val ang = atan2((last.y - prev.y).toDouble(), (last.x - prev.x).toDouble())
            val len = ctx.dp(16).toFloat()
            for (side in listOf(-0.5, 0.5)) {
                c.drawLine(
                    last.x, last.y,
                    last.x - (len * cos(ang + side)).toFloat(), last.y - (len * sin(ang + side)).toFloat(), line
                )
            }
            c.drawCircle(first.x, first.y, ctx.dp(9).toFloat(), startDot)
            c.drawCircle(first.x, first.y, ctx.dp(9).toFloat(), ring)
        }
        // pulsująca fala + kropka w miejscu kliknięcia (dla przesunięcia: na końcu ścieżki)
        val age = SystemClock.uptimeMillis() - t0
        val phase = (age % 800L) / 800f
        wave.alpha = ((1f - phase) * 230).toInt()
        c.drawCircle(last.x, last.y, ctx.dp(14).toFloat() + phase * ctx.dp(34), wave)
        c.drawCircle(last.x, last.y, ctx.dp(9).toFloat(), dot)
        c.drawCircle(last.x, last.y, ctx.dp(9).toFloat(), ring)
        val caption = "$label  (${last.x.toInt()}, ${last.y.toInt()})"
        val ty = if (last.y > height - ctx.dp(80)) last.y - ctx.dp(34) else last.y + ctx.dp(44)
        c.drawText(caption, last.x.coerceIn(ctx.dp(80).toFloat(), (width - ctx.dp(80)).toFloat()), ty, text)
        postInvalidateOnAnimation()
    }
}

/* ═════════════ Korekta pozycji: przeciągalne uchwyty ═════════════ */

/**
 * Pokazuje na ekranie przeciągalne uchwyty (celowniki) w miejscach, w które kliknie wybrany krok:
 * jeden uchwyt dla kliknięcia albo dwa (początek i koniec) dla przesunięcia, połączone linią.
 * Po puszczeniu palca wywoływane jest [onMoved] z nową pozycją.
 */
class PositionEditor(
    private val ctx: Context,
    private val wm: WindowManager,
    private val screen: () -> Pair<Int, Int>,
) {
    private val handles = ArrayList<Pair<HandleView, WindowManager.LayoutParams>>()
    private var lineView: LineView? = null
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private var onSelect: ((Int) -> Unit)? = null
    private var onMoved: ((Int, Int, Int) -> Unit)? = null
    private val half get() = ctx.dp(28)

    val isShown: Boolean get() = handles.isNotEmpty()

    /** [points] – pozycje w pikselach ekranu; [selected] – podświetlony uchwyt (ten, który poruszają strzałki). */
    fun show(
        points: List<Pair<Int, Int>>,
        selected: Int,
        onSelect: (Int) -> Unit,
        onMoved: (index: Int, x: Int, y: Int) -> Unit,
    ) {
        hide()
        if (points.isEmpty()) return
        this.onSelect = onSelect
        this.onMoved = onMoved
        points.forEach { xs.add(it.first.toFloat()); ys.add(it.second.toFloat()) }
        if (points.size >= 2) {
            val lv = LineView(ctx)
            lv.set(xs[0], ys[0], xs[1], ys[1])
            if (runCatching { wm.addView(lv, fullScreenPassThroughParams()) }.isSuccess) lineView = lv
        }
        points.forEachIndexed { i, _ ->
            val hv = HandleView(ctx, i, if (points.size > 1) (if (i == 0) "1" else "2") else "")
            hv.highlighted = i == selected
            val lp = WindowManager.LayoutParams(
                half * 2, half * 2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (xs[i] - half).toInt()
                y = (ys[i] - half).toInt()
            }
            if (runCatching { wm.addView(hv, lp) }.isSuccess) handles.add(hv to lp)
        }
    }

    /** Przesuwa uchwyt programowo (np. strzałkami z menu). */
    fun setPoint(index: Int, x: Int, y: Int) = move(index, x.toFloat(), y.toFloat())

    fun setSelected(index: Int) {
        handles.forEachIndexed { i, (v, _) -> v.highlighted = i == index; v.invalidate() }
    }

    fun hide() {
        handles.forEach { (v, _) -> runCatching { wm.removeView(v) } }
        handles.clear()
        lineView?.let { runCatching { wm.removeView(it) } }
        lineView = null
        xs.clear(); ys.clear()
    }

    private fun move(index: Int, x: Float, y: Float) {
        if (index !in handles.indices) return
        val (w, h) = screen()
        xs[index] = x.coerceIn(0f, (w - 1).toFloat())
        ys[index] = y.coerceIn(0f, (h - 1).toFloat())
        val (v, lp) = handles[index]
        lp.x = (xs[index] - half).toInt()
        lp.y = (ys[index] - half).toInt()
        runCatching { wm.updateViewLayout(v, lp) }
        if (xs.size >= 2) lineView?.set(xs[0], ys[0], xs[1], ys[1])
    }

    private inner class HandleView(ctx: Context, private val index: Int, private val tagText: String) : View(ctx) {
        var highlighted = false
        private var downX = 0f
        private var downY = 0f
        private var baseX = 0f
        private var baseY = 0f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(70, 0, 0, 0) }
        private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat() }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat(); color = Color.WHITE }
        private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(255, 229, 57, 53) }
        private val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = ctx.dp(13).toFloat(); textAlign = Paint.Align.CENTER
            isFakeBoldText = true; setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = ctx.dp(22).toFloat()
            ringP.color = if (highlighted) Color.argb(255, 255, 160, 0) else Color.argb(255, 255, 255, 255)
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, ringP)
            c.drawLine(cx - r, cy, cx - ctx.dp(5), cy, cross)
            c.drawLine(cx + ctx.dp(5), cy, cx + r, cy, cross)
            c.drawLine(cx, cy - r, cx, cy - ctx.dp(5), cross)
            c.drawLine(cx, cy + ctx.dp(5), cx, cy + r, cross)
            c.drawCircle(cx, cy, ctx.dp(3).toFloat(), centerDot)
            if (tagText.isNotEmpty()) c.drawText(tagText, width - ctx.dp(8).toFloat(), ctx.dp(14).toFloat(), tagP)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    baseX = xs.getOrElse(index) { 0f }; baseY = ys.getOrElse(index) { 0f }
                    onSelect?.invoke(index)
                }
                MotionEvent.ACTION_MOVE -> move(index, baseX + (e.rawX - downX), baseY + (e.rawY - downY))
                MotionEvent.ACTION_UP -> onMoved?.invoke(index, xs.getOrElse(index) { 0f }.toInt(), ys.getOrElse(index) { 0f }.toInt())
            }
            return true
        }
    }
}

private class LineView(private val ctx: Context) : View(ctx) {
    private var x1 = 0f; private var y1 = 0f; private var x2 = 0f; private var y2 = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(220, 255, 160, 0); strokeWidth = ctx.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND
    }

    fun set(ax: Float, ay: Float, bx: Float, by: Float) {
        x1 = ax; y1 = ay; x2 = bx; y2 = by
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawLine(x1, y1, x2, y2, p)
        val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len = ctx.dp(16).toFloat()
        for (side in listOf(-0.5, 0.5)) {
            c.drawLine(x2, y2, x2 - (len * cos(ang + side)).toFloat(), y2 - (len * sin(ang + side)).toFloat(), p)
        }
    }
}
