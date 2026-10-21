package pl.bot
import android.content.Context
import android.graphics.*
import com.google.gson.Gson
import java.io.File

data class Step(var type: String, var p: MutableMap<String, String> = mutableMapOf(),
                var body: MutableList<Step> = mutableListOf(), var els: MutableList<Step> = mutableListOf())
data class Script(var name: String, var steps: MutableList<Step> = mutableListOf())

/** Wszystko zapisywane lokalnie w filesDir: scripts/*.json (makra i algorytmy), patterns/*.png */
object Store {
    private val g = Gson()
    private fun dir(c: Context, n: String) = File(c.filesDir, n).apply { mkdirs() }
    fun list(c: Context): List<Script> = dir(c, "scripts").listFiles()!!.mapNotNull {
        runCatching { g.fromJson(it.readText(), Script::class.java) }.getOrNull() }.sortedBy { it.name }
    fun save(c: Context, s: Script) = File(dir(c, "scripts"), s.name + ".json").writeText(g.toJson(s))
    fun delete(c: Context, n: String) { File(dir(c, "scripts"), "$n.json").delete() }
    fun patterns(c: Context) = dir(c, "patterns").listFiles()!!.map { it.nameWithoutExtension }.sorted()
    fun savePattern(c: Context, n: String, b: Bitmap) =
        File(dir(c, "patterns"), "$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    fun deletePattern(c: Context, n: String) { File(dir(c, "patterns"), "$n.png").delete() }
    fun pattern(c: Context, n: String): Bitmap? = BitmapFactory.decodeFile(File(dir(c, "patterns"), "$n.png").path)
}
