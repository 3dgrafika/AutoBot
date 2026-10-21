package pl.bot
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

/** Interpreter skryptów z limitami: czas, liczba kroków, powtórzenia, zagnieżdżenia. */
object Engine {
    val log = MutableStateFlow(listOf<String>())
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    private var job: Job? = null
    private class Brk : Exception()
    private class Halt(m: String) : Exception(m)

    fun log(s: String) { log.value = (log.value + "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} $s").takeLast(500) }
    fun stop() { job?.cancel() }

    /** Uruchamia zaznaczone algorytmy kolejno jako jeden proces. */
    fun startSequence(c: Context, names: List<String>) {
        val scripts = Store.list(c).associateBy { it.name }
        val selected = names.distinct().mapNotNull { scripts[it] }
        if (selected.isEmpty()) { log("Brak zaznaczonych algorytmów"); return }
        val sequence = Script("Cały bot", selected.map {
            Step("macro", mutableMapOf("name" to it.name))
        }.toMutableList())
        start(c, sequence)
    }


    fun start(c: Context, root: Script, maxSec: Int = 600, maxSteps: Int = 5000) {
        val svc = BotService.inst ?: run { log("Włącz usługę dostępności"); return }
        if (running.value) return
        running.value = true; svc.showStop(); log("Start: ${root.name}")
        val all = Store.list(c).associateBy { it.name }
        job = CoroutineScope(Dispatchers.Default).launch {
            val t0 = SystemClock.elapsedRealtime(); var n = 0
            suspend fun tick() {
                while (paused.value) delay(200); ensureActive()
                if (++n > maxSteps) throw Halt("limit kroków ($maxSteps)")
                if (SystemClock.elapsedRealtime() - t0 > maxSec * 1000L) throw Halt("limit czasu ($maxSec s)")
            }
            suspend fun find(p: Map<String, String>): Pair<Int, Int>? {
                val tpl = Store.pattern(c, p["name"]!!) ?: throw Halt("brak wzorca '${p["name"]}'")
                val scr = svc.shot() ?: throw Halt("nie udał się zrzut ekranu")
                val r = Matcher.find(scr, tpl, p["thr"]!!.toDouble()); delay(1000); return r
            }
            suspend fun exec(steps: List<Step>, depth: Int) {
                if (depth > 8) throw Halt("zbyt głębokie zagnieżdżenie")
                for (s in steps) {
                    tick(); val p = s.p; log("${s.type} ${p.values.joinToString(",")}")
                    when (s.type) {
                        "tap" -> { val x = p["x"]!!.toFloat(); val y = p["y"]!!.toFloat()
                            if (!svc.gesture(x, y, x, y, p["ms"]!!.toLong())) throw Halt("system odrzucił gest") }
                        "swipe" -> if (!svc.gesture(p["x1"]!!.toFloat(), p["y1"]!!.toFloat(), p["x2"]!!.toFloat(), p["y2"]!!.toFloat(), p["ms"]!!.toLong())) throw Halt("system odrzucił gest")
                        "wait" -> delay(p["ms"]!!.toLong())
                        "macro" -> exec((all[p["name"]] ?: throw Halt("brak makra '${p["name"]}'")).steps, depth + 1)
                        "click_img" -> { val f = find(p) ?: throw Halt("nie znaleziono '${p["name"]}'")
                            svc.gesture(f.first.toFloat(), f.second.toFloat(), f.first.toFloat(), f.second.toFloat(), 50) }
                        "if_img" -> { val found = find(p) != null
                            exec(if (found != (p["neg"] == "true")) s.body else s.els, depth + 1) }
                        "repeat" -> try { repeat(p["n"]!!.toInt().coerceAtMost(10000)) { exec(s.body, depth + 1) } } catch (e: Brk) {}
                        "until_img" -> try { var i = 0
                            while (find(p) == null) { if (++i > p["max"]!!.toInt()) throw Halt("limit powtórzeń"); exec(s.body, depth + 1) } } catch (e: Brk) {}
                        "break" -> throw Brk()
                        "stop" -> throw Halt("krok STOP")
                    }
                }
            }
            try { exec(root.steps, 0); log("Zakończono") }
            catch (e: CancellationException) { log("Zatrzymano przez użytkownika") }
            catch (e: Halt) { log("Przerwano: ${e.message}") }
            catch (e: Brk) { log("Przerwano: 'break' poza pętlą") }
            catch (e: Exception) { log("Błąd: $e") }
            finally { running.value = false; paused.value = false; svc.hideStop() }
        }
    }
}
