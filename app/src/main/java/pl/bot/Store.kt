package pl.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import java.io.File

/** A single step in an algorithm or macro. Comments are stored in p["_comment"]. */
data class Step(
    var type: String,
    var p: MutableMap<String, String> = mutableMapOf(),
    var body: MutableList<Step> = mutableListOf(),
    var els: MutableList<Step> = mutableListOf()
)

data class Script(
    var name: String,
    var steps: MutableList<Step> = mutableListOf(),
    var kind: String = "algorithm" // "algorithm" or "macro"
)

/** Local storage. Legacy scripts are migrated in memory when loaded. */
object Store {
    private val g = Gson()
    private const val PREFS = "autobot_preferences"
    private const val ORDER_KEY = "algorithm_order"
    private const val SELECTED_KEY = "selected_algorithms"
    private const val SEP = "\u001f"

    private fun dir(c: Context, n: String) = File(c.filesDir, n).apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): List<Script> {
        val files = dir(c, "scripts").listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { f ->
                runCatching {
                    g.fromJson(f.readText(), Script::class.java)?.apply {
                        // Gson does not apply Kotlin constructor defaults to missing legacy fields.
                        if (name.isNullOrBlank()) name = f.nameWithoutExtension
                    }
                }.getOrNull()
            }
            .onEach { s ->
                if (s.steps == null) s.steps = mutableListOf()
                if (s.kind != "algorithm" && s.kind != "macro") {
                    s.kind = if (s.name.startsWith("makro", ignoreCase = true) || s.name.startsWith("macro", ignoreCase = true)) "macro" else "algorithm"
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    fun save(c: Context, s: Script) {
        s.name = clean(s.name)
        if (s.kind != "macro") s.kind = "algorithm"
        File(dir(c, "scripts"), s.name + ".json").writeText(g.toJson(s))
    }

    fun uniqueName(c: Context, prefix: String, kind: String? = null): String {
        val names = list(c).filter { kind == null || it.kind == kind }.map { it.name }.toSet()
        var n = 1
        while ("$prefix$n" in names) n++
        return "$prefix$n"
    }

    fun algorithms(c: Context): List<Script> = list(c).filter { it.kind == "algorithm" }
    fun macros(c: Context): List<Script> = list(c).filter { it.kind == "macro" }

    fun orderedAlgorithms(c: Context): List<Script> {
        val all = algorithms(c)
        val byName = all.associateBy { it.name }
        val savedOrder = prefs(c).getString(ORDER_KEY, null)
        if (savedOrder == null) return all
        val names = savedOrder.split(SEP).filter { it.isNotBlank() }
        return names.mapNotNull { byName[it] } + all.filter { it.name !in names }
    }

    fun saveAlgorithmOrder(c: Context, names: List<String>) {
        prefs(c).edit().putString(ORDER_KEY, names.joinToString(SEP)).apply()
    }

    fun selectedAlgorithms(c: Context, available: List<Script>): Set<String> {
        val p = prefs(c)
        if (!p.contains(SELECTED_KEY)) return available.map { it.name }.toSet()
        return p.getString(SELECTED_KEY, "").orEmpty().split(SEP).filter { it.isNotBlank() }.toSet()
    }

    fun saveSelectedAlgorithms(c: Context, names: Set<String>) {
        prefs(c).edit().putString(SELECTED_KEY, names.joinToString(SEP)).apply()
    }

    fun delete(c: Context, n: String) {
        File(dir(c, "scripts"), "$n.json").delete()
        val p = prefs(c)
        p.edit()
            .putString(ORDER_KEY, p.getString(ORDER_KEY, "").orEmpty().split(SEP).filter { it.isNotBlank() && it != n }.joinToString(SEP))
            .putString(SELECTED_KEY, p.getString(SELECTED_KEY, "").orEmpty().split(SEP).filter { it.isNotBlank() && it != n }.joinToString(SEP))
            .apply()
    }

    /** Rename a script and update every macro call that points at it. */
    fun renameScript(c: Context, oldName: String, requestedName: String): Boolean {
        val newName = clean(requestedName)
        if (newName.isBlank()) return false
        val scripts = list(c)
        val target = scripts.firstOrNull { it.name == oldName } ?: return false
        if (newName != oldName && scripts.any { it.name == newName }) return false
        if (newName == oldName) return true

        val oldFile = File(dir(c, "scripts"), "$oldName.json")
        target.name = newName
        save(c, target)
        oldFile.delete()

        if (target.kind == "macro") {
            list(c).forEach { script ->
                if (replaceReferences(script.steps, oldName, newName, setOf("macro"))) save(c, script)
            }
        }
        val p = prefs(c)
        fun replaceStoredNames(key: String) {
            val names = p.getString(key, null) ?: return
            p.edit().putString(key, names.split(SEP).map { if (it == oldName) newName else it }.joinToString(SEP)).apply()
        }
        replaceStoredNames(ORDER_KEY)
        replaceStoredNames(SELECTED_KEY)
        return true
    }

    private fun replaceReferences(steps: MutableList<Step>, oldName: String, newName: String, types: Set<String>): Boolean {
        var changed = false
        steps.forEach { step ->
            if (step.type in types && step.p["name"] == oldName) {
                step.p["name"] = newName
                changed = true
            }
            if (replaceReferences(step.body, oldName, newName, types)) changed = true
            if (replaceReferences(step.els, oldName, newName, types)) changed = true
        }
        return changed
    }

    private fun pdir(c: Context) = dir(c, "patterns")
    fun clean(n: String) = n.trim().replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").take(60).trim()
    fun join(folder: String, n: String) = if (folder.isEmpty()) clean(n) else "$folder/${clean(n)}"

    /** Returns subfolders and pattern IDs (relative paths without .png) for a folder. */
    fun listDir(c: Context, folder: String): Pair<List<String>, List<String>> {
        val d = File(pdir(c), folder).apply { mkdirs() }
        val fs = d.listFiles() ?: return Pair(emptyList(), emptyList())
        return Pair(
            fs.filter { it.isDirectory }.map { join(folder, it.name) }.sorted(),
            fs.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }.map { join(folder, it.nameWithoutExtension) }.sorted()
        )
    }

    fun createFolder(c: Context, path: String) { File(pdir(c), path).mkdirs() }
    fun deleteFolder(c: Context, path: String) { File(pdir(c), path).deleteRecursively() }

    fun savePattern(c: Context, id: String, b: Bitmap) {
        val f = File(pdir(c), "$id.png")
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun patternExists(c: Context, id: String): Boolean = File(pdir(c), "$id.png").isFile

    /** Keep all references working when a pattern is renamed. */
    fun renamePattern(c: Context, oldId: String, requestedName: String): Boolean {
        val folder = oldId.substringBeforeLast('/', "")
        val newId = join(folder, requestedName)
        if (newId.isBlank() || newId == oldId) return newId == oldId
        val source = File(pdir(c), "$oldId.png")
        val target = File(pdir(c), "$newId.png")
        if (!source.isFile || target.exists()) return false
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) return false
        list(c).forEach { script ->
            if (replaceReferences(script.steps, oldId, newId, IMG)) save(c, script)
        }
        return true
    }

    fun deletePattern(c: Context, id: String) { File(pdir(c), "$id.png").delete() }
    fun pattern(c: Context, id: String): Bitmap? = BitmapFactory.decodeFile(File(pdir(c), "$id.png").path)
}
