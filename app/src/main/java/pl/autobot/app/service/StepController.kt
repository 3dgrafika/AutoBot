package pl.autobot.app.service

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.autobot.app.App
import pl.autobot.app.data.*
import pl.autobot.app.engine.BotState

/**
 * Tryb krokowy pływającego panelu – przechodzenie przez algorytm „po jednym kroku”.
 *
 * Model: algorytm jest „spłaszczony” do listy kroków (każdy blok, także kontener, to jeden krok; zawartość
 * kontenera występuje tuż po nim). Kursor wskazuje krok, który zostanie wykonany jako następny:
 *  • ⏭ wykonuje krok wskazany kursorem i przesuwa kursor dalej (jeśli krok się udał),
 *  • ⏮ cofa kursor o jeden krok bez wykonywania (żeby np. powtórzyć krok po korekcie),
 *  • ☰ otwiera menu: korekta pozycji i czasu, dodanie następnego kroku, usunięcie aktualnego.
 *
 * Pierwsze naciśnięcie ⏮ / ⏭ / ☰ tylko włącza tryb krokowy (pokazuje bieżący krok). Jeśli w tym momencie trwa
 * zwykły test (▶), zostaje wstrzymany, a kursor ustawia się na kroku, na którym stanął.
 * Zmiany (korekta, dodanie, usunięcie) są od razu zapisywane w algorytmie.
 */
