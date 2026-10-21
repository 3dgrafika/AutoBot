package pl.bot
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlin.math.min

val DEF = mapOf(
    "tap" to mapOf("x" to "500", "y" to "500", "ms" to "50"),
    "swipe" to mapOf("x1" to "500", "y1" to "1500", "x2" to "500", "y2" to "500", "ms" to "300"),
    "wait" to mapOf("ms" to "1000"),
    "macro" to mapOf("name" to ""),
    "click_img" to mapOf("name" to "", "thr" to "0.95"),
    "if_img" to mapOf("name" to "", "thr" to "0.95", "neg" to "false"),
    "repeat" to mapOf("n" to "3"),
    "until_img" to mapOf("name" to "", "thr" to "0.95", "max" to "20"),
    "break" to mapOf(), "stop" to mapOf())
val BLOCKS = setOf("if_img", "repeat", "until_img")
val IMG = setOf("click_img", "if_img", "until_img")

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { MaterialTheme { App(this) } } }
}

@Composable fun App(a: MainActivity) {
    var tab by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<Script?>(null) }
    Scaffold(bottomBar = { NavigationBar { listOf("Skrypty", "Wzorce", "Log").forEachIndexed { i, t ->
        NavigationBarItem(tab == i, { tab = i; edit = null }, {}, label = { Text(t) }) } } }) { pad ->
        Box(Modifier.padding(pad).padding(8.dp)) {
            when { edit != null -> Editor(a, edit!!) { edit = null }
                tab == 0 -> Scripts(a) { edit = it }
                tab == 1 -> Patterns(a)
                else -> LogView() }
        }
    }
}

@Composable fun Scripts(a: MainActivity, open: (Script) -> Unit) {
    var list by remember { mutableStateOf(Store.list(a)) }
    val run by Engine.running.collectAsState()
    fun newName(p: String): String { var n = 1; while (list.any { it.name == "$p$n" }) n++; return "$p$n" }
    Column {
        if (BotService.inst == null) Button({ a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Włącz usługę dostępności (AutoBot)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ val s = Script(newName("algorytm")); Store.save(a, s); list = Store.list(a); open(s) }) { Text("Nowy") }
            Button({ BotService.inst?.record { st -> if (st.isNotEmpty()) { Store.save(a, Script(newName("makro"), st.toMutableList())); list = Store.list(a) } }; a.moveTaskToBack(true) },
                enabled = BotService.inst != null) { Text("Nagraj") }
            if (run) Button({ Engine.stop() }) { Text("STOP") }
        }
        LazyColumn { items(list) { s -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.name} (${s.steps.size})", Modifier.weight(1f).clickable { open(s) })
            Button({ Engine.start(a, s) }, enabled = !run) { Text("▶") }
            Button({ BotService.inst?.showControl(s); a.moveTaskToBack(true) }, enabled = BotService.inst != null) { Text("📌") }
            TextButton({ Store.delete(a, s.name); list = Store.list(a) }) { Text("🗑") }
            TextButton({ Store.save(a, Script(newName(s.name + "_kopia"), s.steps.toMutableList())); list = Store.list(a) }) { Text("⧉") }
        } } }
    }
}

@Composable fun Editor(a: MainActivity, s: Script, close: () -> Unit) {
    var ver by remember { mutableIntStateOf(0) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ Store.save(a, s); close() }) { Text("Zapisz") }
            Button({ Store.save(a, s); Engine.start(a, s) }) { Text("Zapisz ▶") }
            Button({ Store.save(a, s); BotService.inst?.showControl(s); a.moveTaskToBack(true) }, enabled = BotService.inst != null) { Text("📌") }
            Button({ Engine.stop() }) { Text("STOP") }
        }
        key(ver) { LazyColumn { item { Steps(s.steps, 0) { ver++ } } } }
    }
}

@Composable fun Steps(l: MutableList<Step>, d: Int, ch: () -> Unit) {
    Column(Modifier.padding(start = (d * 12).dp)) {
        l.toList().forEach { st ->
            Card(Modifier.fillMaxWidth().padding(2.dp)) { Column(Modifier.padding(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(st.type, Modifier.weight(1f)); TextButton({ l.remove(st); ch() }) { Text("✕") } }
                Row { st.p.keys.toList().forEach { k -> var v by remember { mutableStateOf(st.p[k]!!) }
                    if (k == "name" && st.type in IMG) { var dlg by remember { mutableStateOf(false) }
                        OutlinedButton({ dlg = true }, Modifier.weight(1f)) { Text(if (v.isBlank()) "wybierz wzorzec…" else v) }
                        if (dlg) PickerDialog(LocalContext.current as MainActivity, { v = it; st.p[k] = it; dlg = false }) { dlg = false }
                    } else OutlinedTextField(v, { v = it; st.p[k] = it }, label = { Text(k) }, modifier = Modifier.weight(1f), singleLine = true) } }
                if (st.type in BLOCKS) { Steps(st.body, d + 1, ch)
                    if (st.type == "if_img") { Text("w przeciwnym razie:"); Steps(st.els, d + 1, ch) } }
            } }
        }
        var m by remember { mutableStateOf(false) }
        Box { TextButton({ m = true }) { Text("+ dodaj blok") }
            DropdownMenu(m, { m = false }) { DEF.keys.forEach { t ->
                DropdownMenuItem({ Text(t) }, { l.add(Step(t, DEF[t]!!.toMutableMap())); m = false; ch() }) } } }
    }
}

