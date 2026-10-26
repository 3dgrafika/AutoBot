package pl.autobot.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import pl.autobot.app.engine.BotState

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Wiersz wysuwanego menu w panelu (menu, dodawanie kroku, korekta pozycji i czasu). */
sealed class SheetRow {
    class Title(val text: String) : SheetRow()
    class Line(val text: String) : SheetRow()
    class Btn(val label: String, val color: String = "#3A4A5E", val action: () -> Unit) : SheetRow()
    class Group(val buttons: List<Btn>) : SheetRow()
}

/** ScrollView z ograniczoną wysokością – długie listy (np. wzorce) przewijają się wewnątrz panelu. */
private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}

/**
 * Pływający panel sterowania wyświetlany nad grą (jak w prototypie Figma):
 * przesuń ✥ · poprzedni krok ⏮ · START/PAUZA ▶/⏸ · następny krok ⏭ · STOP ■ · menu ☰ · zwiń/rozwiń ‹ ›.
 * ⏮ ⏭ ☰ są widoczne, gdy panel ma algorytm do testowania krok po kroku ([setStepAvailable]).
 * Menu ☰ i korekta kroku wysuwają się pod przyciskami ([showSheet]).
 * W trybie RECORD przyciski służą do zapisania lub anulowania nagrywania makra.
 */
class FloatingPanel(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit,
    private val onClose: () -> Unit,
    private val onRecordSave: () -> Unit,
    private val onRecordCancel: () -> Unit,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onMenu: () -> Unit,
) {
    enum class Mode { BOT, RECORD }

    private var mode = Mode.BOT
    private var state = BotState.IDLE
    private var statusText = ""
    private var recordCount = 0
    private var collapsed = false
    private var attached = false
    private var idleHint = "Gotowy – naciśnij ▶"
    private var stepAvailable = false
    private var stepInfo: String? = null
    private var sheetOn = false

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = ctx.dp(12)
        y = ctx.dp(140)
    }

    private fun circle(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(color))
    }

    private fun button(text: String, color: String): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(Color.WHITE)
        background = circle(color)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { setMargins(ctx.dp(2), ctx.dp(3), ctx.dp(2), ctx.dp(3)) }
    }

    private val handle = button("✥", "#8895A7")
    private val prevBtn = button("⏮", "#3E6FB0")
    private val playBtn = button("▶", "#2EA043")
    private val nextBtn = button("⏭", "#3E6FB0")
    private val stopBtn = button("■", "#D1453B")
    private val closeBtn = button("✕", "#55657A")
    private val menuBtn = button("☰", "#8A5CC2")
    private val collapseBtn = button("‹", "#55657A")

    private val statusView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        maxWidth = ctx.dp(260)
        setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(4))
    }

    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(handle); addView(prevBtn); addView(playBtn); addView(nextBtn)
        addView(stopBtn); addView(closeBtn); addView(menuBtn); addView(collapseBtn)
    }

    /** Wysuwane menu: pionowa lista przycisków pod rzędem sterowania. */
    private val sheetBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = ctx.dp(250)
        setPadding(ctx.dp(2), ctx.dp(4), ctx.dp(2), ctx.dp(2))
    }
    private val sheetScroll = MaxHeightScrollView(ctx, ctx.dp(280)).apply {
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        addView(sheetBox)
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(2))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E6222B36"))
            cornerRadius = ctx.dp(14).toFloat()
        }
        addView(row)
        addView(statusView)
        addView(sheetScroll)
    }

    init {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    if (attached) wm.updateViewLayout(root, lp)
                }
            }
            true
        }
        collapseBtn.setOnClickListener { collapsed = !collapsed; refresh() }
        prevBtn.setOnClickListener { onPrev() }
        nextBtn.setOnClickListener { onNext() }
        menuBtn.setOnClickListener { onMenu() }
        refresh()
    }

    fun setIdleHint(t: String) { idleHint = t }
    fun setMode(m: Mode) { mode = m; refresh() }
    fun setRecordCount(n: Int) { recordCount = n; refresh() }

    /** Czy pokazać przyciski kroków ⏮ ⏭ i menu ☰ (panel ma algorytm do przejścia krok po kroku). */
    fun setStepAvailable(on: Boolean) { stepAvailable = on; refresh() }

    /** Opis bieżącego kroku pokazywany w stanie bezczynności (zamiast podpowiedzi „naciśnij ▶”). Null = brak trybu krokowego. */
    fun setStepInfo(t: String?) { stepInfo = t; refresh() }

    val sheetShown: Boolean get() = sheetOn

    fun showSheet(rows: List<SheetRow>) {
        sheetBox.removeAllViews()
        for (r in rows) sheetBox.addView(buildRow(r))
        sheetScroll.scrollTo(0, 0)
        sheetOn = true
        refresh()
    }

    fun hideSheet() {
        if (!sheetOn) return
        sheetOn = false
        sheetBox.removeAllViews()
        refresh()
    }

    fun update(s: BotState, status: String) {
        state = s; statusText = status; refresh()
        // podczas pracy bota panel trzyma ekran włączony (ważne przy długich oczekiwaniach na datę/godzinę)
        val keep = s != BotState.IDLE
        val has = (lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (keep != has) {
            lp.flags = if (keep) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            if (attached) runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    fun show() {
        if (attached) return
        wm.addView(root, lp)
        attached = true
    }

    fun hide() {
        if (!attached) return
        runCatching { wm.removeView(root) }
        attached = false
    }

    /** Przenosi panel na wierzch okien (np. nad uchwytami korekty pozycji, które mogłyby go przykryć). */
    fun bringToFront() {
        if (!attached) return
        runCatching { wm.removeView(root) }
        attached = runCatching { wm.addView(root, lp) }.isSuccess
    }

    private fun vis(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }

    private fun pill(text: String, color: String): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(ctx.dp(8), ctx.dp(9), ctx.dp(8), ctx.dp(9))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = ctx.dp(8).toFloat()
        }
    }

    private fun buildRow(r: SheetRow): View = when (r) {
        is SheetRow.Title -> TextView(ctx).apply {
            text = r.text
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(4))
        }
        is SheetRow.Line -> TextView(ctx).apply {
            text = r.text
            setTextColor(Color.parseColor("#C9D3E0"))
            textSize = 12f
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(2))
        }
        is SheetRow.Btn -> pill(r.label, r.color).apply {
            setOnClickListener { r.action() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2)) }
        }
        is SheetRow.Group -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in r.buttons) {
                addView(pill(b.label, b.color).apply {
                    setOnClickListener { b.action() }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2)) }
                })
            }
        }
    }

    private fun refresh() {
        collapseBtn.text = if (collapsed) "›" else "‹"
        if (mode == Mode.RECORD) {
            vis(playBtn, false)
            vis(prevBtn, false); vis(nextBtn, false); vis(menuBtn, false)
            vis(sheetScroll, false)
            stopBtn.text = "■"
            stopBtn.setOnClickListener { onRecordSave() }
            closeBtn.setOnClickListener { onRecordCancel() }
            vis(stopBtn, !collapsed); vis(closeBtn, !collapsed)
            statusView.text = "● NAGRYWANIE – kroki: $recordCount\n■ zapisz   ✕ anuluj"
            vis(statusView, !collapsed)
        } else {
            val idle = state == BotState.IDLE
            val stepsOn = stepAvailable && !collapsed
            playBtn.text = if (state == BotState.RUNNING) "⏸" else "▶"
            playBtn.setOnClickListener { if (state == BotState.RUNNING) onPause() else onPlay() }
            stopBtn.setOnClickListener { onStop() }
            closeBtn.setOnClickListener { onClose() }
            vis(playBtn, !collapsed)
            vis(prevBtn, stepsOn); vis(nextBtn, stepsOn); vis(menuBtn, stepsOn)
            vis(stopBtn, !collapsed && !idle)
            vis(closeBtn, !collapsed && idle)
            statusView.text = when {
                statusText.isNotEmpty() -> statusText
                idle -> stepInfo ?: idleHint
                else -> ""
            }
            vis(statusView, !collapsed && statusView.text.isNotEmpty())
            vis(sheetScroll, sheetOn && !collapsed)
        }
    }
}

