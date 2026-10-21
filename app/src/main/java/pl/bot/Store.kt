package pl.bot
import android.content.Context
import android.graphics.*
import com.google.gson.Gson
import java.io.File

data class Step(var type: String, var p: MutableMap<String, String> = mutableMapOf(),
                var body: MutableList<Step> = mutableListOf(), var els: MutableList<Step> = mutableListOf())
data class Script(var name: String, var steps: MutableList<Step> = mutableListOf())

/** Wszystko zapisywane lokalnie w filesDir: scripts/<nazwa>.json (makra i algorytmy), patterns/<nazwa>.png */
object Store {
    private val g = Gson()
    private fun dir(c: Context, n: String) = File(c.filesDir, n).apply { mkdirs() }
    fun list(c: Context): List<Script> = dir(c, "scripts").listFiles()!!.mapNotNull {
        runCatching { g.fromJson(it.readText(), Script::class.java) }.getOrNull() }.sortedBy { it.name }
    fun save(c: Context, s: Script) = File(dir(c, "scripts"), s.name + ".json").writeText(g.toJson(s))
    fun delete(c: Context, n: String) { File(dir(c, "scripts"), "$n.json").delete() }
    // Wzorce: patterns/<folder>/<nazwa>.png ; identyfikator wzorca = "folder/nazwa" (lub "nazwa" w katalogu głównym)
    private fun pdir(c: Context) = dir(c, "patterns")
    fun clean(n: String) = n.trim().replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").take(40)
    fun join(folder: String, n: String) = if (folder.isEmpty()) clean(n) else "$folder/${clean(n)}"
    /** Zwraca (podfoldery, wzorce) wskazanego folderu jako pełne ścieżki. */
    fun listDir(c: Context, folder: String): Pair<List<String>, List<String>> {
        val d = File(pdir(c), folder).apply { mkdirs() }
        val fs = d.listFiles() ?: return Pair(emptyList(), emptyList())
        return Pair(fs.filter { it.isDirectory }.map { join(folder, it.name) }.sorted(),
            fs.filter { it.extension == "png" }.map { join(folder, it.nameWithoutExtension) }.sorted())
    }
    fun createFolder(c: Context, path: String) { File(pdir(c), path).mkdirs() }
    fun deleteFolder(c: Context, path: String) { File(pdir(c), path).deleteRecursively() }
    fun savePattern(c: Context, id: String, b: Bitmap) {
        val f = File(pdir(c), "$id.png"); f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    fun deletePattern(c: Context, id: String) { File(pdir(c), "$id.png").delete() }
    fun pattern(c: Context, id: String): Bitmap? = BitmapFactory.decodeFile(File(pdir(c), "$id.png").path)
}