class StepController(
    private val app: App,
    private val scope: CoroutineScope,
    private val panel: FloatingPanel,
    private val editor: PositionEditor,
    private val screen: () -> Pair<Int, Int>,
    private val toast: (String) -> Unit,
) {
    private enum class Sheet { NONE, MAIN, CORRECTION, OTHER }

    private val main = Handler(Looper.getMainLooper())

    private var algId: String? = null
    private var cursorPath: List<Int>? = null     // null = za ostatnim krokiem (koniec algorytmu)
    private var active = false                    // tryb krokowy włączony
    private var busy = false                      // trwa wykonywanie kroku
    private var editPoint = 0                     // który punkt przesunięcia poruszają strzałki (0 = początek, 1 = koniec)
    private var nudgeStep = 5                     // skok strzałek w pikselach
    private var sheet = Sheet.NONE

    /* ───────── podłączenie do algorytmu ───────── */

    /** Wywoływane przy pokazaniu panelu: [id] to algorytm do przejścia krokowego (null = bez przycisków kroków). */
    fun attach(id: String?) {
        leave()
        algId = id
        val alg = currentAlg()
        cursorPath = alg?.let { flattenBlocks(it.blocks).firstOrNull()?.path }
        panel.setStepAvailable(id != null && alg != null)
    }

    /** ▶ w trybie krokowym kontynuuje od kursora zamiast resetować algorytm. */
    fun continueFromCurrent(): Boolean {
        if (!active || algId == null) return false
        scope.launch {
            if (busy) { toast("Trwa wykonywanie kroku"); return@launch }
            val list = steps() ?: return@launch
            val i = cursorIndex(list)
            if (i >= list.size) { toast("Algorytm zakończony"); return@launch }
            execute(list, i)
        }
        return true
    }

    /** Wyłącza tryb krokowy (znikają uchwyty i menu); kursor zostaje zapamiętany. */
    fun leave() {
        active = false
        sheet = Sheet.NONE
        editor.hide()
        panel.hideSheet()
        panel.setStepInfo(null)
    }

    /* ───────── przyciski panelu ───────── */

    fun onNext() {
        scope.launch {
            if (busy) { toast("Trwa wykonywanie kroku"); return@launch }
            if (!active) { if (enter()) refresh(); return@launch }
            val steps = steps() ?: return@launch
            val i = cursorIndex(steps)
            if (i >= steps.size) { toast("To był ostatni krok – ⏮ cofa kursor"); return@launch }
            execute(steps, i)
        }
    }

    fun onPrev() {
        scope.launch {
            if (busy) { toast("Trwa wykonywanie kroku"); return@launch }
            if (!active) { if (enter()) refresh(); return@launch }
            val steps = steps() ?: return@launch
            val i = cursorIndex(steps)
            if (i <= 0) { toast("To pierwszy krok"); return@launch }
            cursorPath = steps[i - 1].path
            editPoint = 0
            refresh()
            reopenSheetIfNeeded()
        }
    }

    fun onMenu() {
        scope.launch {
            if (busy) { toast("Trwa wykonywanie kroku"); return@launch }
            if (panel.sheetShown) { sheet = Sheet.NONE; panel.hideSheet(); return@launch }
            if (!active && !enter()) return@launch
            refresh()
            showMainMenu()
        }
    }

    /** Włącza tryb krokowy; jeśli trwa zwykły test, wstrzymuje go i ustawia kursor w miejscu, w którym stanął. */
    private suspend fun enter(): Boolean {
        val id = algId ?: return false
        if (currentAlg() == null) { toast("Algorytm został usunięty"); return false }
        val e = app.engine
        if (e.state.value != BotState.IDLE) {
            if (e.state.value == BotState.RUNNING) e.pause()
            delay(200)                                   // pauza zaczyna działać w najbliższym punkcie kontrolnym
            val pos = e.currentPos.value
            e.stopAndJoin()
            if (pos != null && pos.first == id && currentAlg()?.blocks?.blockAt(pos.second) != null) {
                cursorPath = pos.second
                toast("Test wstrzymany – kursor na kroku, w którym stanął")
            }
        }
        active = true
        editPoint = 0
        return true
    }

    /* ───────── wykonywanie kroku ───────── */

    private fun execute(steps: List<FlatStep>, i: Int) {
        val st = steps[i]
        val nextPath = steps.getOrNull(i + 1)?.path
        editor.hide()               // uchwyt leży dokładnie w miejscu kliknięcia – przechwyciłby dotyk bota
        panel.hideSheet()
        sheet = Sheet.NONE
        busy = true
        val started = app.engine.runStep(st.block, "krok ${i + 1}/${steps.size}") { ok ->
            main.post {
                busy = false
                if (ok) cursorPath = nextPath
                editPoint = 0
                refresh()
            }
        }
        if (!started) {
            busy = false
            toast("Poczekaj chwilę i spróbuj ponownie")
            refresh()
        }
    }

    /* ───────── widok: kursor, uchwyty, opis ───────── */

    private fun currentAlg(): Algorithm? = algId?.let { id -> app.repo.algorithms.value.firstOrNull { it.id == id } }

    private fun steps(): List<FlatStep>? = currentAlg()?.let { flattenBlocks(it.blocks) }

    private fun cursorIndex(steps: List<FlatStep>): Int {
        val p = cursorPath ?: return steps.size
        val i = steps.indexOfFirst { it.path == p }
        return if (i < 0) steps.size else i
    }

    private fun infoText(steps: List<FlatStep>, i: Int): String {
        val cur = steps.getOrNull(i)
        return when {
            steps.isEmpty() -> "Algorytm jest pusty\n☰ → dodaj pierwszy krok"
            cur == null -> "Koniec algorytmu ✓ (${steps.size} kroków)\n⏮ cofnij · ☰ menu"
            else -> "Następny: krok ${i + 1}/${steps.size}\n${describe(cur.block)}\n⏭ wykonaj · ⏮ cofnij · ☰ menu"
        }
    }

    /** Pokazuje opis kroku i (dla kliknięcia / przesunięcia) przeciągalne uchwyty w miejscu kliknięcia. */
    private fun refresh() {
        if (!active) return
        val steps = steps()
        if (steps == null) {
            editor.hide()
            panel.setStepInfo("Algorytm został usunięty")
            return
        }
        val i = cursorIndex(steps)
        panel.setStepInfo(infoText(steps, i))
        editor.hide()
        when (val b = steps.getOrNull(i)?.block) {
            is TapBlock -> showHandles(listOf(b.x to b.y))
            is SwipeBlock -> showHandles(listOf(b.x1 to b.y1, b.x2 to b.y2))
            else -> Unit
        }
    }

    private fun refreshInfoOnly() {
        val steps = steps() ?: return
        panel.setStepInfo(infoText(steps, cursorIndex(steps)))
    }

    private fun showHandles(points: List<Pair<Int, Int>>) {
        editPoint = editPoint.coerceIn(0, points.size - 1)
        editor.show(
            points, editPoint,
            onSelect = { idx ->
                editPoint = idx
                editor.setSelected(idx)
                if (sheet == Sheet.CORRECTION) showCorrection()
            },
            onMoved = { idx, x, y -> applyPoint(idx, x, y) },
        )
        panel.bringToFront()          // panel ma być nad uchwytami, żeby nigdy nie został przykryty
    }

    private fun reopenSheetIfNeeded() {
        if (sheet == Sheet.CORRECTION) showCorrection()
        else if (sheet == Sheet.MAIN) showMainMenu()
    }

    /* ───────── edycja algorytmu ───────── */

    private fun saveBlocks(blocks: List<Block>) {
        val alg = currentAlg() ?: return
        app.repo.saveAlgorithm(alg.copy(blocks = blocks))
    }

    /** Nowa pozycja uchwytu (po przeciągnięciu) → zapis w bloku. */
    private fun applyPoint(idx: Int, x: Int, y: Int) {
        val path = cursorPath ?: return
        val alg = currentAlg() ?: return
        val nb: Block = when (val b = alg.blocks.blockAt(path)) {
            is TapBlock -> b.copy(x = x, y = y)
            is SwipeBlock -> if (idx == 0) b.copy(x1 = x, y1 = y) else b.copy(x2 = x, y2 = y)
            else -> return
        }
        saveBlocks(alg.blocks.replaceAt(path, nb))
        refreshInfoOnly()
        if (sheet == Sheet.CORRECTION) showCorrection()
    }

    /** Strzałki: przesuwa wybrany punkt o [dx], [dy] pikseli. */
    private fun nudge(dx: Int, dy: Int) {
        val path = cursorPath ?: return
        val alg = currentAlg() ?: return
        val (w, h) = screen()
        fun cx(v: Int) = v.coerceIn(0, w - 1)
        fun cy(v: Int) = v.coerceIn(0, h - 1)
        val nb: Block
        val shownIdx: Int
        val nx: Int
        val ny: Int
        when (val b = alg.blocks.blockAt(path)) {
            is TapBlock -> {
                nx = cx(b.x + dx); ny = cy(b.y + dy); shownIdx = 0
                nb = b.copy(x = nx, y = ny)
            }
            is SwipeBlock -> {
                if (editPoint == 0) {
                    nx = cx(b.x1 + dx); ny = cy(b.y1 + dy); shownIdx = 0
                    nb = b.copy(x1 = nx, y1 = ny)
                } else {
                    nx = cx(b.x2 + dx); ny = cy(b.y2 + dy); shownIdx = 1
                    nb = b.copy(x2 = nx, y2 = ny)
                }
            }
            else -> return
        }
        saveBlocks(alg.blocks.replaceAt(path, nb))
        editor.setPoint(shownIdx, nx, ny)
        refreshInfoOnly()
        showCorrection()
    }

    /** Czas między kliknięciami: zmienia blok CZEKAJ stojący tuż przed krokiem (tworzy go lub usuwa, gdy zejdzie do zera). */
    private fun adjustGapBefore(delta: Long) {
        val path = cursorPath ?: return
        val alg = currentAlg() ?: return
        var newPath = path
        val blocks = alg.blocks.editSiblings(path) { l, i ->
            val m = l.toMutableList()
            val prev = m.getOrNull(i - 1) as? WaitBlock
            if (prev != null) {
                val nv = prev.ms + delta
                if (nv <= 0L) {
                    m.removeAt(i - 1)
                    newPath = path.dropLast(1) + (path.last() - 1)
                } else {
                    m[i - 1] = prev.copy(ms = nv)
                }
            } else if (delta > 0L) {
                m.add(i, WaitBlock(delta))
                newPath = path.dropLast(1) + (path.last() + 1)
            }
            m
        }
        saveBlocks(blocks)
        cursorPath = newPath
        refresh()
        showCorrection()
    }

    private fun gapBefore(path: List<Int>): Long {
        val alg = currentAlg() ?: return 0L
        var gap = 0L
        alg.blocks.editSiblings(path) { l, i ->
            gap = (l.getOrNull(i - 1) as? WaitBlock)?.ms ?: 0L
            l
        }
        return gap
    }

    /** Zmiana własnego czasu kroku: CZEKAJ (czas) albo ZNAJDŹ I KLIKNIJ (maks. czas szukania). */
    private fun adjustOwnTime(delta: Long) {
        val path = cursorPath ?: return
        val alg = currentAlg() ?: return
        val nb: Block = when (val b = alg.blocks.blockAt(path)) {
            is WaitBlock -> b.copy(ms = (b.ms + delta).coerceAtLeast(0L))
            is FindClickBlock -> b.copy(timeoutMs = (b.timeoutMs + delta).coerceAtLeast(500L))
            else -> return
        }
        saveBlocks(alg.blocks.replaceAt(path, nb))
        refreshInfoOnly()
        showCorrection()
    }

    private fun addBlock(nb: Block) {
        val alg = currentAlg() ?: return
        val path = cursorPath
        val cur = path?.let { alg.blocks.blockAt(it) }
        val blocks: List<Block>
        val newPath: List<Int>
        if (path == null || cur == null) {
            blocks = alg.blocks + nb
            newPath = listOf(alg.blocks.size)
        } else {
            blocks = alg.blocks.insertAfterStep(path, nb)
            newPath = if (cur.branchCount() > 0) path + listOf(0, 0) else path.dropLast(1) + (path.last() + 1)
        }
        saveBlocks(blocks)
        cursorPath = newPath
        editPoint = 0
        sheet = Sheet.NONE
        panel.hideSheet()
        refresh()
        when (nb) {
            is TapBlock -> toast("Dodano kliknięcie – przeciągnij znacznik w docelowe miejsce")
            is SwipeBlock -> toast("Dodano przesunięcie – przeciągnij znaczniki 1 i 2")
            is WaitBlock -> toast("Dodano czekanie – ☰ → Koryguj, aby zmienić czas")
            else -> toast("Dodano krok")
        }
    }

    private fun deleteCurrent() {
        val alg = currentAlg() ?: return
        val steps = flattenBlocks(alg.blocks)
        val i = cursorIndex(steps)
        val cur = steps.getOrNull(i) ?: return
        val blocks = alg.blocks.removeAtPath(cur.path)
        saveBlocks(blocks)
        val after = flattenBlocks(blocks)
        cursorPath = after.getOrNull(i)?.path ?: after.lastOrNull()?.path
        editPoint = 0
        sheet = Sheet.NONE
        panel.hideSheet()
        refresh()
        toast("Usunięto krok")
    }

    /* ───────── menu w panelu ───────── */

    private fun showMainMenu() {
        val steps = steps() ?: return
        val i = cursorIndex(steps)
        val cur = steps.getOrNull(i)
        val rows = ArrayList<SheetRow>()
        rows.add(SheetRow.Title(if (cur != null) "Krok ${i + 1}/${steps.size}: ${describe(cur.block)}" else "Menu kroków"))
        if (cur != null && isCorrectable(cur.block)) {
            rows.add(SheetRow.Btn("✎  Koryguj pozycję i czas", "#2F5BEA") { showCorrection() })
        }
        rows.add(SheetRow.Btn("＋  Dodaj następny krok", "#2EA043") { showAddMenu() })
        if (cur != null) rows.add(SheetRow.Btn("🗑  Usuń aktualny krok", "#D1453B") { showDeleteConfirm() })
        if (steps.isNotEmpty()) rows.add(SheetRow.Btn("↺  Wróć do pierwszego kroku", "#3A4A5E") {
            cursorPath = steps.first().path
            editPoint = 0
            sheet = Sheet.NONE
            panel.hideSheet()
            refresh()
        })
        rows.add(SheetRow.Btn("Zakończ tryb krokowy", "#55657A") { leave() })
        rows.add(SheetRow.Btn("Zamknij menu", "#55657A") { sheet = Sheet.NONE; panel.hideSheet() })
        sheet = Sheet.MAIN
        panel.showSheet(rows)
    }

    private fun showAddMenu() {
        val (w, h) = screen()
        val rows = ArrayList<SheetRow>()
        rows.add(SheetRow.Title("＋ Dodaj następny krok"))
        rows.add(SheetRow.Line("Nowy krok trafi tuż po aktualnym."))
        rows.add(SheetRow.Btn("KLIKNIJ") { addBlock(TapBlock(x = w / 2, y = h / 2, durationMs = 50)) })
        rows.add(SheetRow.Btn("PRZESUŃ") { addBlock(SwipeBlock(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 400)) })
        rows.add(SheetRow.Btn("CZEKAJ 1 s") { addBlock(WaitBlock(1000)) })
        rows.add(SheetRow.Btn("ZNAJDŹ WZORZEC I KLIKNIJ…") { showPatternPicker() })
        rows.add(SheetRow.Line("Pętle, warunki i inne bloki dodasz w edytorze algorytmu."))
        rows.add(SheetRow.Btn("‹  Wróć", "#55657A") { showMainMenu() })
        sheet = Sheet.OTHER
        panel.showSheet(rows)
    }

    private fun showPatternPicker() {
        val pats = app.repo.patterns.value
        val rows = ArrayList<SheetRow>()
        rows.add(SheetRow.Title("Wybierz wzorzec do kliknięcia"))
        if (pats.isEmpty()) rows.add(SheetRow.Line("Brak wzorców – utwórz je w zakładce WZORCE."))
        for (p in pats) {
            rows.add(SheetRow.Btn(p.name) { addBlock(FindClickBlock(patternId = p.id)) })
        }
        rows.add(SheetRow.Btn("‹  Wróć", "#55657A") { showAddMenu() })
        sheet = Sheet.OTHER
        panel.showSheet(rows)
    }

    private fun showDeleteConfirm() {
        val steps = steps() ?: return
        val i = cursorIndex(steps)
        val cur = steps.getOrNull(i) ?: return
        val rows = ArrayList<SheetRow>()
        rows.add(SheetRow.Title("Usunąć krok ${i + 1}/${steps.size}?"))
        rows.add(SheetRow.Line(describe(cur.block)))
        if (cur.block.branchCount() > 0) rows.add(SheetRow.Line("⚠ Zniknie też cała zawartość tego bloku."))
        rows.add(SheetRow.Group(listOf(
            SheetRow.Btn("Tak, usuń", "#D1453B") { deleteCurrent() },
            SheetRow.Btn("Anuluj", "#55657A") { showMainMenu() },
        )))
        sheet = Sheet.OTHER
        panel.showSheet(rows)
    }

    private fun isCorrectable(b: Block): Boolean = b is TapBlock || b is SwipeBlock || b is WaitBlock || b is FindClickBlock

    private fun timeGroup(onDelta: (Long) -> Unit) = SheetRow.Group(listOf(
        SheetRow.Btn("−1 s") { onDelta(-1000L) },
        SheetRow.Btn("−100") { onDelta(-100L) },
        SheetRow.Btn("+100") { onDelta(100L) },
        SheetRow.Btn("+1 s") { onDelta(1000L) },
    ))

    /** Korekta pozycji i czasu bieżącego kroku (odświeżana po każdej zmianie). */
    private fun showCorrection() {
        val steps = steps() ?: return
        val i = cursorIndex(steps)
        val cur = steps.getOrNull(i) ?: return
        val path = cur.path
        val rows = ArrayList<SheetRow>()
        rows.add(SheetRow.Title("✎ Krok ${i + 1}/${steps.size}: ${describe(cur.block)}"))
        val b = cur.block
        if (b is TapBlock || b is SwipeBlock) {
            if (b is SwipeBlock) {
                rows.add(SheetRow.Line("Punkty: 1 = (${b.x1}, ${b.y1}), 2 = (${b.x2}, ${b.y2})"))
                rows.add(SheetRow.Group(listOf(
                    SheetRow.Btn(if (editPoint == 0) "● Punkt 1" else "Punkt 1", if (editPoint == 0) "#FF9800" else "#3A4A5E") { selectPoint(0) },
                    SheetRow.Btn(if (editPoint == 1) "● Punkt 2" else "Punkt 2", if (editPoint == 1) "#FF9800" else "#3A4A5E") { selectPoint(1) },
                )))
            } else if (b is TapBlock) {
                rows.add(SheetRow.Line("Pozycja: (${b.x}, ${b.y})"))
            }
            rows.add(SheetRow.Line("Przeciągnij celownik na ekranie albo użyj strzałek (skok w px):"))
            rows.add(SheetRow.Group(listOf(1, 5, 20).map { n ->
                SheetRow.Btn(if (n == nudgeStep) "● $n" else "$n", if (n == nudgeStep) "#2F5BEA" else "#3A4A5E") { nudgeStep = n; showCorrection() }
            }))
            rows.add(SheetRow.Group(listOf(
                SheetRow.Btn("◀") { nudge(-nudgeStep, 0) },
                SheetRow.Btn("▲") { nudge(0, -nudgeStep) },
                SheetRow.Btn("▼") { nudge(0, nudgeStep) },
                SheetRow.Btn("▶") { nudge(nudgeStep, 0) },
            )))
        }
        when (b) {
            is WaitBlock -> {
                rows.add(SheetRow.Line("Czas oczekiwania: ${b.ms} ms"))
                rows.add(timeGroup { adjustOwnTime(it) })
            }
            is FindClickBlock -> {
                rows.add(SheetRow.Line("Maks. czas szukania wzorca: ${b.timeoutMs} ms"))
                rows.add(timeGroup { adjustOwnTime(it) })
            }
            else -> Unit
        }
        if (b is TapBlock || b is SwipeBlock || b is FindClickBlock) {
            rows.add(SheetRow.Line("Przerwa przed tym krokiem (po poprzednim kliknięciu): ${gapBefore(path)} ms"))
            rows.add(timeGroup { adjustGapBefore(it) })
        }
        rows.add(SheetRow.Group(listOf(
            SheetRow.Btn("✔ Gotowe", "#2EA043") { sheet = Sheet.NONE; panel.hideSheet() },
            SheetRow.Btn("‹ Menu", "#55657A") { showMainMenu() },
        )))
        sheet = Sheet.CORRECTION
        panel.showSheet(rows)
    }

    private fun selectPoint(idx: Int) {
        editPoint = idx
        editor.setSelected(idx)
        showCorrection()
    }

    /* ───────── opisy ───────── */

    private fun patternName(id: String?): String =
        id?.let { pid -> app.repo.patterns.value.firstOrNull { it.id == pid }?.name } ?: "(nie wybrano)"

    private fun unitName(u: RepeatUnit): String = when (u) {
        RepeatUnit.MINUTES -> "min"
        RepeatUnit.HOURS -> "godz."
        RepeatUnit.DAYS -> "dni"
        RepeatUnit.WEEKS -> "tyg."
    }

    private fun describe(b: Block): String = when (b) {
        is TapBlock -> "KLIKNIJ (${b.x}, ${b.y})"
        is SwipeBlock -> "PRZESUŃ (${b.x1}, ${b.y1}) → (${b.x2}, ${b.y2})"
        is WaitBlock -> "CZEKAJ ${b.ms} ms"
        is FindClickBlock -> "ZNAJDŹ I KLIKNIJ „${patternName(b.patternId)}”"
        is IfPatternBlock -> "JEŻELI „${patternName(b.patternId)}” ${if (b.negate) "nie " else ""}istnieje"
        is LoopBlock -> when (b.mode) {
            LoopMode.COUNT -> "PĘTLA ${b.count}×"
            LoopMode.UNTIL_FOUND -> "PĘTLA do pojawienia się wzorca"
            LoopMode.UNTIL_MISSING -> "PĘTLA do zniknięcia wzorca"
        }
        is AlgorithmBlock -> "ALGORYTM „${app.repo.algorithms.value.firstOrNull { it.id == b.algorithmId }?.name ?: "?"}” ×${b.repeat}"
        is ForEachPatternBlock -> "DLA KAŻDEGO WZORCA (${b.patternIds.size})"
        is AtTimeBlock -> "O OKREŚLONEJ DACIE I GODZINIE"
        is RepeatEveryBlock -> "POWTARZAJ CO ${b.every} ${unitName(b.unit)}"
        is BreakBlock -> "PRZERWIJ PĘTLĘ"
        is ContinueBlock -> "POMIŃ ITERACJĘ"
        is EndAlgorithmBlock -> "PRZERWIJ AKTUALNY ALGORYTM"
        is StopBotBlock -> "ZATRZYMAJ BOTA"
    }
}
