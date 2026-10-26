package pl.autobot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.autobot.app.App
import pl.autobot.app.MainActivity
import pl.autobot.app.data.Algorithm
import pl.autobot.app.data.GestureStep
import pl.autobot.app.data.gestureStepsToBlocks
import pl.autobot.app.data.PickedGesture
import pl.autobot.app.data.Pt
import pl.autobot.app.engine.BotState
import pl.autobot.app.engine.DeviceIO
import pl.autobot.app.engine.ScreenshotException
import pl.autobot.app.engine.TemplateMatcher
import pl.autobot.app.ui.PendingImage
import kotlin.coroutines.resume
import kotlin.math.hypot

/**
 * Serce aplikacji po stronie systemu Android:
 *  • dispatchGesture()  – wykonywanie kliknięć i przesunięć (API 24+)
 *  • takeScreenshot()   – zrzuty ekranu bez MediaProjection (API 30+)
 *  • nakładki TYPE_ACCESSIBILITY_OVERLAY – nagrywanie dotknięć, panel START/STOP
 *    (nie wymagają osobnego uprawnienia "wyświetlanie nad innymi aplikacjami").
 */
class BotAccessibilityService : AccessibilityService(), DeviceIO {

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
        val connected = MutableStateFlow(false)
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val app get() = application as App

