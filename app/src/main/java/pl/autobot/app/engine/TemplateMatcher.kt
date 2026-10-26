package pl.autobot.app.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/** Obraz w skali szarości (8 bit) – wystarcza do dopasowania wzorca i jest szybki. */
class Gray(val w: Int, val h: Int, val data: ByteArray)

fun Bitmap.toGray(): Gray {
    val w = width
    val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    val out = ByteArray(w * h)
    for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        out[i] = ((r * 77 + g * 151 + b * 28) shr 8).toByte()
    }
    return Gray(w, h, out)
}

private fun Gray.downscale(f: Int): Gray {
    if (f <= 1) return this
    val nw = w / f
    val nh = h / f
    val out = ByteArray(nw * nh)
    val area = f * f
    for (y in 0 until nh) for (x in 0 until nw) {
        var s = 0
        for (dy in 0 until f) {
            val row = (y * f + dy) * w + x * f
            for (dx in 0 until f) s += data[row + dx].toInt() and 255
        }
        out[y * nw + x] = (s / area).toByte()
    }
    return Gray(nw, nh, out)
}

/**
 * Dopasowanie wzorca metodą sumy bezwzględnych różnic (SAD), dwuetapowo:
 *  1) szybkie wyszukiwanie zgrubne na pomniejszonym obrazie (z wczesnym odrzucaniem),
 *  2) dokładne dopracowanie najlepszych kandydatów w pełnej rozdzielczości.
 * Wynik "score" to 1.0 = identyczne, 0.0 = całkowicie różne.
 */
object TemplateMatcher {
    data class Match(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
    }

    private class Cand(val x: Int, val y: Int, val score: Float)

    private fun sad(s: Gray, t: Gray, ox: Int, oy: Int, limit: Long): Long {
        var sum = 0L
        val sd = s.data
        val td = t.data
        for (y in 0 until t.h) {
            var si = (oy + y) * s.w + ox
            var ti = y * t.w
            var row = 0
            for (x in 0 until t.w) {
                val d = (sd[si++].toInt() and 255) - (td[ti++].toInt() and 255)
                row += if (d < 0) -d else d
            }
            sum += row
            if (sum > limit) return sum
        }
        return sum
    }

    fun findAll(screen: Gray, tpl: Gray, threshold: Float, maxResults: Int = 1): List<Match> {
        if (tpl.w < 4 || tpl.h < 4 || tpl.w > screen.w || tpl.h > screen.h) return emptyList()
        val small = minOf(tpl.w, tpl.h)
        val f = when {
            small >= 48 -> 4
            small >= 24 -> 2
            else -> 1
        }
        val s = screen.downscale(f)
        val t = tpl.downscale(f)
        val n = t.w * t.h
        val coarseThr = (threshold - 0.10f).coerceAtLeast(0.40f)
        val limit = ((1f - coarseThr) * 255.0 * n).toLong()

        val cands = ArrayList<Cand>()
        for (y in 0..(s.h - t.h)) for (x in 0..(s.w - t.w)) {
            val sum = sad(s, t, x, y, limit)
            if (sum <= limit) cands.add(Cand(x, y, 1f - sum.toFloat() / (255f * n)))
        }
        if (cands.isEmpty()) return emptyList()
        cands.sortByDescending { it.score }

        // redukcja sąsiadujących kandydatów
        val dx = max(1, t.w / 2)
        val dy = max(1, t.h / 2)
        val picked = ArrayList<Cand>()
        for (c in cands) {
            if (picked.size >= maxResults * 8 + 8) break
            if (picked.none { abs(it.x - c.x) < dx && abs(it.y - c.y) < dy }) picked.add(c)
        }

        // dopracowanie w pełnej rozdzielczości
        val fullN = tpl.w * tpl.h
        val radius = if (f == 1) 0 else f
        val found = ArrayList<Match>()
        for (c in picked) {
            val cx = c.x * f
            val cy = c.y * f
            var best = -1f
            var bx = cx
            var by = cy
            for (oy in (cy - radius)..(cy + radius)) for (ox in (cx - radius)..(cx + radius)) {
                if (ox < 0 || oy < 0 || ox + tpl.w > screen.w || oy + tpl.h > screen.h) continue
                val lim = if (best < 0f) Long.MAX_VALUE else ((1f - best) * 255.0 * fullN).toLong()
                val sum = sad(screen, tpl, ox, oy, lim)
                val score = 1f - sum.toFloat() / (255f * fullN)
                if (score > best) { best = score; bx = ox; by = oy }
            }
            if (best >= threshold) found.add(Match(bx, by, tpl.w, tpl.h, best))
        }
        found.sortByDescending { it.score }
        val result = ArrayList<Match>()
        for (m in found) {
            if (result.size >= maxResults) break
            if (result.none { abs(it.x - m.x) < tpl.w / 2 && abs(it.y - m.y) < tpl.h / 2 }) result.add(m)
        }
        return result
    }
}
