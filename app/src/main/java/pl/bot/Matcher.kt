package pl.bot
import android.graphics.Bitmap
import kotlin.math.abs

/** Dopasowanie wzorca: średnia różnica jasności na obrazach pomniejszonych 4x. thr 0..1 (1 = identyczny). */
object Matcher {
    private const val K = 4
    private fun gray(b: Bitmap, w: Int, h: Int): IntArray {
        val p = IntArray(w * h); Bitmap.createScaledBitmap(b, w, h, true).getPixels(p, 0, w, 0, 0, w, h)
        return IntArray(w * h) { val c = p[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
    }
    fun find(scr: Bitmap, tpl: Bitmap, thr: Double): Pair<Int, Int>? {
        val sw = scr.width / K; val sh = scr.height / K
        val tw = maxOf(1, tpl.width / K); val th = maxOf(1, tpl.height / K)
        if (tw > sw || th > sh) return null
        val s = gray(scr, sw, sh); val t = gray(tpl, tw, th)
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        for (y in 0..sh - th) for (x in 0..sw - tw) {
            var sum = 0L; var j = 0
            while (j < th && sum < best) { val so = (y + j) * sw + x; val to = j * tw
                for (i in 0 until tw) sum += abs(s[so + i] - t[to + i]); j++ }
            if (j == th && sum < best) { best = sum; bx = x; by = y }
        }
        val score = 1.0 - best.toDouble() / (255.0 * tw * th)
        return if (score >= thr) Pair(bx * K + tpl.width / 2, by * K + tpl.height / 2) else null
    }
}
