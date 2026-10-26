package pl.autobot.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.hypot

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/* ───────────── Makra (nagrane gesty) ───────────── */

@Serializable
data class Pt(val x: Float, val y: Float, val t: Long = 0)

/** Jeden nagrany gest: opóźnienie od poprzedniego gestu, czas trwania i ścieżka palca. */
@Serializable
data class GestureStep(val delayMs: Long, val durationMs: Long, val points: List<Pt>) {
    val kind: String
        get() {
            if (points.size < 2) return if (durationMs >= 500) "Przytrzymanie" else "Kliknięcie"
            val a = points.first()
            val b = points.last()
            val moved = hypot(b.x - a.x, b.y - a.y)
            return if (moved < 25f && durationMs >= 500) "Przytrzymanie" else "Przesunięcie"
        }

    val where: String
        get() {
            val a = points.first()
            val b = points.last()
            return if (points.size < 2) "(${a.x.toInt()}, ${a.y.toInt()})"
            else "(${a.x.toInt()}, ${a.y.toInt()}) → (${b.x.toInt()}, ${b.y.toInt()})"
        }
}

/** Zamienia nagrane gesty na bloki: przerwa → CZEKAJ, kliknięcie → KLIKNIJ, przesunięcie → PRZESUŃ. */
fun gestureStepsToBlocks(steps: List<GestureStep>): List<Block> {
    val blocks = ArrayList<Block>()
    for (st in steps) {
        if (st.delayMs > 0) blocks.add(WaitBlock(st.delayMs))
        val a = st.points.first()
        val z = st.points.last()
        blocks.add(
            if (st.points.size < 2) TapBlock(a.x.toInt(), a.y.toInt(), st.durationMs)
            else SwipeBlock(a.x.toInt(), a.y.toInt(), z.x.toInt(), z.y.toInt(), st.durationMs)
        )
    }
    return blocks
}

/* ───────────── Wzorce obrazów ───────────── */

@Serializable
data class PatternInfo(
    val id: String = newId(),
    val name: String,
    val folder: String = "",          // "" = główny folder WZORCE, np. "GRA/EKRAN_GLOWNY"
    val width: Int,
    val height: Int,
    val threshold: Float = 0.90f,     // domyślna dokładność dopasowania 0.5–1.0
    val createdAt: Long = System.currentTimeMillis(),
)

/* ───────────── Bloki algorytmów ───────────── */

enum class NotFoundAction { ERROR, SKIP, END_ALGORITHM }
enum class LoopMode { COUNT, UNTIL_FOUND, UNTIL_MISSING }
enum class RepeatUnit(val ms: Long) {
    MINUTES(60_000L), HOURS(3_600_000L), DAYS(86_400_000L), WEEKS(604_800_000L)
}
/** Co zrobić z blokiem „o dacie i godzinie”, gdy termin już minął w chwili dojścia do bloku. */
enum class PastAction { SKIP, RUN_NOW }

@Serializable
sealed class Block

@Serializable @SerialName("tap")
data class TapBlock(val x: Int = 540, val y: Int = 1200, val durationMs: Long = 50) : Block()

@Serializable @SerialName("swipe")
data class SwipeBlock(
    val x1: Int = 540, val y1: Int = 1600,
    val x2: Int = 540, val y2: Int = 600,
    val durationMs: Long = 400,
) : Block()

@Serializable @SerialName("wait")
data class WaitBlock(val ms: Long = 1000) : Block()

@Serializable @SerialName("find_click")
data class FindClickBlock(
    val patternId: String? = null,
    val threshold: Float? = null,          // null = dokładność domyślna wzorca
    val timeoutMs: Long = 5000,
    val onNotFound: NotFoundAction = NotFoundAction.ERROR,
) : Block()

@Serializable @SerialName("if_pattern")
data class IfPatternBlock(
    val patternId: String? = null,
    val threshold: Float? = null,
    val negate: Boolean = false,           // false: JEŻELI istnieje, true: JEŻELI NIE istnieje
    val thenBlocks: List<Block> = emptyList(),
    val elseBlocks: List<Block> = emptyList(),
) : Block()

@Serializable @SerialName("loop")
data class LoopBlock(
    val mode: LoopMode = LoopMode.COUNT,
    val count: Int = 3,
    val patternId: String? = null,
    val threshold: Float? = null,
    val maxIterations: Int = 50,           // dla trybów "do skutku" – zabezpieczenie
    val body: List<Block> = emptyList(),
) : Block()

/** Wywołanie innego zapisanego algorytmu jako bloku (podalgorytm). */
@Serializable @SerialName("algorithm")
data class AlgorithmBlock(val algorithmId: String? = null, val repeat: Int = 1) : Block()

/**
 * DLA KAŻDEGO wzorca z zestawu: dopóki na ekranie jest którykolwiek wzorzec z listy, bierze pierwszy
 * z nich (od góry ekranu), opcjonalnie go klika, wykonuje [body] i szuka ponownie.
 */
@Serializable @SerialName("for_each_pattern")
data class ForEachPatternBlock(
    val patternIds: List<String> = emptyList(),
    val threshold: Float? = null,
    val clickFound: Boolean = true,
    val maxIterations: Int = 50,
    val body: List<Block> = emptyList(),
) : Block()

