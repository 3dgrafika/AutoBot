package pl.autobot.app.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.autobot.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class BotError(message: String) : Exception(message)
class ScreenshotException(val code: Int) : Exception("Błąd zrzutu ekranu (kod $code)")

/** Abstrakcja urządzenia – implementuje ją usługa dostępności. */
interface DeviceIO {
    suspend fun gesture(points: List<Pt>, durationMs: Long): Boolean
    suspend fun screenshot(): Bitmap
}

/** Rysowanie na ekranie miejsc kliknięć w teście – implementuje ją usługa dostępności. */
interface ClickMarkers {
    fun show(points: List<Pt>, label: String)

    /** Chowa znaczniki; zwraca true, jeśli były widoczne (trzeba chwilę poczekać, aż znikną – żeby nie trafiły na zrzut). */
    fun clear(): Boolean
}

enum class BotState { IDLE, RUNNING, PAUSED }

private enum class SignalKind { BREAK, CONTINUE, END_ALGORITHM, STOP_BOT }

/** Sygnał sterowania przebiegiem (bez śladu stosu – tani). */
private class ControlSignal(val kind: SignalKind) : RuntimeException(null, null, false, false)

private class FindResult(val info: PatternInfo, val threshold: Float, val matches: List<TemplateMatcher.Match>)
private class AnyHit(val info: PatternInfo, val match: TemplateMatcher.Match, val threshold: Float)

private fun fmtDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private fun fmtDur(ms: Long): String {
    var s = ms / 1000
    val d = s / 86_400; s %= 86_400
    val h = s / 3600; s %= 3600
    val m = s / 60; s %= 60
    return buildString {
        if (d > 0) append("$d d ")
        if (d > 0 || h > 0) append("$h h ")
        if (d > 0 || h > 0 || m > 0) append("$m min ")
        if (d == 0L && h == 0L) append("$s s")
    }.trim()
}

/**
 * Interpreter algorytmów blokowych.
 *
 * Zabezpieczenia: limit czasu działania, limit liczby akcji, limit iteracji pętli,
 * limit czasu pojedynczego oczekiwania, minimalna przerwa w każdej iteracji pętli,
 * natychmiastowe zatrzymanie (anulowanie korutyny) i pełne logowanie przyczyn zatrzymania.
 */
