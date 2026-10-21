package pl.bot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val DEF = linkedMapOf(
    "tap" to mapOf("x" to "500", "y" to "500", "ms" to "50"),
    "swipe" to mapOf("x1" to "500", "y1" to "1500", "x2" to "500", "y2" to "500", "ms" to "300"),
    "wait" to mapOf("ms" to "1000"),
    "macro" to mapOf("name" to ""),
    "click_img" to mapOf("name" to "", "thr" to "0.95"),
    "if_img" to mapOf("name" to "", "thr" to "0.95", "neg" to "false"),
    "repeat" to mapOf("n" to "3"),
    "until_img" to mapOf("name" to "", "thr" to "0.95", "max" to "20"),
    "break" to emptyMap(),
    "stop" to emptyMap()
)
val BLOCKS = setOf("if_img", "repeat", "until_img")
val IMG = setOf("click_img", "if_img", "until_img")

data class BlockInfo(val type: String, val icon: String, val title: String, val description: String)
val BLOCK_INFO = listOf(
    BlockInfo("tap", "👆", "Dotknij ekranu", "Wykonuje dotknięcie w podanych współrzędnych."),
    BlockInfo("swipe", "↕️", "Przesuń palcem", "Wykonuje gest od punktu początkowego do końcowego."),
    BlockInfo("wait", "⏱️", "Odczekaj", "Zatrzymuje działanie na podaną liczbę milisekund."),
    BlockInfo("macro", "🎞️", "Uruchom makro", "Wykonuje kroki zapisane w wybranym makrze."),
    BlockInfo("click_img", "🖼️", "Znajdź wzorzec i kliknij", "Szuka obrazu na ekranie i dotyka jego znalezionego położenia."),
    BlockInfo("if_img", "❔", "Jeśli wzorzec istnieje", "Wykonuje pierwszą gałąź, gdy obraz jest znaleziony; w przeciwnym razie drugą."),
    BlockInfo("repeat", "🔁", "Powtórz określoną liczbę razy", "Wykonuje zagnieżdżone bloki określoną liczbę razy."),
    BlockInfo("until_img", "🔄", "Powtarzaj aż do wzorca", "Powtarza bloki, dopóki obraz nie pojawi się na ekranie, z limitem prób."),
    BlockInfo("break", "⏹️", "Przerwij pętlę", "Kończy aktualną pętlę powtarzania."),
    BlockInfo("stop", "🛑", "Zatrzymaj bota", "Kończy działanie całej procedury.")
)
fun blockTitle(type: String): String = BLOCK_INFO.firstOrNull { it.type == type }?.title ?: type

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme { App(this) } }
    }
}

@Composable
fun App(a: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Script?>(null) }
    val tabs = listOf("BOT", "Wzorce", "Makra", "Log")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; editing = null },
                        icon = { Text(when (i) { 0 -> "🤖"; 1 -> "🖼️"; 2 -> "🎞️"; else -> "📋" }) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(10.dp)) {
            when {
                editing != null -> Editor(a, editing!!) { editing = null }
                tab == 0 -> BotTab(a) { editing = it }
                tab == 1 -> Patterns(a)
                tab == 2 -> Macros(a) { editing = it }
                else -> LogView()
            }
        }
    }
}