/** Czeka do wskazanej daty i godziny, po czym wykonuje [body]. */
@Serializable @SerialName("at_time")
data class AtTimeBlock(
    val atMs: Long = System.currentTimeMillis() + 3_600_000L,
    val ifPast: PastAction = PastAction.SKIP,
    val body: List<Block> = emptyList(),
) : Block()

/** Wykonuje [body] cyklicznie: co [every] [unit]. maxRuns = 0 oznacza „bez końca”. */
@Serializable @SerialName("repeat_every")
data class RepeatEveryBlock(
    val every: Int = 1,
    val unit: RepeatUnit = RepeatUnit.HOURS,
    val runFirstNow: Boolean = true,
    val maxRuns: Int = 0,
    val body: List<Block> = emptyList(),
) : Block()

@Serializable @SerialName("break")
data object BreakBlock : Block()

@Serializable @SerialName("continue")
data object ContinueBlock : Block()

@Serializable @SerialName("end_algorithm")
data object EndAlgorithmBlock : Block()

@Serializable @SerialName("stop_bot")
data object StopBotBlock : Block()

/* ───────────── Algorytmy, ustawienia, logi ───────────── */

@Serializable
data class Algorithm(
    val id: String = newId(),
    val name: String,
    val blocks: List<Block> = emptyList(),
    val selected: Boolean = false,
    val inBot: Boolean = false,          // true = algorytm wczytany do zakładki BOT
    val createdAt: Long = 0L,            // 0 = algorytm sprzed wprowadzenia dat (data pojawi się po pierwszej edycji)
    val modifiedAt: Long = 0L,
) {
    /** Data ostatniej modyfikacji (lub utworzenia). */
    val stamp: Long get() = maxOf(createdAt, modifiedAt)
}

/** Identyfikatory algorytmów używanych (także zagnieżdżonych w kontenerach) w liście bloków. */
fun List<Block>.algorithmRefs(): List<String> = flatMap { b ->
    when (b) {
        is AlgorithmBlock -> listOfNotNull(b.algorithmId)
        is IfPatternBlock -> b.thenBlocks.algorithmRefs() + b.elseBlocks.algorithmRefs()
        is LoopBlock -> b.body.algorithmRefs()
        is ForEachPatternBlock -> b.body.algorithmRefs()
        is AtTimeBlock -> b.body.algorithmRefs()
        is RepeatEveryBlock -> b.body.algorithmRefs()
        else -> emptyList()
    }
}

/** Czy algorytm [fromId] (bezpośrednio lub przez inne algorytmy) wywołuje algorytm [targetId]? Chroni przed rekurencją. */
fun reachesAlgorithm(all: List<Algorithm>, fromId: String, targetId: String): Boolean {
    val seen = HashSet<String>()
    val stack = ArrayList<String>()
    stack.add(fromId)
    while (stack.isNotEmpty()) {
        val id = stack.removeAt(stack.lastIndex)
        if (id == targetId) return true
        if (!seen.add(id)) continue
        val a = all.firstOrNull { it.id == id } ?: continue
        stack.addAll(a.blocks.algorithmRefs())
    }
    return false
}

/** Limity bezpieczeństwa i losowość działań. */
@Serializable
data class Settings(
    val limitRuntimeEnabled: Boolean = true,  // każdy limit można wyłączyć osobno
    val maxRuntimeMin: Int = 30,
    val limitActionsEnabled: Boolean = true,
    val maxActions: Int = 5000,
    val limitLoopEnabled: Boolean = true,
    val maxLoopIterations: Int = 500,
    val limitWaitEnabled: Boolean = true,
    val maxWaitMs: Long = 60_000,
    val startDelaySec: Int = 3,
    val clickJitterPx: Int = 5,               // każde kliknięcie/przesunięcie: losowo −X…+X px (0 = wyłączone)
    val extraDelayMaxMs: Int = 500,           // do każdego oczekiwania: losowo 0…Y ms (0 = wyłączone)
    val showClickMarkers: Boolean = true,     // test (TESTUJ / tryb krokowy): pokazuj na ekranie miejsca kliknięć
)

@Serializable
data class LogEntry(val ts: Long, val level: String, val msg: String)

/** Współrzędne pobrane fizycznym dotknięciem ekranu (pływający przycisk rejestracji). */
data class PickedGesture(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val durationMs: Long, val isSwipe: Boolean,
    val ts: Long = System.currentTimeMillis(),
    val keepDuration: Boolean = false,      // true = edycja pozycji na ekranie: zostaw czas z bloku
)

/* ───────────── Kopia zapasowa ───────────── */

/** Zawartość pliku backup.json w archiwum kopii zapasowej (obrazy wzorców leżą obok jako patterns/<id>.png). */
@Serializable
data class BackupData(
    val format: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val algorithms: List<Algorithm> = emptyList(),
    val patterns: List<PatternInfo> = emptyList(),
    val folders: List<String> = emptyList(),
    val settings: Settings = Settings(),
)

data class BackupSummary(val algorithms: Int, val patterns: Int)