class BotEngine(
    private val repo: Repository,
    private val deviceProvider: () -> DeviceIO?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val paused = MutableStateFlow(false)

    val state = MutableStateFlow(BotState.IDLE)
    val status = MutableStateFlow("")

    /** Ustawiane przez usługę – rysuje ramkę wokół znalezionego wzorca (test wzorca). */
    @Volatile
    var highlighter: ((TemplateMatcher.Match) -> Unit)? = null

    /** Ustawiane przez usługę – pokazuje na ekranie miejsca kliknięć (tylko w teście). */
    @Volatile
    var markers: ClickMarkers? = null

    /** Aktualnie wykonywany blok najwyższego algorytmu: (id algorytmu, ścieżka bloku). Null, gdy nic nie działa. */
    val currentPos = MutableStateFlow<Pair<String, List<Int>>?>(null)

    @Volatile private var testMode = false          // true = TESTUJ / tryb krokowy (wtedy pokazujemy znaczniki)
    @Volatile private var resume: List<Int>? = null // ścieżka kroku, od którego algorytm ma kontynuować (null = od początku)
    @Volatile private var stepLabel: String? = null // opis kroku pokazywany przy znaczniku w trybie krokowym
    private var trackId: String? = null             // algorytm, którego pozycję śledzimy w [currentPos]

    private lateinit var io: DeviceIO
    private var startedAt = 0L
    private var actions = 0
    private var lastShot = 0L
    private val grayCache = HashMap<String, Gray>()
    private val algStack = ArrayList<String>()     // aktualnie wykonywane algorytmy (wykrywanie rekurencji)

    val isActive: Boolean get() = job?.isCompleted == false

    /* ───────── API ───────── */

    fun startSelected() {
        val ids = repo.algorithms.value.filter { it.selected && it.inBot }.map { it.id }
        if (ids.isEmpty()) {
            repo.log("WARN", "Nie zaznaczono żadnego algorytmu")
            return
        }
        launch("zestaw algorytmów (${ids.size})", repo.settings.value.startDelaySec) { runAlgorithms(ids) }
    }

    /**
     * Jednorazowe uruchomienie wybranego algorytmu (test z pływającego panelu) – od razu, bez odliczania.
     * [startPath] (ścieżka bloku, patrz StepTree.kt) = kontynuacja od tego kroku zamiast od początku;
     * bloki wcześniejsze są pomijane, a kontenery (pętla, JEŻELI…) zawierające ten krok są wchodzone bez sprawdzania warunku.
     */
    fun runAlgorithmOnce(algorithmId: String, startPath: List<Int>? = null) {
        val a = repo.algorithms.value.firstOrNull { it.id == algorithmId } ?: return
        val title = if (startPath == null) "test algorytmu „${a.name}”" else "test algorytmu „${a.name}” – kontynuacja od wskazanego kroku"
        launch(title, 0, test = true) {
            resume = startPath
            try {
                runAlgorithms(listOf(a.id))
            } finally {
                resume = null
            }
        }
    }

    /**
     * Tryb krokowy: wykonuje dokładnie jeden blok (bez odliczania). Kontenery (pętla, JEŻELI…) nie są
     * wykonywane – ich zawartość jest przechodzona krok po kroku jako osobne bloki.
     * [onEnd] dostaje true, gdy krok zakończył się powodzeniem. Zwraca false, gdy nie dało się go uruchomić.
     */
    fun runStep(block: Block, label: String, onEnd: (Boolean) -> Unit): Boolean =
        launch("krok", 0, test = true, quiet = true, stepName = label, onEnd = onEnd) { execStepBlock(block) }

    fun testPattern(patternId: String) {
        launch("test wzorca", maxOf(4, repo.settings.value.startDelaySec)) {
            val r = find(patternId, null, minScore = 0.5f, max = 1)
            val best = r.matches.firstOrNull()
            if (best == null) {
                repo.log("WARN", "Test „${r.info.name}”: brak dopasowania powyżej 50%")
            } else {
                val ok = best.score >= r.threshold
                repo.log(
                    if (ok) "INFO" else "WARN",
                    "Test „${r.info.name}”: najlepsze dopasowanie ${pct(best.score)} w (${best.cx}, ${best.cy}); " +
                        "wymagane ${pct(r.threshold)} → ${if (ok) "ZNALEZIONO" else "za niska dokładność"}"
                )
                highlighter?.invoke(best)
            }
        }
    }

    fun pause() {
        if (state.value == BotState.RUNNING) {
            paused.value = true
            state.value = BotState.PAUSED
        }
    }

    fun resume() {
        if (state.value == BotState.PAUSED) paused.value = false
    }

    fun stop() {
        paused.value = false
        job?.cancel()
    }

    /** Zatrzymuje bota i czeka, aż praca naprawdę się zakończy (potrzebne przed uruchomieniem pojedynczego kroku). */
    suspend fun stopAndJoin() {
        stop()
        job?.join()
    }

    /* ───────── uruchamianie ───────── */

    private fun launch(
        title: String,
        countdownSec: Int,
        test: Boolean = false,
        quiet: Boolean = false,
        stepName: String? = null,
        onEnd: ((Boolean) -> Unit)? = null,
        body: suspend () -> Unit,
    ): Boolean {
        if (isActive) return false
        val device = deviceProvider()
        if (device == null) {
            repo.log("ERROR", "Usługa dostępności jest wyłączona – nie można uruchomić bota")
            return false
        }
        io = device
        grayCache.clear()
        algStack.clear()
        actions = 0
        paused.value = false
        testMode = test
        stepLabel = stepName
        trackId = null
        resume = null
        currentPos.value = null
        state.value = BotState.RUNNING
        job = scope.launch {
            var ok = false
            try {
                for (i in countdownSec downTo 1) {
                    status.value = "Start za $i s…"
                    delay(1000)
                }
                startedAt = System.currentTimeMillis()
                if (!quiet) repo.log("INFO", "▶ START: $title")
                body()
                ok = true
                if (!quiet) repo.log("INFO", "✔ Zakończono: $title (wykonane akcje: $actions)")
            } catch (s: ControlSignal) {
                if (s.kind == SignalKind.STOP_BOT) repo.log("WARN", "■ Zatrzymano blokiem ZATRZYMAJ BOTA")
                else repo.log("INFO", "Zakończono: $title")
            } catch (e: CancellationException) {
                repo.log("WARN", "■ Zatrzymano przez użytkownika")
                throw e
            } catch (e: BotError) {
                repo.log("ERROR", "✖ ${e.message} — bot zatrzymany")
            } catch (e: Throwable) {
                repo.log("ERROR", "✖ Nieoczekiwany błąd: ${e::class.simpleName}: ${e.message}")
            } finally {
                state.value = BotState.IDLE
                status.value = ""
                currentPos.value = null
                onEnd?.invoke(ok)
            }
        }
        return true
    }

    private suspend fun runAlgorithms(ids: List<String>) {
        for ((i, id) in ids.withIndex()) {
            val alg = repo.algorithms.value.firstOrNull { it.id == id } ?: continue
            repo.log("INFO", "Algorytm ${i + 1}/${ids.size}: ${alg.name}")
            trackId = alg.id
            runAlgorithm(alg, true)
        }
    }

    /** Wykonuje algorytm; PRZERWIJ ALGORYTM / zbłąkane PRZERWIJ PĘTLĘ kończą tylko ten algorytm. */
    private suspend fun runAlgorithm(alg: Algorithm, track: Boolean) {
        algStack.add(alg.id)
        try {
            execBlocks(alg.blocks, if (track) emptyList() else null)
        } catch (s: ControlSignal) {
            when (s.kind) {
                SignalKind.STOP_BOT -> throw s
                SignalKind.END_ALGORITHM -> repo.log("INFO", "Algorytm „${alg.name}” przerwany – przechodzę dalej")
                else -> repo.log("WARN", "PRZERWIJ PĘTLĘ / POMIŃ ITERACJĘ użyte poza pętlą w „${alg.name}”")
            }
        } finally {
            algStack.removeAt(algStack.lastIndex)
        }
    }

    /* ───────── wykonywanie bloków ───────── */

    /** [path] = ścieżka listy (do śledzenia pozycji w [currentPos]); null = nie śledzimy (podalgorytm, tryb krokowy). */
    private suspend fun execBlocks(blocks: List<Block>, path: List<Int>? = null) {
        var start = 0
        val r = resume
        // kontynuacja od kroku: na liście leżącej na ścieżce do kroku pomijamy bloki stojące przed nim
        if (path != null && r != null && r.size > path.size && r.subList(0, path.size) == path) {
            start = r[path.size].coerceIn(0, blocks.size)
            if (r.size == path.size + 1) resume = null     // dotarliśmy do samego kroku
        }
        for (i in start until blocks.size) execBlock(blocks[i], path?.plus(i))
    }

    /** Czy kontener o ścieżce [path] zawiera krok, od którego kontynuujemy (wchodzimy do niego bez sprawdzania warunku). */
    private fun onResumePath(path: List<Int>?): Boolean {
        val r = resume ?: return false
        return path != null && r.size > path.size && r.subList(0, path.size) == path
    }

    /** Jedno wykonanie zawartości kontenera; false = PRZERWIJ PĘTLĘ. */
    private suspend fun runBodyOnce(body: List<Block>, path: List<Int>?): Boolean {
        try {
            execBlocks(body, path?.plus(0))
            return true
        } catch (sig: ControlSignal) {
            return when (sig.kind) {
                SignalKind.BREAK -> false
                SignalKind.CONTINUE -> true
                else -> throw sig
            }
        }
    }

    private suspend fun execBlock(b: Block, path: List<Int>? = null) {
        if (path != null) trackId?.let { currentPos.value = it to path }
        checkpoint()
        when (b) {
            is TapBlock -> {
                act("Klik (${b.x}, ${b.y})")
                doGesture("KLIKNIJ", listOf(Pt(b.x.toFloat(), b.y.toFloat())), b.durationMs)
            }
            is SwipeBlock -> {
                act("Przesunięcie (${b.x1},${b.y1}) → (${b.x2},${b.y2})")
                doGesture("PRZESUŃ", listOf(Pt(b.x1.toFloat(), b.y1.toFloat()), Pt(b.x2.toFloat(), b.y2.toFloat())), b.durationMs)
            }
            is WaitBlock -> {
                val s = repo.settings.value
                var ms = b.ms
                if (s.limitWaitEnabled && ms > s.maxWaitMs) {
                    repo.log("WARN", "Czekaj ${b.ms} ms skrócone do limitu ${s.maxWaitMs} ms")
                    ms = s.maxWaitMs
                }
                val extra = extraDelay()
                val total = ms + extra
                repo.log("INFO", "CZEKAJ: ${ms} ms" + (if (extra > 0) " + losowo $extra ms" else "") + " = $total ms")
                status.value = "Czekam $total ms"
                sleep(total)
            }
            is FindClickBlock -> execFindClick(b)
            is IfPatternBlock -> {
                val r = resume
                if (path != null && r != null && onResumePath(path)) {
                    val br = r[path.size]
                    execBlocks(if (br == 0) b.thenBlocks else b.elseBlocks, path.plus(br))
                } else {
                    val cond = evalIf(b)
                    execBlocks(if (cond) b.thenBlocks else b.elseBlocks, path?.plus(if (cond) 0 else 1))
                }
            }
            is LoopBlock -> execLoop(b, path)
            is AlgorithmBlock -> {
                val alg = repo.algorithms.value.firstOrNull { it.id == b.algorithmId }
                    ?: throw BotError("Blok ALGORYTM: nie wybrano algorytmu lub został usunięty")
                if (alg.id in algStack) throw BotError("Blok ALGORYTM: „${alg.name}” wywołuje sam siebie (rekurencja)")
                val s = repo.settings.value
                val times = if (s.limitLoopEnabled) b.repeat.coerceIn(1, s.maxLoopIterations) else b.repeat.coerceAtLeast(1)
                for (i in 1..times) {
                    status.value = "Algorytm „${alg.name}” ($i/$times)"
                    repo.log("INFO", "↳ Podalgorytm „${alg.name}” ($i/$times)")
                    runAlgorithm(alg, false)
                }
            }
            is ForEachPatternBlock -> execForEach(b, path)
            is AtTimeBlock -> {
                if (onResumePath(path)) {
                    execBlocks(b.body, path?.plus(0))
                    return
                }
                val now = System.currentTimeMillis()
                if (b.atMs <= now) {
                    if (b.ifPast == PastAction.SKIP) {
                        repo.log("WARN", "Termin ${fmtDate(b.atMs)} już minął – pomijam blok")
                        return
                    }
                    repo.log("INFO", "Termin ${fmtDate(b.atMs)} już minął – wykonuję od razu")
                } else {
                    repo.log("INFO", "Czekam do ${fmtDate(b.atMs)}")
                    waitUntil(b.atMs, "Czekam do ${fmtDate(b.atMs)}")
                }
                execBlocks(b.body, path?.plus(0))
            }
            is RepeatEveryBlock -> execRepeat(b, path)
            is BreakBlock -> { repo.log("INFO", "PRZERWIJ PĘTLĘ"); throw ControlSignal(SignalKind.BREAK) }
            is ContinueBlock -> { repo.log("INFO", "POMIŃ ITERACJĘ → następna iteracja"); throw ControlSignal(SignalKind.CONTINUE) }
            is EndAlgorithmBlock -> { repo.log("INFO", "PRZERWIJ AKTUALNY ALGORYTM"); throw ControlSignal(SignalKind.END_ALGORITHM) }
            is StopBotBlock -> throw ControlSignal(SignalKind.STOP_BOT)
        }
    }

    /** Sprawdza warunek bloku JEŻELI (zrzut + dopasowanie) i zapisuje wynik w logach. */
    private suspend fun evalIf(b: IfPatternBlock): Boolean {
        status.value = "Sprawdzam wzorzec…"
        val r = find(b.patternId, b.threshold)
        val found = r.matches.isNotEmpty()
        val cond = if (b.negate) !found else found
        val best = r.matches.firstOrNull()
        repo.log(
            "INFO",
            "JEŻELI wzorzec „${r.info.name}” ${if (b.negate) "NIE " else ""}ISTNIEJE (wymagane ${pct(r.threshold)}): " +
                (if (best != null) "znaleziono ${pct(best.score)} w (${best.cx}, ${best.cy})" else "nie znaleziono") +
                " → warunek ${if (cond) "spełniony: gałąź TO" else "niespełniony: gałąź W PRZECIWNYM RAZIE"}"
        )
        return cond
    }

    /** Pojedynczy krok w trybie krokowym – patrz [runStep]. */
    private suspend fun execStepBlock(b: Block) {
        checkpoint()
        when (b) {
            is IfPatternBlock -> {
                val cond = evalIf(b)
                repo.log("INFO", "KROK: warunek ${if (cond) "spełniony" else "niespełniony"} – w trybie krokowym przechodzisz przez obie gałęzie po kolei")
            }
            is LoopBlock, is ForEachPatternBlock, is AtTimeBlock, is RepeatEveryBlock ->
                repo.log("INFO", "KROK: blok-kontener – tryb krokowy przechodzi przez jego zawartość jako osobne kroki")
            is BreakBlock, is ContinueBlock, is EndAlgorithmBlock, is StopBotBlock ->
                repo.log("INFO", "KROK: blok sterujący pominięty w trybie krokowym")
            else -> execBlock(b)
        }
    }

    private suspend fun execFindClick(b: FindClickBlock) {
        val s = repo.settings.value
        val timeout = if (s.limitWaitEnabled) b.timeoutMs.coerceAtMost(s.maxWaitMs) else b.timeoutMs
        val deadline = System.currentTimeMillis() + timeout
        val pinfo = patternGray(b.patternId).first
        repo.log("INFO", "ZNAJDŹ I KLIKNIJ: szukam „${pinfo.name}” (wymagane ${pct(b.threshold ?: pinfo.threshold)}, limit ${timeout} ms)")
        var attempts = 0
        while (true) {
            status.value = "Szukam wzorca…"
            val r = find(b.patternId, b.threshold)
            attempts++
            val m = r.matches.firstOrNull()
            if (m != null) {
                repo.log("INFO", "ZNAJDŹ I KLIKNIJ: znaleziono „${r.info.name}” (${pct(m.score)}) w (${m.cx}, ${m.cy}) w próbie $attempts")
                act("Klik we wzorzec „${r.info.name}”")
                // losowe przesunięcie nigdy nie wychodzi poza obrys znalezionego wzorca
                doGesture("ZNAJDŹ I KLIKNIJ – klik", listOf(Pt(m.cx.toFloat(), m.cy.toFloat())), 50, (m.w / 2 - 1).coerceAtLeast(0), (m.h / 2 - 1).coerceAtLeast(0))
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                repo.log("WARN", "ZNAJDŹ I KLIKNIJ: nie znaleziono „${r.info.name}” po $attempts próbach")
                when (b.onNotFound) {
                    NotFoundAction.ERROR -> throw BotError("Nie znaleziono wzorca „${r.info.name}” w ciągu ${b.timeoutMs} ms")
                    NotFoundAction.SKIP -> {
                        repo.log("WARN", "Nie znaleziono „${r.info.name}” – pomijam blok")
                        return
                    }
                    NotFoundAction.END_ALGORITHM -> {
                        repo.log("WARN", "Nie znaleziono „${r.info.name}” – przerywam algorytm")
                        throw ControlSignal(SignalKind.END_ALGORITHM)
                    }
                }
            }
            sleep(300)
        }
    }

    private suspend fun execLoop(b: LoopBlock, path: List<Int>?) {
        val resuming = onResumePath(path)
        val s = repo.settings.value
        if (s.limitLoopEnabled && b.mode == LoopMode.COUNT && b.count > s.maxLoopIterations)
            throw BotError("Pętla ${b.count}× przekracza limit ${s.maxLoopIterations} iteracji (Ustawienia → limity)")
        val limit = when {
            b.mode == LoopMode.COUNT -> b.count
            s.limitLoopEnabled -> minOf(b.maxIterations, s.maxLoopIterations)
            else -> Int.MAX_VALUE                      // limity wyłączone – pętla „do skutku” bez zabezpieczenia
        }
        repo.log(
            "INFO",
            when (b.mode) {
                LoopMode.COUNT -> "PĘTLA: start, ${b.count}×"
                LoopMode.UNTIL_FOUND -> "PĘTLA: start, do pojawienia się wzorca (" + if (limit == Int.MAX_VALUE) "bez limitu iteracji)" else "maks. $limit iteracji)"
                LoopMode.UNTIL_MISSING -> "PĘTLA: start, do zniknięcia wzorca (" + if (limit == Int.MAX_VALUE) "bez limitu iteracji)" else "maks. $limit iteracji)"
            }
        )
        var i = 0
        if (resuming) {                      // kontynuacja w środku pętli: dokończ tę iterację, potem pętla działa normalnie
            i = 1
            repo.log("INFO", "PĘTLA: kontynuacja w iteracji 1")
            if (!runBodyOnce(b.body, path)) return
            sleep(50)
        }
        while (true) {
            checkpoint()
            when (b.mode) {
                LoopMode.COUNT -> if (i >= b.count) { repo.log("INFO", "PĘTLA: zakończona (${b.count}/${b.count})"); return }
                LoopMode.UNTIL_FOUND -> {
                    val r = find(b.patternId, b.threshold)
                    val m = r.matches.firstOrNull()
                    repo.log("INFO", "PĘTLA: sprawdzam „${r.info.name}” (wymagane ${pct(r.threshold)}): " +
                        if (m != null) "jest (${pct(m.score)}) w (${m.cx}, ${m.cy}) → koniec pętli po $i iteracjach" else "nie ma → kolejna iteracja")
                    if (m != null) return
                }
                LoopMode.UNTIL_MISSING -> {
                    val r = find(b.patternId, b.threshold)
                    val m = r.matches.firstOrNull()
                    repo.log("INFO", "PĘTLA: sprawdzam „${r.info.name}” (wymagane ${pct(r.threshold)}): " +
                        if (m == null) "nie ma → koniec pętli po $i iteracjach" else "nadal jest (${pct(m.score)}) w (${m.cx}, ${m.cy}) → kolejna iteracja")
                    if (m == null) return
                }
            }
            if (i >= limit) throw BotError("Pętla: warunek nie został spełniony po $limit iteracjach")
            i++
            status.value = "Pętla: iteracja $i" + if (b.mode == LoopMode.COUNT) "/${b.count}" else ""
            repo.log("INFO", "PĘTLA: iteracja $i" + if (b.mode == LoopMode.COUNT) "/${b.count} (zostało ${b.count - i})" else "")
            try {
                execBlocks(b.body, path?.plus(0))
            } catch (sig: ControlSignal) {
                when (sig.kind) {
                    SignalKind.BREAK -> { repo.log("INFO", "PĘTLA: przerwana w iteracji $i"); return }
                    SignalKind.CONTINUE -> Unit
                    else -> throw sig
                }
            }
            sleep(50) // ochrona przed pętlą bez przerw
        }
    }

    /** DLA KAŻDEGO wzorca z zestawu – patrz [ForEachPatternBlock]. Po każdym elemencie ekran jest skanowany od nowa. */
    private suspend fun execForEach(b: ForEachPatternBlock, path: List<Int>?) {
        val resuming = onResumePath(path)
        if (b.patternIds.isEmpty()) throw BotError("Blok DLA KAŻDEGO WZORCA: dodaj co najmniej jeden wzorzec do zestawu")
        val s = repo.settings.value
        val limit = if (s.limitLoopEnabled) minOf(b.maxIterations, s.maxLoopIterations) else Int.MAX_VALUE
        var i = 0
        if (resuming) {
            i = 1
            if (!runBodyOnce(b.body, path)) return
            sleep(100)
        }
        while (true) {
            checkpoint()
            status.value = "Szukam wzorców z zestawu…"
            val hit = findAny(b.patternIds, b.threshold)
            if (hit == null) {
                repo.log("INFO", "DLA KAŻDEGO: na ekranie nie ma już żadnego wzorca z zestawu (obsłużono $i)")
                return
            }
            if (i >= limit) throw BotError("DLA KAŻDEGO: po $limit iteracjach na ekranie nadal są wzorce z zestawu")
            i++
            val m = hit.match
            repo.log("INFO", "DLA KAŻDEGO #$i: „${hit.info.name}” (${pct(m.score)}) w (${m.cx}, ${m.cy})")
            status.value = "Dla każdego #$i: „${hit.info.name}”"
            try {
                if (b.clickFound) {
                    act("Klik we wzorzec „${hit.info.name}”")
                    doGesture("DLA KAŻDEGO – klik", listOf(Pt(m.cx.toFloat(), m.cy.toFloat())), 50, (m.w / 2 - 1).coerceAtLeast(0), (m.h / 2 - 1).coerceAtLeast(0))
                }
                execBlocks(b.body, path?.plus(0))
            } catch (sig: ControlSignal) {
                when (sig.kind) {
                    SignalKind.BREAK -> return
                    SignalKind.CONTINUE -> Unit
                    else -> throw sig
                }
            }
            sleep(100)   // czas na zniknięcie/animację elementu przed ponownym skanem
        }
    }

    /** POWTARZAJ CO…: stały rytm liczony od startu poprzedniego uruchomienia (bez dryfu). */
    private suspend fun execRepeat(b: RepeatEveryBlock, path: List<Int>?) {
        val resuming = onResumePath(path)
        val interval = b.every.coerceAtLeast(1) * b.unit.ms
        var run = 0
        var next = System.currentTimeMillis() + if (b.runFirstNow) 0L else interval
        if (resuming) {                      // kontynuacja w środku cyklu: dokończ go, następny wg normalnego rytmu
            val t0 = System.currentTimeMillis()
            run = 1
            repo.log("INFO", "POWTARZAJ: kontynuacja w uruchomieniu #1")
            if (!runBodyOnce(b.body, path)) return
            next = t0 + interval
            if (next < System.currentTimeMillis()) next = System.currentTimeMillis()
        }
        while (true) {
            if (b.maxRuns > 0 && run >= b.maxRuns) {
                repo.log("INFO", "POWTARZAJ: wykonano zaplanowane $run uruchomień")
                return
            }
            if (next > System.currentTimeMillis())
                repo.log("INFO", "POWTARZAJ: co ${fmtDur(interval)} – następne uruchomienie ${fmtDate(next)}")
            waitUntil(next, "Następne uruchomienie ${fmtDate(next)}")
            run++
            repo.log("INFO", "POWTARZAJ: uruchomienie #$run" + if (b.maxRuns > 0) "/${b.maxRuns}" else "")
            try {
                execBlocks(b.body, path?.plus(0))
            } catch (sig: ControlSignal) {
                when (sig.kind) {
                    SignalKind.BREAK -> return
                    SignalKind.CONTINUE -> Unit
                    else -> throw sig
                }
            }
            next += interval
            if (next < System.currentTimeMillis()) {
                repo.log("WARN", "POWTARZAJ: wykonanie trwało dłużej niż interwał – następne uruchomienie od razu")
                next = System.currentTimeMillis()
            }
        }
    }


    /* ───────── narzędzia ───────── */

    private fun act(desc: String) {
        actions++
        status.value = desc
    }

    /** Losowy czas dodawany do każdego oczekiwania: 0…extraDelayMaxMs. */
    private fun extraDelay(): Long {
        val max = repo.settings.value.extraDelayMaxMs
        return if (max <= 0) 0L else Random.nextInt(max + 1).toLong()
    }

    private fun jitter(max: Int): Int = if (max <= 0) 0 else Random.nextInt(-max, max + 1)

    /**
     * Wykonuje gest z losowym przesunięciem −X…+X px (X z ustawień). Cała ścieżka jest przesuwana
     * o ten sam wektor, więc kształt przesunięcia się nie zniekształca. [limX]/[limY] ograniczają
     * przesunięcie (np. do połowy rozmiaru małego wzorca).
     */
    private suspend fun doGesture(label: String, points: List<Pt>, durationMs: Long, limX: Int = Int.MAX_VALUE, limY: Int = Int.MAX_VALUE) {
        val j = repo.settings.value.clickJitterPx
        val dx = jitter(minOf(j, limX)).toFloat()
        val dy = jitter(minOf(j, limY)).toFloat()
        val moved = if (dx == 0f && dy == 0f) points
        else points.map { it.copy(x = (it.x + dx).coerceAtLeast(0f), y = (it.y + dy).coerceAtLeast(0f)) }
        fun xy(p: Pt) = "(${p.x.toInt()}, ${p.y.toInt()})"
        val where = if (points.size < 2) xy(moved.first()) else "${xy(moved.first())} → ${xy(moved.last())}"
        val shift = if (dx == 0f && dy == 0f) "" else
            " [zadane ${if (points.size < 2) xy(points.first()) else "${xy(points.first())} → ${xy(points.last())}"}, losowe przesunięcie ${dx.toInt()}, ${dy.toInt()} px]"
        repo.log("INFO", "$label: $where$shift · ${durationMs} ms")
        if (testMode && repo.settings.value.showClickMarkers) markers?.show(moved, stepLabel ?: "#$actions")
        if (!io.gesture(moved, durationMs)) throw BotError("System anulował gest (inna aplikacja przechwyciła ekran?)")
    }

    /** Czeka do [target] (ms epoch). Czas oczekiwania nie liczy się do limitu czasu działania. */
    private suspend fun waitUntil(target: Long, label: String) {
        while (true) {
            val left = target - System.currentTimeMillis()
            if (left <= 0) return
            status.value = "$label (za ${fmtDur(left)})"
            val chunk = minOf(left, 1000L)
            delay(chunk)
            startedAt += chunk
            checkpoint()
        }
    }

    private suspend fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            val chunk = minOf(left, 100L)
            delay(chunk)
            left -= chunk
            checkpoint()
        }
    }

    /** Punkt kontrolny: anulowanie, pauza oraz limity bezpieczeństwa. */
    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (paused.value) {
            val t0 = System.currentTimeMillis()
            state.value = BotState.PAUSED
            repo.log("INFO", "⏸ Wstrzymano")
            paused.first { !it }
            startedAt += System.currentTimeMillis() - t0   // pauza nie liczy się do limitu czasu
            state.value = BotState.RUNNING
            repo.log("INFO", "▶ Wznowiono")
        }
        val s = repo.settings.value
        if (s.limitRuntimeEnabled && System.currentTimeMillis() - startedAt > s.maxRuntimeMin * 60_000L)
            throw BotError("Przekroczono limit czasu działania (${s.maxRuntimeMin} min)")
        if (s.limitActionsEnabled && actions > s.maxActions)
            throw BotError("Przekroczono limit liczby akcji (${s.maxActions})")
    }

    private fun patternGray(id: String?): Pair<PatternInfo, Gray> {
        val info = id?.let { pid -> repo.patterns.value.firstOrNull { it.id == pid } }
            ?: throw BotError("Blok używa wzorca, który nie został wybrany lub został usunięty")
        val g = grayCache.getOrPut(info.id) {
            repo.patternBitmap(info.id)?.toGray() ?: throw BotError("Brak pliku obrazu wzorca „${info.name}”")
        }
        return info to g
    }

    private suspend fun find(
        patternId: String?,
        thresholdOverride: Float?,
        minScore: Float? = null,
        max: Int = 1,
    ): FindResult {
        val (info, tpl) = patternGray(patternId)
        val threshold = thresholdOverride ?: info.threshold
        val shot = takeShot()
        val screen = shot.toGray()
        shot.recycle()
        val res = withContext(Dispatchers.Default) {
            TemplateMatcher.findAll(screen, tpl, minScore ?: threshold, max)
        }
        return FindResult(info, threshold, res)
    }

    /** Jeden zrzut ekranu, szukanie każdego wzorca z listy; zwraca trafienie położone najwyżej (potem najbardziej na lewo). */
    private suspend fun findAny(patternIds: List<String>, thresholdOverride: Float?): AnyHit? {
        val tpls = patternIds.map { patternGray(it) }
        val shot = takeShot()
        val screen = shot.toGray()
        shot.recycle()
        val hits = withContext(Dispatchers.Default) {
            tpls.flatMap { (info, tpl) ->
                val thr = thresholdOverride ?: info.threshold
                TemplateMatcher.findAll(screen, tpl, thr, 6).map { AnyHit(info, it, thr) }
            }
        }
        return hits.minWithOrNull(compareBy<AnyHit>({ it.match.y }, { it.match.x }))
    }

    private suspend fun takeShot(): Bitmap {
        // znaczniki kliknięć są oknami nad ekranem – nie mogą trafić na zrzut używany do szukania wzorców
        if (testMode && markers?.clear() == true) delay(120)
        val wait = 450 - (System.currentTimeMillis() - lastShot)
        if (wait > 0) delay(wait)
        repeat(4) { attempt ->
            try {
                lastShot = System.currentTimeMillis()
                return io.screenshot()
            } catch (e: ScreenshotException) {
                if (e.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < 3) {
                    delay(600)
                } else {
                    throw BotError("Nie można wykonać zrzutu ekranu (kod ${e.code}) – okno może być chronione przed zrzutami")
                }
            }
        }
        throw BotError("Nie udało się wykonać zrzutu ekranu")
    }

    private fun pct(f: Float) = "${(f * 100).toInt()}%"
}
