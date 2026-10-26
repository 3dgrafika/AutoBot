package pl.autobot.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.autobot.app.App
import pl.autobot.app.data.PatternInfo
import pl.autobot.app.service.BotAccessibilityService
import kotlin.math.abs
import kotlin.math.min

private fun parentOf(path: String): String = path.substringBeforeLast('/', "")
private fun displayPath(folder: String): String = folderLabel(folder)

private fun decodeUri(ctx: android.content.Context, uri: Uri): Bitmap? = try {
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) {
    null
}

@Composable
fun PatternsScreen(app: App, folder: String, onFolderChange: (String) -> Unit, onCrop: () -> Unit) {
    val ctx = LocalContext.current
    val patterns by app.repo.patterns.collectAsState()
    val folders by app.repo.folders.collectAsState()
    var addDialog by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PatternInfo?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bmp = decodeUri(ctx, uri)
            if (bmp != null) { PendingImage.bitmap = bmp; onCrop() } else ctx.toast("Nie udało się wczytać obrazu")
        }
    }

    val subfolders = folders.filter { parentOf(it) == folder }.sorted()
    val here = patterns.filter { it.folder == folder }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Icons.Default.ImageIcon, "Wzorce",
            "Dodaj wzór obrazu z pamięci telefonu i zarządzaj zapisanymi wzorami"
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Bg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AKTUALNA ŚCIEŻKA GALERII", fontSize = 10.sp, color = Muted)
                    Text(displayPath(folder), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (folder.isNotEmpty()) {
                    IconButton({ onFolderChange(parentOf(folder)) }) { Icon(Icons.Default.ArrowUpward, "Folder wyżej") }
                }
                Box {
                    IconButton({ folderMenu = true }) { Icon(Icons.Default.Edit, "Zarządzaj folderami") }
                    DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                        DropdownMenuItem(text = { Text("+ NOWY FOLDER") }, onClick = { folderMenu = false; newFolderDialog = true })
                        DropdownMenuItem(
                            text = { Text("ZMIANA NAZWY") }, enabled = folder.isNotEmpty(),
                            onClick = { folderMenu = false; renameDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("USUŃ", color = if (folder.isNotEmpty()) Danger else Color.Unspecified) }, enabled = folder.isNotEmpty(),
                            onClick = { folderMenu = false; deleteDialog = true }
                        )
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(subfolders, key = { "f$it" }) { f ->
                    WhiteCard(onClick = { onFolderChange(f) }) {
                        Icon(Icons.Default.Folder, null)
                        Text(f.substringAfterLast('/'), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
                items(here, key = { it.id }) { p ->
                    val img = remember(p.id, p.createdAt) { app.repo.patternBitmap(p.id)?.asImageBitmap() }
                    WhiteCard {
                        Box(Modifier.size(56.dp).background(Color(0xFFEFEFEF)), contentAlignment = Alignment.Center) {
                            if (img != null) Image(img, null, Modifier.size(56.dp), contentScale = ContentScale.Fit)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                            Text("${p.width}×${p.height} px · dokładność ${(p.threshold * 100).toInt()}%", fontSize = 11.sp, color = Muted)
                        }
                        IconButton({ editing = p }, Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Edytuj") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AppButton("+ NOWY WZORZEC", Primary) { addDialog = true }
            }
        }
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Nowy wzorzec") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wzorzec powinien pochodzić z tego samego telefonu (ta sama rozdzielczość), co ekran, na którym bot będzie go szukał.", fontSize = 12.sp, color = Muted)
                    AppButton("Z GALERII", Primary, Modifier.fillMaxWidth()) { addDialog = false; gallery.launch("image/*") }
                    AppButton("ZRZUT EKRANU (PŁYWAJĄCY PRZYCISK)", Primary, Modifier.fillMaxWidth()) {
                        addDialog = false
                        val svc = BotAccessibilityService.instance
                        if (svc == null) {
                            ctx.toast("Najpierw włącz usługę dostępności")
                        } else {
                            if (svc.startScreenshotButton()) ctx.findActivity()?.moveTaskToBack(true)
                        }
                    }
                    Text("Aplikacja się zminimalizuje i pojawi się pływający przycisk „ZRÓB ZRZUT”. Przejdź na właściwy ekran i naciśnij go, kiedy chcesz wykonać zrzut.", fontSize = 11.sp, color = Muted)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ addDialog = false }) { Text("ANULUJ") } }
        )
    }

    if (newFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("Nowy folder") },
            text = { OutlinedTextField(name, { name = it.filter { c -> c != '/' } }, label = { Text("Nazwa folderu") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    if (name.isNotBlank()) {
                        val path = if (folder.isEmpty()) name.trim() else "$folder/${name.trim()}"
                        app.repo.addFolder(path)
                        onFolderChange(path)
                    }
                    newFolderDialog = false
                }) { Text("UTWÓRZ") }
            },
            dismissButton = { TextButton({ newFolderDialog = false }) { Text("ANULUJ") } }
        )
    }

    if (renameDialog) {
        var name by remember { mutableStateOf(folder.substringAfterLast('/')) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Zmiana nazwy folderu") },
            text = { OutlinedTextField(name, { name = it.filter { c -> c != '/' } }, label = { Text("Nowa nazwa") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val np = app.repo.renameFolder(folder, name)
                    if (np == null) ctx.toast("Folder o takiej nazwie już istnieje") else onFolderChange(np)
                    renameDialog = false
                }, enabled = name.isNotBlank()) { Text("ZAPISZ") }
            },
            dismissButton = { TextButton({ renameDialog = false }) { Text("ANULUJ") } }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Usunąć folder „${folder.substringAfterLast('/')}”?") },
            text = { Text("Podfoldery zostaną usunięte, a wzorce z ich wnętrza przeniesione do folderu nadrzędnego (nie są kasowane).", fontSize = 13.sp) },
            confirmButton = {
                TextButton({
                    val parent = parentOf(folder)
                    app.repo.deleteFolder(folder)
                    onFolderChange(parent)
                    deleteDialog = false
                }) { Text("USUŃ", color = Danger) }
            },
            dismissButton = { TextButton({ deleteDialog = false }) { Text("ANULUJ") } }
        )
    }

    editing?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        var moveTo by remember(p.id) { mutableStateOf(p.folder) }
        var thr by remember(p.id) { mutableStateOf(p.threshold) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edytuj wzorzec") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PatternThumb(app, p.id, 56.dp)
                        OutlinedTextField(name, { name = it }, label = { Text("NAZWA WZORCA") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Text("Folder:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Picker(
                        "Wybierz folder…",
                        (listOf("") + folders.sorted()).map { it to folderLabel(it) },
                        moveTo
                    ) { moveTo = it }
                    Text("Dokładność dopasowania: ${(thr * 100).toInt()}%", fontSize = 13.sp)
                    Slider(thr, { thr = it }, valueRange = 0.5f..1f)
                    Text("Wyżej = bardziej rygorystycznie. Przy zmiennym tle spróbuj 80–90%.", fontSize = 11.sp, color = Muted)
                    AppButton("TESTUJ NA EKRANIE (za 4 s)", Primary, Modifier.fillMaxWidth()) {
                        app.repo.updatePattern(p.copy(name = name.ifBlank { p.name }, threshold = thr, folder = moveTo))
                        val svc = BotAccessibilityService.instance
                        if (svc == null) {
                            ctx.toast("Najpierw włącz usługę dostępności")
                        } else {
                            svc.showBotPanel()
                            app.engine.testPattern(p.id)
                            editing = null
                            ctx.findActivity()?.moveTaskToBack(true)
                        }
                    }
                    AppButton("USUŃ WZORZEC", Danger, Modifier.fillMaxWidth()) { app.repo.deletePattern(p.id); editing = null }
                }
            },
            confirmButton = {
                TextButton({
                    app.repo.updatePattern(p.copy(name = name.ifBlank { p.name }, threshold = thr, folder = moveTo))
                    editing = null
                }) { Text("ZAPISZ") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("ANULUJ") } }
        )
    }
}