    private var panel: FloatingPanel? = null
    private var stepCtl: StepController? = null
    private var posEditor: PositionEditor? = null
    private var markerOverlay: ClickMarkerOverlay? = null
    private var recView: RecorderView? = null
    private var recLp: WindowManager.LayoutParams? = null
    private val recSteps = ArrayList<GestureStep>()
    private var lastUpTime = 0L
    /** true, gdy trwa odtwarzanie właśnie nagranego gestu – dotyk wstrzyknięty przez nas nie może być nagrany ponownie. */
    @Volatile private var replaying = false
    private var actionPill: FloatingAction? = null
    private var pickView: PickView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.value = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        app.engine.highlighter = { m -> mainHandler.post { flash(m) } }
        markerOverlay = ClickMarkerOverlay(this, wm).also { app.engine.markers = it }
        mainScope.launch {
            combine(app.engine.state, app.engine.status) { s, t -> s to t }
                .collect { (s, t) -> panel?.update(s, t) }
        }
        app.repo.log("INFO", "Usługa dostępności połączona")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* niepotrzebne */ }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        app.engine.stop()
        finishRecording(false)
        cancelPick()
        hideActionPill()
        stepCtl?.leave()
        hideCoordEdit()
        markerOverlay?.dispose()
        app.engine.markers = null
        panel?.hide()
        instance = null
        connected.value = false
        mainScope.cancel()
        return super.onUnbind(intent)
    }

    /* ───────────────── DeviceIO ───────────────── */

    override suspend fun gesture(points: List<Pt>, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path()
            // współrzędne (po losowym przesunięciu) nie mogą wyjść poza ekran
            val bounds = wm.maximumWindowMetrics.bounds
            val maxX = (bounds.width() - 1).toFloat()
            val maxY = (bounds.height() - 1).toFloat()
            points.forEachIndexed { i, p ->
                val x = p.x.coerceIn(0f, maxX)
                val y = p.y.coerceIn(0f, maxY)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val dur = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
            val started = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!started && cont.isActive) cont.resume(false)
        }

    override suspend fun screenshot(): Bitmap = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                try {
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    if (cont.isActive) {
                        if (soft != null) cont.resume(soft)
                        else cont.resumeWith(Result.failure(ScreenshotException(-1)))
                    }
                } finally {
                    hb.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resumeWith(Result.failure(ScreenshotException(errorCode)))
            }
        })
    }

    /* ───────────────── Panel sterowania ───────────────── */

    private fun screenSize(): Pair<Int, Int> = wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }

    private fun ensurePanel(): FloatingPanel = panel ?: FloatingPanel(
        ctx = this, wm = wm,
        onPlay = {
            val e = app.engine
            val test = panelTestId
            if (e.state.value == BotState.PAUSED) e.resume()
            else if (e.state.value == BotState.IDLE) {
                // w trybie krokowym ▶ kontynuuje od kroku z kursora (np. 7/20), a nie od początku
                val from = stepCtl?.resumePoint()
                stepCtl?.leave()
                if (from != null) e.runAlgorithmOnce(from.first, from.second)
                else if (test != null) e.runAlgorithmOnce(test)
                else e.startSelected()
            }
        },
        onPause = { app.engine.pause() },
        onStop = { app.engine.stop() },
        onClose = {
            // ✕ zamyka panel i wraca do okna AutoBota (do edytora testowanego algorytmu)
            stepCtl?.leave()
            val id = panelTestId
            panelTestId = null
            returnToApp(id) { panel?.hide() }
        },
        onRecordSave = { finishRecording(true) },
        onRecordCancel = { finishRecording(false) },
        onPrev = { stepCtl?.onPrev() },
        onNext = { stepCtl?.onNext() },
        onMenu = { stepCtl?.onMenu() },
    ).also { p ->
        panel = p
        val ed = PositionEditor(this, wm) { screenSize() }
        posEditor = ed
        stepCtl = StepController(app, mainScope, p, ed, { screenSize() }, { msg -> toast(msg) })
    }

    /** Algorytm do jednorazowego testu – uruchamiany przyciskiem ▶ panelu (null = zwykłe „uruchom bota”). */
    @Volatile private var panelTestId: String? = null

    fun showBotPanel(testAlgorithmId: String? = null, testName: String? = null) {
        panelTestId = testAlgorithmId
        val p = ensurePanel()
        p.hide()
        // przyciski ⏮ ⏭ ☰: testowany algorytm albo – w zwykłym panelu BOT – jedyny zaznaczony algorytm
        val stepAlg = testAlgorithmId
            ?: app.repo.algorithms.value.filter { it.selected && it.inBot }.singleOrNull()?.id
        stepCtl?.attach(stepAlg)
        p.setIdleHint(if (testAlgorithmId != null) "Test: „${testName ?: ""}” – naciśnij ▶" else "Gotowy – naciśnij ▶")
        p.setMode(FloatingPanel.Mode.BOT)
        val st = app.engine.state.value
        p.update(st, if (st == BotState.IDLE) "" else app.engine.status.value)   // w bezczynności pokazujemy podpowiedź, nie stary status
        p.show()
    }

    /* ───────────────── Nagrywanie makr ───────────────── */

    /**
     * Krok 1: pokazuje tylko pływający przycisk „ROZPOCZNIJ NAGRYWANIE” i wraca na ekran główny.
     * Rejestracja gestów zaczyna się dopiero po jego naciśnięciu ([beginRecording]).
     */
    fun startRecording() {
        if (recView != null) return
        if (app.engine.isActive) { toast("Najpierw zatrzymaj bota"); return }
        cancelPick()
        hideActionPill()
        actionPill = FloatingAction(
            ctx = this, wm = wm, label = "● ROZPOCZNIJ NAGRYWANIE", color = "#D1453B",
            onClick = { beginRecording() },
            onCancel = { returnToApp { hideActionPill() } },
        ).also { it.show() }
        performGlobalAction(GLOBAL_ACTION_HOME)
        toast("Przejdź do właściwej aplikacji i naciśnij „ROZPOCZNIJ NAGRYWANIE”")
    }

    /** Krok 2: po naciśnięciu pływającego przycisku zaczyna się właściwe nagrywanie. */
    private fun beginRecording() {
        if (recView != null) return
        hideActionPill()
        recSteps.clear()
        lastUpTime = 0L
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val v = RecorderView(this)
        wm.addView(v, lp)
        recView = v
        recLp = lp
        // panel musi być nad nakładką nagrywania, więc dodajemy go po niej
        val p = ensurePanel()
        stepCtl?.leave()
        p.hide()
        p.setMode(FloatingPanel.Mode.RECORD)
        p.setRecordCount(0)
        p.show()
        toast("Nagrywanie rozpoczęte: wykonaj czynności, potem naciśnij ■")
    }

    fun finishRecording(save: Boolean) {
        replaying = false
        hideActionPill()
        val v = recView
        if (v != null) {
            runCatching { wm.removeView(v) }
            recView = null
            recLp = null
        }
        val p = panel
        if (p != null && v != null) {
            p.hide()
            p.setMode(FloatingPanel.Mode.BOT)
        }
        if (!save || v == null) return
        if (recSteps.isEmpty()) { toast("Nic nie nagrano"); return }
        // Nagranie zapisujemy jako zwykły algorytm z blokami KLIKNIJ / PRZESUŃ, a przerwy jako CZEKAJ.
        val blocks = gestureStepsToBlocks(recSteps.toList())
        val n = app.repo.algorithms.value.count { it.name.startsWith("Makro ") } + 1
        val alg = Algorithm(name = "Makro $n", blocks = blocks)
        app.repo.saveAlgorithm(alg)
        app.repo.log("INFO", "Nagrano makro „${alg.name}” jako algorytm (${recSteps.size} gestów, ${blocks.size} bloków)")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("algorithm", alg.id)
            )
        }
    }

    private fun addRecordedStep(down: Long, up: Long, raw: List<Pt>) {
        if (replaying) return
        // Zabezpieczenie: identyczny gest (to samo miejsce, ten sam czas trwania) zaczynający się
        // tuż po poprzednim to nasze własne odtworzenie – nie człowiek – więc go nie zapisujemy.
        val prev = recSteps.lastOrNull()
        if (prev != null && lastUpTime != 0L && prev.points.size == 1 && raw.size >= 1) {
            val gap = down - lastUpTime
            val f = raw.first()
            val p0 = prev.points.first()
            val movedLittle = raw.size < 2 || hypot(raw.last().x - f.x, raw.last().y - f.y) < 25f
            if (gap in 0..80 && movedLittle &&
                kotlin.math.abs((up - down) - prev.durationMs) <= 30 &&
                hypot(f.x - p0.x, f.y - p0.y) < 8f
            ) return
        }
        val delay = if (lastUpTime == 0L) 0L else (down - lastUpTime).coerceAtLeast(0L)
        lastUpTime = up
        val dur = (up - down).coerceAtLeast(40L)
        val first = raw.first()
        val last = raw.last()
        val pts: List<Pt> = if (raw.size < 2 || hypot(last.x - first.x, last.y - first.y) < 25f) {
            listOf(Pt(first.x, first.y, 0))               // klik / przytrzymanie
        } else {
            val maxPts = 40
            val step = (raw.size.toFloat() / maxPts).coerceAtLeast(1f)
            val out = ArrayList<Pt>()
            var idx = 0f
            while (idx < raw.size - 1) { out.add(raw[idx.toInt()]); idx += step }
            out.add(last)
            out
        }
        val step = GestureStep(delay, dur, pts)
        recSteps.add(step)
        panel?.setRecordCount(recSteps.size)
        replayLive(step)
    }

    /** Odtwarza nagrany gest od razu, żeby aplikacja pod spodem reagowała tak jak podczas normalnego użycia. */
    private fun replayLive(step: GestureStep) {
        val v = recView ?: return
        val lp = recLp ?: return
        replaying = true
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
        mainScope.launch {
            delay(80)                       // czas na zastosowanie FLAG_NOT_TOUCHABLE, zanim wstrzykniemy gest
            gesture(step.points, step.durationMs)
            delay(150)                      // ewentualne spóźnione zdarzenia dotyku też są ignorowane
            replaying = false
            if (recView === v) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(v, lp) }
            }
        }
    }

    private inner class RecorderView(ctx: Context) : View(ctx) {
        private val border = Paint().apply {
            style = Paint.Style.STROKE; color = Color.argb(200, 220, 40, 40); strokeWidth = ctx.dp(5).toFloat()
        }
        private val pts = ArrayList<Pt>()
        private var downT = 0L

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(20, 255, 0, 0))
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (replaying) { pts.clear(); return true }   // nie nagrywaj własnego, odtwarzanego gestu
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pts.clear()
                    downT = e.eventTime
                    pts.add(Pt(e.rawX, e.rawY, 0))
                }
                MotionEvent.ACTION_MOVE -> if (pts.isNotEmpty()) {
                    val l = pts.last()
                    if (hypot(e.rawX - l.x, e.rawY - l.y) > 6f) pts.add(Pt(e.rawX, e.rawY, e.eventTime - downT))
                }
                MotionEvent.ACTION_UP -> if (pts.isNotEmpty()) {
                    pts.add(Pt(e.rawX, e.rawY, e.eventTime - downT))
                    addRecordedStep(downT, e.eventTime, ArrayList(pts))
                    pts.clear()
                }
                MotionEvent.ACTION_CANCEL -> pts.clear()
            }
            return true
        }
    }

    /* ───────────────── Zrzut do tworzenia wzorca (pływający przycisk) ───────────────── */

    private fun hideActionPill() {
        actionPill?.hide()
        actionPill = null
    }

    private fun bringAppToFront(algorithmId: String? = null) {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .apply { if (algorithmId != null) putExtra("algorithm", algorithmId) }
            )
        }
    }

    /**
     * Wraca do AutoBota, a dopiero chwilę później usuwa własne okna nakładki. Dopóki nakładka jest widoczna,
     * system pozwala usłudze uruchomić aktywność z tła – gdyby okno zniknęło wcześniej, start mógłby zostać zablokowany.
     */
    private fun returnToApp(algorithmId: String? = null, cleanup: () -> Unit) {
        bringAppToFront(algorithmId)
        mainHandler.postDelayed({ cleanup() }, 350)
    }

    /** Pokazuje pływający przycisk „ZRÓB ZRZUT”. Zrzut powstaje dopiero po jego naciśnięciu. */
    fun startScreenshotButton(): Boolean {
        if (app.engine.isActive) { toast("Najpierw zatrzymaj bota"); return false }
        cancelPick()
        hideActionPill()
        actionPill = FloatingAction(
            ctx = this, wm = wm, label = "📷 ZRÓB ZRZUT", color = "#2F5BEA",
            onClick = { takePatternShot() },
            onCancel = { returnToApp { hideActionPill() } },
        ).also { it.show() }
        toast("Przejdź na właściwy ekran i naciśnij „ZRÓB ZRZUT”")
        return true
    }

    private fun takePatternShot() {
        hideActionPill()                         // przycisk nie może być widoczny na zrzucie
        mainScope.launch {
            delay(350)
            val bmp = runCatching { screenshot() }.getOrNull()
            if (bmp == null) {
                toast("Nie udało się wykonać zrzutu (ekran może być chroniony)")
                bringAppToFront()
                return@launch
            }
            PendingImage.bitmap = bmp
            runCatching {
                startActivity(
                    Intent(this@BotAccessibilityService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("crop", true)
                )
            }
        }
    }

    /* ───────────────── Pobieranie współrzędnych dotknięciem ───────────────── */

    /**
     * Pokazuje pływający przycisk „ZAREJESTRUJ…”. Po jego naciśnięciu cały ekran przechwytuje jedno
     * dotknięcie (kliknięcie lub przesunięcie) – bez przekazywania go do aplikacji pod spodem –
     * a wynik wraca do edytora bloku przez [App.coordPick].
     */
    fun startCoordinatePick(wantSwipe: Boolean): Boolean {
        if (app.engine.isActive) { toast("Najpierw zatrzymaj bota"); return false }
        cancelPick()
        hideActionPill()
        actionPill = FloatingAction(
            ctx = this, wm = wm,
            label = if (wantSwipe) "● ZAREJESTRUJ PRZESUNIĘCIE" else "● ZAREJESTRUJ KLIKNIĘCIE",
            color = "#C95A57",
            onClick = { beginPick(wantSwipe) },
            onCancel = { returnToApp { hideActionPill() } },
        ).also { it.show() }
        toast("Przejdź na właściwy ekran i naciśnij czerwony przycisk")
        return true
    }

    private fun beginPick(wantSwipe: Boolean) {
        hideActionPill()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val v = PickView(this, wantSwipe)
        wm.addView(v, lp)
        pickView = v
        // przycisk anulowania dodany po nakładce, więc leży nad nią
        actionPill = FloatingAction(
            ctx = this, wm = wm, label = "✕ ANULUJ", color = "#55657A",
            onClick = { returnToApp { cancelPick() } },
            onCancel = null,
        ).also { it.show() }
    }

    private fun cancelPick() {
        pickView?.let { runCatching { wm.removeView(it) } }
        pickView = null
        hideCoordEdit()
        if (actionPill != null) hideActionPill()
    }

    /* ───────────────── Edycja istniejącej pozycji na ekranie ───────────────── */

    private var coordEditor: PositionEditor? = null

    private fun hideCoordEdit() {
        coordEditor?.hide()
        coordEditor = null
    }

    /**
     * „EDYTUJ NA EKRANIE”: pokazuje aktualną pozycję kliknięcia (albo początek i koniec przesunięcia) jako przeciągalne
     * celowniki – tak samo jak w trybie krokowym. Zielony przycisk zapisuje wynik w edytowanym bloku (przez [App.coordPick]).
     */
    fun startCoordinateEdit(points: List<Pair<Int, Int>>, wantSwipe: Boolean): Boolean {
        if (app.engine.isActive) { toast("Najpierw zatrzymaj bota"); return false }
        if (points.isEmpty()) return false
        cancelPick()
        hideActionPill()
        stepCtl?.leave()
        val pts = points.toMutableList()
        fun caption() = if (wantSwipe) "✔ ZAPISZ PRZESUNIĘCIE" else "✔ ZAPISZ (${pts[0].first}, ${pts[0].second})"
        val ed = PositionEditor(this, wm) { screenSize() }
        coordEditor = ed
        var pill: FloatingAction? = null
        ed.show(
            pts, 0,
            onSelect = { },
            onMoved = { i, x, y ->
                if (i in pts.indices) pts[i] = x to y
                pill?.setLabel(caption())
            },
        )
        // przycisk dodany po celownikach – leży nad nimi i nie da się go przykryć
        pill = FloatingAction(
            ctx = this, wm = wm, label = caption(), color = "#2EA043",
            onClick = { finishCoordEdit(pts, wantSwipe) },
            onCancel = { returnToApp { cancelPick() } },
        ).also { it.show() }
        actionPill = pill
        toast(if (wantSwipe) "Przeciągnij celowniki 1 i 2, potem naciśnij zielony przycisk" else "Przeciągnij celownik, potem naciśnij zielony przycisk")
        return true
    }

    private fun finishCoordEdit(pts: List<Pair<Int, Int>>, wantSwipe: Boolean) {
        val a = pts[0]
        val b = if (wantSwipe && pts.size > 1) pts[1] else a
        val g = PickedGesture(
            x1 = a.first, y1 = a.second, x2 = b.first, y2 = b.second,
            durationMs = 0L, isSwipe = wantSwipe, keepDuration = true,
        )
        app.coordPick.value = g
        app.repo.log("INFO", "Edytowano pozycję na ekranie: (${g.x1}, ${g.y1})" + if (wantSwipe) " → (${g.x2}, ${g.y2})" else "")
        returnToApp { cancelPick() }
    }

    private fun finishPick(g: PickedGesture) {
        pickView?.let { runCatching { wm.removeView(it) } }
        pickView = null
        hideActionPill()
        app.coordPick.value = g
        app.repo.log("INFO", "Pobrano współrzędne z ekranu: (${g.x1}, ${g.y1})" + if (g.isSwipe) " → (${g.x2}, ${g.y2})" else "")
        bringAppToFront()
    }

    private inner class PickView(ctx: Context, private val wantSwipe: Boolean) : View(ctx) {
        private val border = Paint().apply {
            style = Paint.Style.STROKE; color = Color.argb(220, 47, 91, 234); strokeWidth = ctx.dp(5).toFloat()
        }
        private val dot = Paint().apply { color = Color.argb(230, 201, 90, 87); style = Paint.Style.FILL }
        private val line = Paint().apply { color = Color.argb(230, 201, 90, 87); strokeWidth = ctx.dp(3).toFloat() }
        private val text = Paint().apply {
            color = Color.WHITE; textSize = ctx.dp(16).toFloat(); textAlign = Paint.Align.CENTER
            isAntiAlias = true; setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        private var sx = 0f; private var sy = 0f
        private var cx = 0f; private var cy = 0f
        private var down = false
        private var downT = 0L

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(45, 47, 91, 234))
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
            canvas.drawText(
                if (wantSwipe) "Przesuń palcem od punktu startu do końca" else "Dotknij miejsca, które ma zostać kliknięte",
                width / 2f, context.dp(110).toFloat(), text
            )
            if (down) {
                if (wantSwipe) canvas.drawLine(sx, sy, cx, cy, line)
                canvas.drawCircle(sx, sy, context.dp(10).toFloat(), dot)
                if (wantSwipe) canvas.drawCircle(cx, cy, context.dp(10).toFloat(), dot)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = true; sx = e.rawX; sy = e.rawY; cx = sx; cy = sy; downT = e.eventTime; invalidate() }
                MotionEvent.ACTION_MOVE -> { cx = e.rawX; cy = e.rawY; invalidate() }
                MotionEvent.ACTION_UP -> {
                    down = false
                    val ex = e.rawX; val ey = e.rawY
                    val dur = e.eventTime - downT
                    val moved = hypot(ex - sx, ey - sy)
                    if (wantSwipe && moved < 25f) {
                        toast("To było kliknięcie – przesuń palcem od startu do końca")
                        invalidate()
                    } else {
                        finishPick(
                            PickedGesture(
                                x1 = sx.toInt(), y1 = sy.toInt(),
                                x2 = if (wantSwipe) ex.toInt() else sx.toInt(),
                                y2 = if (wantSwipe) ey.toInt() else sy.toInt(),
                                durationMs = dur.coerceAtLeast(30L), isSwipe = wantSwipe,
                            )
                        )
                    }
                }
                MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
            }
            return true
        }
    }

    /* ───────────────── Podświetlenie znalezionego wzorca ───────────────── */

    private fun flash(m: TemplateMatcher.Match) {
        val v = object : View(this) {
            private val p = Paint().apply { style = Paint.Style.STROKE; color = Color.GREEN; strokeWidth = dp(4).toFloat() }
            override fun onDraw(c: Canvas) { c.drawRect(2f, 2f, width - 2f, height - 2f, p) }
        }
        val lp = WindowManager.LayoutParams(
            m.w, m.h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = m.x; y = m.y }
        runCatching { wm.addView(v, lp) }
        mainHandler.postDelayed({ runCatching { wm.removeView(v) } }, 2500)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