/**
 * Pływający przycisk akcji (np. „● ZAREJESTRUJ KLIKNIĘCIE”, „📷 ZRÓB ZRZUT”): przesuwany uchwytem ✥,
 * z głównym przyciskiem i opcjonalnym ✕ (anuluj). Jest oknem usługi dostępności – nie wymaga dodatkowych uprawnień.
 */
class FloatingAction(
    private val ctx: Context,
    private val wm: WindowManager,
    label: String,
    color: String,
    private val onClick: () -> Unit,
    private val onCancel: (() -> Unit)?,
) {
    private var attached = false

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = ctx.dp(12)
        y = ctx.dp(140)
    }

    private fun circle(c: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(c))
    }

    private fun roundButton(text: String, c: String) = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(Color.WHITE)
        background = circle(c)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3)) }
    }

    private val handle = roundButton("✥", "#8895A7")

    private val main = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = ctx.dp(22).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ctx.dp(44)).apply {
            setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        }
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E6222B36"))
            cornerRadius = ctx.dp(28).toFloat()
        }
        addView(handle)
        addView(main)
    }

    init {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    if (attached) runCatching { wm.updateViewLayout(root, lp) }
                }
            }
            true
        }
        main.setOnClickListener { onClick() }
        if (onCancel != null) {
            val cancel = roundButton("✕", "#55657A")
            cancel.setOnClickListener { onCancel.invoke() }
            root.addView(cancel)
        }
    }

    fun setLabel(t: String) { main.text = t }

    fun show() {
        if (attached) return
        wm.addView(root, lp)
        attached = true
    }

    fun hide() {
        if (!attached) return
        runCatching { wm.removeView(root) }
        attached = false
    }
}
