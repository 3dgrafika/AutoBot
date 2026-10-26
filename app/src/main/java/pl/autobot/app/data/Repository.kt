package pl.autobot.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Lokalne repozytorium: algorytmy, wzorce i ustawienia to pliki JSON w pamięci
 * prywatnej aplikacji (filesDir), obrazy wzorców to pliki PNG, logi – plik tekstowy.
 * Dzięki temu wszystko przetrwa zamknięcie i ponowne uruchomienie aplikacji.
 */
class Repository(ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val root: File = ctx.filesDir
    private val patternsDir = File(root, "patterns").apply { mkdirs() }
    private val logFile = File(root, "bot.log")
    private val lock = Any()
    /** Kompaktowy JSON do kopii zapasowej (bez wcięć; archiwum ZIP dodatkowo go kompresuje). */
    private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val algorithms = MutableStateFlow<List<Algorithm>>(emptyList())
    val patterns = MutableStateFlow<List<PatternInfo>>(emptyList())
    val folders = MutableStateFlow<List<String>>(emptyList())
    val settings = MutableStateFlow(Settings())
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())

    init {
        algorithms.value = read<List<Algorithm>>("algorithms.json") ?: emptyList()
        patterns.value = read<List<PatternInfo>>("patterns.json") ?: emptyList()
        folders.value = read<List<String>>("folders.json") ?: emptyList()
        settings.value = read<Settings>("settings.json") ?: Settings()
        loadLogs()
    }

    private inline fun <reified T> read(name: String): T? = try {
        val f = File(root, name)
        if (f.exists()) json.decodeFromString<T>(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    private inline fun <reified T> write(name: String, value: T) {
        synchronized(lock) {
            try {
                File(root, name).writeText(json.encodeToString(value))
            } catch (_: Exception) {
            }
        }
    }

    /* ── algorytmy ── */
    fun saveAlgorithm(a: Algorithm) {
        val now = System.currentTimeMillis()
        algorithms.update { list ->
            val old = list.firstOrNull { it.id == a.id }
            if (old == null) {
                list + a.copy(createdAt = if (a.createdAt == 0L) now else a.createdAt, modifiedAt = now)
            } else {
                // zaznaczenie / wczytanie do BOT-a nie jest modyfikacją algorytmu
                val changed = old.name != a.name || old.blocks != a.blocks
                val na = if (changed) a.copy(modifiedAt = now, createdAt = if (a.createdAt == 0L) now else a.createdAt) else a
                list.map { if (it.id == a.id) na else it }
            }
        }
        write("algorithms.json", algorithms.value)
    }

    fun deleteAlgorithm(id: String) {
        algorithms.update { it.filterNot { a -> a.id == id } }
        write("algorithms.json", algorithms.value)
    }

    /** Kopiuje algorytm (głęboko – bloki są niezmienne) i wstawia kopię tuż za oryginałem. */
    fun duplicateAlgorithm(id: String): Algorithm? {
        val list = algorithms.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val src = list[idx]
        val now = System.currentTimeMillis()
        val copy = src.copy(id = newId(), name = src.name + " (kopia)", selected = false, createdAt = now, modifiedAt = now)
        val out = list.toMutableList().also { it.add(idx + 1, copy) }
        algorithms.value = out
        write("algorithms.json", out)
        return copy
    }

    /** Przenosi algorytm na pozycję [toIndex] w obrębie algorytmów wczytanych do BOT-a (kolejność = kolejność uruchamiania). */
    fun reorderBot(id: String, toIndex: Int) {
        val list = algorithms.value
        val slots = list.indices.filter { list[it].inBot }
        val loaded = slots.map { list[it] }.toMutableList()
        val from = loaded.indexOfFirst { it.id == id }
        if (from < 0) return
        val a = loaded.removeAt(from)
        loaded.add(toIndex.coerceIn(0, loaded.size), a)
        val out = list.toMutableList()
        slots.forEachIndexed { k, pos -> out[pos] = loaded[k] }
        algorithms.value = out
        write("algorithms.json", out)
    }

    /** Ustawia, które algorytmy są wczytane do zakładki BOT. */
    fun setBotLoaded(ids: Set<String>) {
        val list = algorithms.value.map { it.copy(inBot = it.id in ids) }
        algorithms.value = list
        write("algorithms.json", list)
    }

    /* ── wzorce ── */
    fun patternBitmap(id: String): Bitmap? =
        File(patternsDir, "$id.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun addPattern(name: String, folder: String, bmp: Bitmap): PatternInfo {
        val info = PatternInfo(name = name, folder = folder, width = bmp.width, height = bmp.height)
        File(patternsDir, "${info.id}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        patterns.update { it + info }
        write("patterns.json", patterns.value)
        return info
    }

    fun updatePattern(p: PatternInfo) {
        patterns.update { list -> list.map { if (it.id == p.id) p else it } }
        write("patterns.json", patterns.value)
    }

    fun deletePattern(id: String) {
        File(patternsDir, "$id.png").delete()
        patterns.update { it.filterNot { p -> p.id == id } }
        write("patterns.json", patterns.value)
    }

    /** Zmienia nazwę folderu (razem z podfolderami i wzorcami w nich). Zwraca nową ścieżkę albo null, gdy nazwa jest zajęta/niepoprawna. */
    fun renameFolder(path: String, newName: String): String? {
        val n = newName.trim().filter { it != '/' }
        if (path.isEmpty() || n.isEmpty()) return null
        val parent = path.substringBeforeLast('/', "")
        val np = if (parent.isEmpty()) n else "$parent/$n"
        if (np == path) return path
        if (np in folders.value) return null
        fun mv(p: String) = when {
            p == path -> np
            p.startsWith("$path/") -> np + p.removePrefix(path)
            else -> p
        }
        folders.value = folders.value.map { mv(it) }.distinct()
        patterns.value = patterns.value.map { it.copy(folder = mv(it.folder)) }
        write("folders.json", folders.value)
        write("patterns.json", patterns.value)
        return np
    }

    /** Usuwa folder wraz z podfolderami. Wzorce z środka NIE są kasowane – trafiają do folderu nadrzędnego. */
    fun deleteFolder(path: String) {
        if (path.isEmpty()) return
        val parent = path.substringBeforeLast('/', "")
        fun inside(p: String) = p == path || p.startsWith("$path/")
        folders.value = folders.value.filterNot { inside(it) }
        patterns.value = patterns.value.map { if (inside(it.folder)) it.copy(folder = parent) else it }
        write("folders.json", folders.value)
        write("patterns.json", patterns.value)
    }

    fun addFolder(path: String) {
        if (path in folders.value) return
        folders.update { it + path }
        write("folders.json", folders.value)
    }

    /* ── ustawienia ── */
    fun saveSettings(s: Settings) {
        settings.value = s
        write("settings.json", s)
    }

    /* ── kopia zapasowa ── */

    /**
     * Zapisuje do [out] archiwum ZIP:
     *  • backup.json – algorytmy, informacje o wzorcach, foldery i ustawienia (kompaktowy JSON),
     *  • patterns/<id>.png – obrazy wzorców (bezstratne PNG, już skompresowane).
     * Logi nie wchodzą do kopii. Zwykle całość to kilkadziesiąt–kilkaset KB.
     */
    fun exportBackup(out: OutputStream): BackupSummary {
        val pats = patterns.value
        val data = BackupData(
            algorithms = algorithms.value, patterns = pats,
            folders = folders.value, settings = settings.value,
        )
        ZipOutputStream(out).use { zip ->
            zip.setLevel(9)
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(compact.encodeToString(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in pats) {
                val f = File(patternsDir, "${p.id}.png")
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("patterns/${p.id}.png"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return BackupSummary(data.algorithms.size, pats.size)
    }

    /**
     * Wczytuje kopię. [replace] = true: zastępuje wszystko (łącznie z ustawieniami);
     * false: dołącza do istniejących danych, a elementy o tym samym id aktualizuje.
     */
    fun importBackup(input: InputStream, replace: Boolean): BackupSummary {
        var parsed: BackupData? = null
        val images = HashMap<String, ByteArray>()
        var entries = 0
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (++entries > 20_000) throw IllegalArgumentException("Nieprawidłowy plik kopii")
                val name = e.name
                if (name == "backup.json") {
                    parsed = compact.decodeFromString<BackupData>(zip.readLimited(64_000_000).toString(Charsets.UTF_8))
                } else if (name.startsWith("patterns/") && name.endsWith(".png")) {
                    val id = name.removePrefix("patterns/").removeSuffix(".png")
                    if (ID_RE.matches(id)) images[id] = zip.readLimited(32_000_000)
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val data = parsed ?: throw IllegalArgumentException("To nie jest kopia zapasowa AutoBot (brak backup.json)")
        if (data.format > BACKUP_FORMAT) throw IllegalArgumentException("Kopia pochodzi z nowszej wersji aplikacji")

        if (replace) {
            patternsDir.listFiles()?.forEach { it.delete() }
        }
        val validPatterns = data.patterns.filter {
            ID_RE.matches(it.id) && (it.id in images || (!replace && File(patternsDir, "${it.id}.png").exists()))
        }
        for ((id, bytes) in images) {
            if (validPatterns.any { it.id == id }) File(patternsDir, "$id.png").writeBytes(bytes)
        }

        if (replace) {
            algorithms.value = data.algorithms
            patterns.value = validPatterns
            folders.value = data.folders.filter { it.isNotEmpty() }.distinct()
            settings.value = data.settings
        } else {
            algorithms.value = upsert(algorithms.value, data.algorithms) { it.id }
            patterns.value = upsert(patterns.value, validPatterns) { it.id }
            folders.value = (folders.value + data.folders.filter { it.isNotEmpty() }).distinct()
        }
        write("algorithms.json", algorithms.value)
        write("patterns.json", patterns.value)
        write("folders.json", folders.value)
        write("settings.json", settings.value)
        return BackupSummary(data.algorithms.size, validPatterns.size)
    }

    private fun <T> upsert(old: List<T>, new: List<T>, id: (T) -> String): List<T> {
        val byId = new.associateBy(id)
        return old.map { byId[id(it)] ?: it } + new.filter { n -> old.none { id(it) == id(n) } }
    }

    private fun InputStream.readLimited(max: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalArgumentException("Plik w kopii jest zbyt duży")
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    /* ── logi ── */
    private val timeFmt get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun formatLog(e: LogEntry): String = "${timeFmt.format(Date(e.ts))}  ${e.level.padEnd(5)} ${e.msg}"

    fun log(level: String, msg: String) {
        val e = LogEntry(System.currentTimeMillis(), level, msg)
        logs.update { (it + e).takeLast(1000) }
        synchronized(lock) {
            try {
                logFile.appendText("${e.ts}\t$level\t${msg.replace('\n', ' ')}\n")
                if (logFile.length() > 512_000) {
                    val keep = logFile.readLines().takeLast(500)
                    logFile.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Exception) {
            }
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        synchronized(lock) { try { logFile.writeText("") } catch (_: Exception) {} }
    }

    private fun loadLogs() {
        try {
            if (!logFile.exists()) return
            logs.value = logFile.readLines().takeLast(500).mapNotNull { line ->
                val p = line.split('\t', limit = 3)
                if (p.size == 3) LogEntry(p[0].toLongOrNull() ?: 0L, p[1], p[2]) else null
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val BACKUP_FORMAT = 1
        private val ID_RE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
