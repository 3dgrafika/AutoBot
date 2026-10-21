package pl.bot

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

/** Interpreter of algorithms/macros with execution limits. */
object Engine {
    val log = MutableStateFlow(listOf<String>())
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    private var job: Job? = null
    private class Brk : Exception()
    private class Halt(m: String) : Exception(m)

    fun log(s: String) {
        log.value = (log.value + "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} $s").takeLast(500)
    }

    fun stop() { job?.cancel() }

    fun start(c: Context, root: Script, maxSec: Int = 600, maxSteps: Int = 5000) =
        startSequence(c, listOf(root), maxSec, maxSteps)

    /** Runs the checked algorithms one after another, preserving the order selected in the BOT tab. */
    fun startSequence(c: Context, roots: List<Script>, maxSec: Int = 1800, maxSteps: Int = 15000) {
        val svc = BotService.inst ?: run { log("Włącz usługę dostępności"); return }
        if (running.value) return
        if (roots.isEmpty()) { log("Nie wybrano żadnego algorytmu"); return }
        running.value = true
        paused.value = false
        svc.showStop()
        log("Start bota: ${roots.size} algorytm(y)")
        job = CoroutineScope(Dispatchers.Default).launch {
            val t0 = SystemClock.elapsedRealtime()
            var n = 0
            val macros = Store.macros(c).associateBy { it.name }

            suspend fun tick() {
                while (paused.value) delay(200)
                ensureActive()
                if (++n > maxSteps) throw Halt("limit kroków ($maxSteps)")
                if (SystemClock.elapsedRealtime() - t0 > maxSec * 1000L) throw Halt("limit czasu ($maxSec s)")
            }

            suspend fun find(p: Map<String, String>): Pair<Int, Int>? {
                val name = p["name"] ?: throw Halt("nie wybrano wzorca")
                val tpl = Store.pattern(c, name) ?: throw Halt("brak wzorca '$name'")
                val scr = svc.shot() ?: throw Halt("nie udał się zrzut ekranu")
                val result = Matcher.find(scr, tpl, (p["thr"] ?: "0.95").toDoubleOrNull() ?: 0.95)
                delay(1000)
                return result
            }

            suspend fun exec(steps: List<Step>, depth: Int) {
                if (depth > 8) throw Halt("zbyt głębokie zagnieżdżenie")
                for (s in steps) {
                    tick()
                    val p = s.p
                    val details = p.filterKeys { it != "_comment" && it != "_label" }.values.joinToString(",")
                    log("${p["_label"]?.takeIf { it.isNotBlank() } ?: blockTitle(s.type)}${if (details.isBlank()) "" else " $details"}")
                    when (s.type) {
                        "tap" -> {
                            val x = p["x"]!!.toFloat(); val y = p["y"]!!.toFloat()
                            if (!svc.gesture(x, y, x, y, p["ms"]!!.toLong())) throw Halt("system odrzucił gest")
                        }
                        "swipe" -> if (!svc.gesture(
                                p["x1"]!!.toFloat(), p["y1"]!!.toFloat(), p["x2"]!!.toFloat(), p["y2"]!!.toFloat(), p["ms"]!!.toLong()
                            )) throw Halt("system odrzucił gest")
                        "wait" -> delay(p["ms"]!!.toLong())
                        "macro" -> {
                            val name = p["name"] ?: throw Halt("nie wybrano makra")
                            exec((macros[name] ?: throw Halt("brak makra '$name'")).steps, depth + 1)
                        }
                        "click_img" -> {
                            val f = find(p) ?: throw Halt("nie znaleziono '${p["name"]}'")
                            svc.gesture(f.first.toFloat(), f.second.toFloat(), f.first.toFloat(), f.second.toFloat(), 50)
                        }
                        "if_img" -> {
                            val found = find(p) != null
                            exec(if (found != (p["neg"] == "true")) s.body else s.els, depth + 1)
                        }
                        "repeat" -> try {
                            repeat((p["n"] ?: "3").toInt().coerceIn(0, 10000)) { exec(s.body, depth + 1) }
                        } catch (_: Brk) { }
                        "until_img" -> try {
                            var i = 0
                            while (find(p) == null) {
                                if (++i > (p["max"] ?: "20").toInt()) throw Halt("limit powtórzeń")
                                exec(s.body, depth + 1)
                            }
                        } catch (_: Brk) { }
                        "break" -> throw Brk()
                        "stop" -> throw Halt("krok STOP")
                    }
                }
            }

            try {
                roots.forEachIndexed { index, root ->
                    log("Algorytm ${index + 1}/${roots.size}: ${root.name}")
                    exec(root.steps, 0)
                }
                log("Zakończono wszystkie algorytmy")
            } catch (e: CancellationException) {
                log("Zatrzymano przez użytkownika")
            } catch (e: Halt) {
                log("Przerwano: ${e.message}")
            } catch (e: Brk) {
                log("Przerwano: 'przerwij pętlę' poza pętlą")
            } catch (e: Exception) {
                log("Błąd: $e")
            } finally {
                running.value = false
                paused.value = false
                svc.hideStop()
            }
        }
    }
}