@Composable
fun BotTab(a: MainActivity, open: (Script) -> Unit) {
    var scripts by remember { mutableStateOf(Store.orderedAlgorithms(a)) }
    var selected by remember { mutableStateOf(Store.selectedAlgorithms(a, scripts)) }
    var renaming by remember { mutableStateOf<Script?>(null) }
    var renameStatus by remember { mutableStateOf("") }
    val running by Engine.running.collectAsState()

    fun refresh() {
        scripts = Store.orderedAlgorithms(a)
        selected = Store.selectedAlgorithms(a, scripts)
    }

    Column(Modifier.fillMaxSize()) {
        Text("Algorytmy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Zaznaczone algorytmy uruchomią się w kolejności z tej listy.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val service = BotService.inst
                    if (service == null) a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    else {
                        val queue = scripts.filter { it.name in selected }
                        service.showBotLauncher(queue)
                        a.moveTaskToBack(true)
                    }
                },
                enabled = selected.isNotEmpty() && !running && scripts.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("▶ URUCHOM BOTA") }
            OutlinedButton(onClick = {
                val s = Script(Store.uniqueName(a, "algorytm"), mutableListOf(), "algorithm")
                Store.save(a, s)
                val reordered = scripts + s
                scripts = reordered
                Store.saveAlgorithmOrder(a, reordered.map { it.name })
                selected = selected + s.name
                Store.saveSelectedAlgorithms(a, selected)
                open(s)
            }) { Text("＋ Algorytm") }
        }
        if (BotService.inst == null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Włącz usługę dostępności AutoBot, aby uruchamiać procedury.")
            }
        }
        if (scripts.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Nie ma jeszcze algorytmów. Dodaj pierwszy przyciskiem „＋ Algorytm”.")
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(scripts, key = { it.name }) { script ->
                val index = scripts.indexOfFirst { it.name == script.name }
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            checked = script.name in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + script.name else selected - script.name
                                Store.saveSelectedAlgorithms(a, selected)
                            }
                        )
                        Column(Modifier.weight(1f).clickable { open(script) }.padding(vertical = 6.dp)) {
                            Text(script.name, fontWeight = FontWeight.Medium, maxLines = 3)
                            Text("${script.steps.size} bloków", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { renaming = script }) { Text("Zmień nazwę") }
                        TextButton(onClick = {
                            if (index > 0) {
                                val reordered = scripts.toMutableList()
                                val item = reordered.removeAt(index)
                                reordered.add(index - 1, item)
                                scripts = reordered
                                Store.saveAlgorithmOrder(a, reordered.map { it.name })
                            }
                        }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = {
                            if (index < scripts.lastIndex) {
                                val reordered = scripts.toMutableList()
                                val item = reordered.removeAt(index)
                                reordered.add(index + 1, item)
                                scripts = reordered
                                Store.saveAlgorithmOrder(a, reordered.map { it.name })
                            }
                        }, enabled = index < scripts.lastIndex) { Text("↓") }
                        TextButton(onClick = { open(script) }) { Text("Edytuj") }
                        TextButton(onClick = {
                            Store.delete(a, script.name)
                            refresh()
                        }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
                      }
                    }
                }
            }
        }
    }
    renaming?.let { old ->
        NameDialog("Zmień nazwę algorytmu") { newName ->
            if (!newName.isNullOrBlank()) {
                val ok = Store.renameScript(a, old.name, newName)
                renameStatus = if (ok) "Zmieniono nazwę algorytmu." else "Ta nazwa jest pusta lub zajęta."
                refresh()
            }
            renaming = null
        }
    }
    if (renameStatus.isNotBlank()) AlertDialog(onDismissRequest = { renameStatus = "" }, title = { Text("Algorytm") }, text = { Text(renameStatus) }, confirmButton = { TextButton(onClick = { renameStatus = "" }) { Text("OK") } })
}

@Composable
fun Macros(a: MainActivity, open: (Script) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<Script?>(null) }
    var status by remember { mutableStateOf("") }
    val scripts = remember(version) { Store.macros(a) }
    val running by Engine.running.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text("Makra", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Makra można wykorzystywać w wielu algorytmach. Zmiana nazwy aktualizuje istniejące odwołania.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val service = BotService.inst
                if (service == null) a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                else {
                    service.record { recorded ->
                        if (recorded.isNotEmpty()) {
                            Store.save(a, Script(Store.uniqueName(a, "makro", "macro"), recorded.toMutableList(), "macro"))
                            version++
                            status = "Zapisano nagrane makro."
                        }
                    }
                    a.moveTaskToBack(true)
                }
            }, enabled = !running) { Text("● Nagraj makro") }
            OutlinedButton(onClick = {
                val s = Script(Store.uniqueName(a, "makro", "macro"), mutableListOf(), "macro")
                Store.save(a, s)
                version++
                open(s)
            }) { Text("＋ Puste makro") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (scripts.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Brak zapisanych makr. Nagraj makro lub utwórz puste.")
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(scripts, key = { it.name }) { script ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { open(script) }) {
                                Text("🎞️ ${script.name}", fontWeight = FontWeight.Medium)
                                Text("${script.steps.size} bloków", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { open(script) }) { Text("Edytuj") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { renaming = script }) { Text("Zmień nazwę") }
                            TextButton(onClick = { Store.delete(a, script.name); version++ }) {
                                Text("Usuń", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { old ->
        NameDialog("Nowa nazwa makra") { newName ->
            if (!newName.isNullOrBlank()) {
                val ok = Store.renameScript(a, old.name, newName)
                status = if (ok) "Zmieniono nazwę makra i zaktualizowano odwołania." else "Nie można użyć tej nazwy (jest pusta lub już istnieje)."
                version++
            }
            renaming = null
        }
    }
}

@Composable
fun Editor(a: MainActivity, s: Script, close: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var renameOpen by remember { mutableStateOf(false) }
    var patternTarget by remember { mutableStateOf<Step?>(null) }
    var macroTarget by remember { mutableStateOf<Step?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.kind == "macro") s.name else s.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { renameOpen = true }) { Text("Zmień nazwę") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Store.save(a, s); close() }, modifier = Modifier.weight(1f)) { Text("Zapisz i wróć") }
            OutlinedButton(onClick = close) { Text("Anuluj") }
        }
        Spacer(Modifier.height(8.dp))
        val scrollState = rememberLazyListState()
        LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
            item { Steps(a, s.steps, 0, { version++ }, { patternTarget = it }, { macroTarget = it }) }
        }
        if (renameOpen) NameDialog("Zmień nazwę") { newName ->
            if (!newName.isNullOrBlank()) {
                val oldName = s.name
                if (Store.renameScript(a, oldName, newName)) s.name = Store.clean(newName)
            }
            renameOpen = false
        }
        patternTarget?.let { target ->
            PickerDialog(a, onPick = { value -> target.p["name"] = value; patternTarget = null; version++ }, onDismiss = { patternTarget = null })
        }
        macroTarget?.let { target ->
            MacroPickerDialog(a, onPick = { value -> target.p["name"] = value; macroTarget = null; version++ }, onDismiss = { macroTarget = null })
        }
    }
}