/** Proste narzędzie do wycinania wzorca z obrazu: przeciągnij palcem, aby zaznaczyć prostokąt. */
@Composable
fun CropScreen(app: App, folder: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = PendingImage.bitmap
    if (bmp == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    var name by remember { mutableStateOf("Wzorzec ${app.repo.patterns.value.size + 1}") }
    var start by remember { mutableStateOf<Offset?>(null) }   // współrzędne w pikselach bitmapy
    var end by remember { mutableStateOf<Offset?>(null) }

    fun selection(): android.graphics.Rect? {
        val s = start ?: return null
        val e = end ?: return null
        val l = min(s.x, e.x).toInt().coerceIn(0, bmp.width - 1)
        val t = min(s.y, e.y).toInt().coerceIn(0, bmp.height - 1)
        val r = maxOf(s.x, e.x).toInt().coerceIn(l + 1, bmp.width)
        val b = maxOf(s.y, e.y).toInt().coerceIn(t + 1, bmp.height)
        return android.graphics.Rect(l, t, r, b)
    }

    val sel = selection()
    val valid = sel == null || (sel.width() >= 8 && sel.height() >= 8)

    Column(Modifier.fillMaxSize().background(Bg)) {
        ScreenHeader(
            Icons.Default.ImageIcon, "Nowy wzorzec", "Zaznacz palcem fragment, który bot ma rozpoznawać.",
            onBack = { PendingImage.bitmap = null; onDone() }
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val maxW = with(density) { maxWidth.toPx() }
            val maxH = with(density) { maxHeight.toPx() }
            val scale = min(maxW / bmp.width, maxH / bmp.height)
            val wDp = with(density) { (bmp.width * scale).toDp() }
            val hDp = with(density) { (bmp.height * scale).toDp() }
            val img = remember(bmp) { bmp.asImageBitmap() }
            Box(Modifier.size(wDp, hDp)) {
                Image(img, null, Modifier.fillMaxSize())
                Canvas(
                    Modifier.fillMaxSize().pointerInput(scale) {
                        detectDragGestures(
                            onDragStart = { o -> start = o / scale; end = o / scale },
                            onDrag = { change, _ -> change.consume(); end = change.position / scale }
                        )
                    }
                ) {
                    val s = start
                    val e = end
                    if (s != null && e != null) {
                        val tl = Offset(min(s.x, e.x) * scale, min(s.y, e.y) * scale)
                        val sz = Size(abs(s.x - e.x) * scale, abs(s.y - e.y) * scale)
                        drawRect(Color(0x332F5BEA), tl, sz)
                        drawRect(Color(0xFF2F5BEA), tl, sz, style = Stroke(3f))
                    }
                }
            }
        }
        Column(Modifier.background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("NAZWA WZORCA") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                when {
                    sel == null -> "Brak zaznaczenia – zapisany zostanie cały obraz (${bmp.width}×${bmp.height})."
                    !valid -> "Zaznaczenie jest zbyt małe (min. 8×8 px)."
                    else -> "Zaznaczono ${sel.width()}×${sel.height()} px."
                },
                fontSize = 12.sp, color = Muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("ANULUJ", Color(0xFF55657A)) { PendingImage.bitmap = null; onDone() }
                AppButton("ZAPISZ WZORZEC", Primary, enabled = valid && name.isNotBlank()) {
                    val out = if (sel == null) bmp else Bitmap.createBitmap(bmp, sel.left, sel.top, sel.width(), sel.height())
                    app.repo.addPattern(name.trim(), folder, out)
                    PendingImage.bitmap = null
                    ctx.toast("Zapisano wzorzec „${name.trim()}”")
                    onDone()
                }
            }
        }
    }
}