@Composable fun Patterns(a: MainActivity) {
    var folder by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var msg by remember { mutableStateOf("") }
    val sc = rememberCoroutineScope()
    if (shot != null) Crop(shot!!, folder) { bmp, name ->
        msg = if (bmp != null) { Store.savePattern(a, Store.join(folder, name), bmp); "Zapisano: ${Store.join(folder, name)}" } else ""
        shot = null }
    else Column {
        Button({ sc.launch { for (i in 5 downTo 1) { msg = "Zrzut za $i s – przełącz na docelową aplikację, potem wróć tutaj"; delay(1000) }
            shot = BotService.inst?.shot(); msg = if (shot == null) "Brak zrzutu (włącz usługę dostępności)" else "" } },
            enabled = BotService.inst != null) { Text("Nowy wzorzec w /$folder") }
        Text(msg)
        Browser(a, folder, { folder = it }, null)
    }
}

/** Przeglądarka folderów i wzorców. pick == null: tryb zarządzania (usuwanie); pick != null: wybór wzorca. */
@Composable fun Browser(a: MainActivity, folder: String, setFolder: (String) -> Unit, pick: ((String) -> Unit)?) {
    var ver by remember { mutableIntStateOf(0) }
    var newF by remember { mutableStateOf(false) }
    val (dirs, pats) = remember(folder, ver) { Store.listDir(a, folder) }
    Column {
        Text("📁 /$folder")
        Row { if (folder.isNotEmpty()) TextButton({ setFolder(folder.substringBeforeLast('/', "")) }) { Text("↑ wyżej") }
            TextButton({ newF = true }) { Text("+ nowy folder") } }
        LazyColumn(Modifier.heightIn(max = 450.dp)) {
            items(dirs) { d -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📁 ${d.substringAfterLast('/')}", Modifier.weight(1f).clickable { setFolder(d) }.padding(8.dp))
                if (pick == null) TextButton({ Store.deleteFolder(a, d); ver++ }) { Text("Usuń") } } }
            items(pats) { id -> Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = remember(id) { Store.pattern(a, id) }
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(48.dp), contentScale = ContentScale.Fit)
                Text(id.substringAfterLast('/'), Modifier.weight(1f).then(if (pick != null) Modifier.clickable { pick(id) } else Modifier).padding(8.dp))
                if (pick == null) TextButton({ Store.deletePattern(a, id); ver++ }) { Text("Usuń") } } }
        }
    }
    if (newF) NameDialog("Nazwa folderu") { n -> if (!n.isNullOrBlank()) { Store.createFolder(a, Store.join(folder, n)); ver++ }; newF = false }
}

@Composable fun PickerDialog(a: MainActivity, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var folder by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    val phoneImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = a.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
                if (bitmap == null) {
                    importError = "Nie udało się odczytać wybranego obrazu."
                } else {
                    val baseName = "telefon_${System.currentTimeMillis()}"
                    val id = Store.join(folder, baseName)
                    Store.savePattern(a, id, bitmap)
                    bitmap.recycle()
                    onPick(id)
                }
            } catch (e: Exception) {
                importError = "Nie udało się zaimportować obrazu: ${e.message ?: "błąd odczytu"}"
            }
        }
    }
    AlertDialog(onDismiss, confirmButton = { TextButton(onDismiss) { Text("Anuluj") } },
        title = { Text("Wybierz wzorzec") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ phoneImagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("🖼 Wybierz obraz z telefonu")
                }
                Text("Obraz zostanie skopiowany do wzorców aplikacji.")
                if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
                Browser(a, folder, { folder = it }, onPick)
            }
        })
}

@Composable fun NameDialog(title: String, done: (String?) -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog({ done(null) }, confirmButton = { TextButton({ done(t) }) { Text("OK") } },
        dismissButton = { TextButton({ done(null) }) { Text("Anuluj") } }, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) })
}

@Composable fun Crop(b: Bitmap, folder: String, done: (Bitmap?, String) -> Unit) {
    var p1 by remember { mutableStateOf(Offset.Zero) }; var p2 by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }; var name by remember { mutableStateOf("") }
    Column {
        Text("Folder: /$folder – przeciągnij palcem po obrazie, by zaznaczyć element")
        OutlinedTextField(name, { name = it }, label = { Text("nazwa wzorca") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ val k = b.width.toFloat() / size.width
                val x = (min(p1.x, p2.x) * k).toInt().coerceIn(0, b.width - 1); val y = (min(p1.y, p2.y) * k).toInt().coerceIn(0, b.height - 1)
                val w = (abs(p2.x - p1.x) * k).toInt().coerceIn(1, b.width - x); val h = (abs(p2.y - p1.y) * k).toInt().coerceIn(1, b.height - y)
                done(Bitmap.createBitmap(b, x, y, w, h), name) },
                enabled = name.isNotBlank() && abs(p2.x - p1.x) > 4 && abs(p2.y - p1.y) > 4) { Text("💾 Zapisz wzorzec") }
            TextButton({ done(null, "") }) { Text("Anuluj") }
        }
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { p1 = it; p2 = it }) { c, _ -> p2 = c.position } }
            .drawWithContent { drawContent()
                drawRect(Color.Red, Offset(min(p1.x, p2.x), min(p1.y, p2.y)), Size(abs(p2.x - p1.x), abs(p2.y - p1.y)), style = Stroke(3f)) })
    }
}

@Composable fun LogView() {
    val log by Engine.log.collectAsState(); val paused by Engine.paused.collectAsState()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ Engine.paused.value = !paused }) { Text(if (paused) "Wznów" else "Wstrzymaj") }
            Button({ Engine.stop() }) { Text("STOP") }
            TextButton({ Engine.log.value = emptyList() }) { Text("Wyczyść") }
        }
        LazyColumn(reverseLayout = true) { items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