@Composable
fun Steps(
    a: MainActivity,
    list: MutableList<Step>,
    depth: Int,
    changed: () -> Unit,
    requestPattern: (Step) -> Unit,
    requestMacro: (Step) -> Unit
) {
    Column(Modifier.padding(start = (depth * 10).dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        list.toList().forEachIndexed { index, step ->
            var collapsed by remember(step) { mutableStateOf(true) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(BLOCK_INFO.firstOrNull { it.type == step.type }?.icon ?: "▫️")
                        Column(Modifier.weight(1f)) {
                            Text(step.p["_label"]?.takeIf { it.isNotBlank() } ?: blockTitle(step.type), fontWeight = FontWeight.SemiBold, maxLines = 3)
                            TextButton(onClick = { collapsed = !collapsed }, contentPadding = PaddingValues(0.dp)) { Text(if (collapsed) "ROZWIŃ  +" else "ZWIŃ  −") }
                        }
                        TextButton(onClick = {
                            if (index > 0) {
                                val item = list.removeAt(index)
                                list.add(index - 1, item)
                                changed()
                            }
                        }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = {
                            if (index < list.lastIndex) {
                                val item = list.removeAt(index)
                                list.add(index + 1, item)
                                changed()
                            }
                        }, enabled = index < list.lastIndex) { Text("↓") }
                        TextButton(onClick = { list.remove(step); changed() }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                    if (!collapsed) {
                        var blockLabel by remember(step) { mutableStateOf(step.p["_label"].orEmpty()) }
                        OutlinedTextField(value = blockLabel, onValueChange = { blockLabel = it; if (it.isBlank()) step.p.remove("_label") else step.p["_label"] = it }, label = { Text("Własna nazwa bloku (opcjonalnie)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (step.type in setOf("tap", "swipe", "macro")) {
                            OutlinedButton(
                                onClick = {
                                    val service = BotService.inst
                                    if (service != null) {
                                        service.record { recorded ->
                                            when (step.type) {
                                                "tap" -> recorded.firstOrNull { it.type == "tap" }?.let { r ->
                                                    listOf("x", "y", "ms").forEach { k -> r.p[k]?.let { step.p[k] = it } }
                                                    changed()
                                                }
                                                "swipe" -> recorded.firstOrNull { it.type == "swipe" }?.let { r ->
                                                    listOf("x1", "y1", "x2", "y2", "ms").forEach { k -> r.p[k]?.let { step.p[k] = it } }
                                                    changed()
                                                }
                                                "macro" -> if (recorded.isNotEmpty()) {
                                                    val name = Store.uniqueName(a, "makro", "macro")
                                                    Store.save(a, Script(name, recorded.toMutableList(), "macro"))
                                                    step.p["name"] = name
                                                    changed()
                                                }
                                            }
                                        }
                                        a.moveTaskToBack(true)
                                    }
                                },
                                enabled = BotService.inst != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(when (step.type) {
                                    "tap" -> "● Nagraj dotknięcie"
                                    "swipe" -> "● Nagraj gest przesunięcia"
                                    else -> "● Nagraj i przypisz makro"
                                })
                            }
                        }
                        step.p.keys.filter { it != "_comment" && it != "_label" }.toList().forEach { key ->
                            var value by remember(step, key) { mutableStateOf(step.p[key].orEmpty()) }
                            when {
                                key == "name" && step.type in IMG -> {
                                    OutlinedButton(
                                        onClick = { requestPattern(step) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (value.isBlank()) "Wybierz wzorzec…" else "🖼️ $value") }
                                }
                                key == "name" && step.type == "macro" -> {
                                    OutlinedButton(
                                        onClick = { requestMacro(step) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (value.isBlank()) "Wybierz makro…" else "🎞️ $value") }
                                }
                                key == "neg" -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Warunek: wzorzec ma nie występować", Modifier.weight(1f))
                                        Switch(checked = value == "true", onCheckedChange = {
                                            value = it.toString(); step.p[key] = value; changed()
                                        })
                                    }
                                }
                                else -> OutlinedTextField(
                                    value = value,
                                    onValueChange = { value = it; step.p[key] = it },
                                    label = { Text(parameterTitle(key)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (step.type in BLOCKS) {
                            Text("Wykonaj", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Steps(a, step.body, depth + 1, changed, requestPattern, requestMacro)
                            if (step.type == "if_img") {
                                Text("W przeciwnym razie", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Steps(a, step.els, depth + 1, changed, requestPattern, requestMacro)
                            }
                        }
                    }
                }
            }
        }
        var adding by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Dodaj blok") }
        if (adding) {
            AlertDialog(
                onDismissRequest = { adding = false },
                title = { Text("Wybierz blok") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(BLOCK_INFO, key = { it.type }) { info ->
                            Card(Modifier.fillMaxWidth().clickable {
                                list.add(Step(info.type, DEF[info.type]!!.toMutableMap()))
                                adding = false
                                changed()
                            }) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(info.icon, style = MaterialTheme.typography.titleLarge)
                                    Column {
                                        Text(info.title, fontWeight = FontWeight.SemiBold)
                                        Text(info.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { adding = false }) { Text("Zamknij") } }
            )
        }
    }
}

@Composable
fun parameterTitle(key: String): String = when (key) {
    "x" -> "Współrzędna X"
    "y" -> "Współrzędna Y"
    "x1" -> "Początek X"
    "y1" -> "Początek Y"
    "x2" -> "Koniec X"
    "y2" -> "Koniec Y"
    "ms" -> "Czas (ms)"
    "name" -> "Nazwa"
    "thr" -> "Próg dopasowania (0–1)"
    "n" -> "Liczba powtórzeń"
    "max" -> "Maksymalna liczba prób"
    else -> key
}

@Composable
fun Patterns(a: MainActivity) {
    var folder by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var importFolder by remember { mutableStateOf("") }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = a.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
                if (bitmap == null) status = "Nie udało się odczytać obrazu."
                else { pendingBitmap = bitmap; status = "" }
            } catch (e: Exception) {
                status = "Nie udało się odczytać obrazu: ${e.message ?: "błąd"}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Wzorce", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Wybierz obraz z pamięci telefonu lub zarządzaj zapisanymi wzorcami.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { importFolder = folder; imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("🖼️ Dodaj wzorzec z telefonu")
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(5.dp))
        Browser(a, folder, { folder = it }, null, version)
    }

    if (pendingBitmap != null) {
        NameDialog("Nazwa importowanego wzorca") { name ->
            val bitmap = pendingBitmap
            var keepDialogOpen = false
            if (!name.isNullOrBlank() && bitmap != null) {
                val id = Store.join(importFolder, name)
                if (Store.patternExists(a, id)) {
                    status = "Wzorzec o tej nazwie już istnieje. Wpisz inną nazwę."
                    keepDialogOpen = true
                } else {
                    runCatching { Store.savePattern(a, id, bitmap) }
                        .onSuccess {
                            status = "Dodano wzorzec: ${id.substringAfterLast('/')}"
                            version++
                        }
                        .onFailure { status = "Nie udało się zapisać wzorca: ${it.message ?: "błąd zapisu"}" }
                }
            }
            if (!keepDialogOpen) {
                bitmap?.recycle()
                pendingBitmap = null
            }
        }
    }
}

/** Pattern folder browser; a non-null pick callback turns it into a selection dialog. */
@Composable
fun Browser(
    a: MainActivity,
    folder: String,
    setFolder: (String) -> Unit,
    pick: ((String) -> Unit)?,
    refreshToken: Int = 0
) {
    var version by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val (dirs, patterns) = remember(folder, version, refreshToken) { Store.listDir(a, folder) }
    Column(Modifier.fillMaxWidth()) {
        Text("📁 /$folder", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (folder.isNotEmpty()) TextButton(onClick = { setFolder(folder.substringBeforeLast('/', "")) }) { Text("↑ Wyżej") }
            TextButton(onClick = { newFolder = true }) { Text("＋ Folder") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(dirs, key = { it }) { dir ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📁 ${dir.substringAfterLast('/')}", Modifier.weight(1f).clickable { setFolder(dir) }.padding(8.dp))
                    if (pick == null) TextButton(onClick = { Store.deleteFolder(a, dir); version++ }) { Text("Usuń") }
                }
            }
            items(patterns, key = { it }) { id ->
                val bitmap = remember(id, version, refreshToken) { Store.pattern(a, id) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(48.dp), contentScale = ContentScale.Fit)
                            Text(
                                id.substringAfterLast('/'),
                                Modifier.weight(1f).then(if (pick != null) Modifier.clickable { pick(id) } else Modifier).padding(vertical = 8.dp)
                            )
                        }
                        if (pick == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { renameTarget = id }) { Text("Zmień nazwę") }
                            TextButton(onClick = { Store.deletePattern(a, id); version++ }) {
                                Text("Usuń", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    if (newFolder) NameDialog("Nazwa folderu") { name ->
        if (!name.isNullOrBlank()) { Store.createFolder(a, Store.join(folder, name)); version++ }
        newFolder = false
    }
    renameTarget?.let { oldId ->
        NameDialog("Nowa nazwa wzorca") { name ->
            if (!name.isNullOrBlank()) {
                val ok = Store.renamePattern(a, oldId, name)
                status = if (ok) "Zmieniono nazwę wzorca i zaktualizowano odwołania." else "Nie można zmienić nazwy: sprawdź, czy taka nazwa już istnieje."
                version++
            }
            renameTarget = null
        }
    }
}

@Composable
fun PickerDialog(a: MainActivity, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var folder by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    val phoneImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = a.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
                if (bitmap == null) importError = "Nie udało się odczytać obrazu."
                else {
                    val id = Store.join(folder, "telefon_${System.currentTimeMillis()}")
                    Store.savePattern(a, id, bitmap)
                    bitmap.recycle()
                    onPick(id)
                }
            } catch (e: Exception) {
                importError = "Nie udało się zaimportować obrazu: ${e.message ?: "błąd odczytu"}"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Anuluj") } },
        title = { Text("Wybierz wzorzec") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { phoneImagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("🖼️ Wybierz obraz z telefonu")
                }
                if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
                Browser(a, folder, { folder = it }, onPick)
            }
        }
    )
}

@Composable
fun MacroPickerDialog(a: MainActivity, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val macros = remember { Store.macros(a) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Anuluj") } },
        title = { Text("Wybierz zapisane makro") },
        text = {
            if (macros.isEmpty()) Text("Nie zapisano jeszcze żadnych makr. Nagraj makro w zakładce Makra.")
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(macros, key = { it.name }) { macro ->
                    TextButton(onClick = { onPick(macro.name) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("🎞️ ${macro.name}", Modifier.weight(1f))
                            Text("${macro.steps.size} bloków", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun NameDialog(title: String, done: (String?) -> Unit) {
    var value by remember(title) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { done(null) },
        confirmButton = { TextButton(onClick = { done(value.trim()) }, enabled = value.isNotBlank()) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = { done(null) }) { Text("Anuluj") } },
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Nazwa") }, singleLine = true) }
    )
}

@Composable
fun LogView() {
    val log by Engine.log.collectAsState()
    val paused by Engine.paused.collectAsState()
    val running by Engine.running.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text("Log działania", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Engine.paused.value = !paused }, enabled = running) { Text(if (paused) "Wznów" else "Wstrzymaj") }
            Button(onClick = { Engine.stop() }, enabled = running) { Text("STOP") }
            TextButton(onClick = { Engine.log.value = emptyList() }) { Text("Wyczyść") }
        }
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
            items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
