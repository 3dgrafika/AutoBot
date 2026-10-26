package pl.autobot.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.autobot.app.App
import pl.autobot.app.data.*
import pl.autobot.app.service.BotAccessibilityService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Id algorytmu aktualnie edytowanego – potrzebne, by nie pozwolić na rekurencję w blokach ALGORYTM. */
private val LocalAlgId = compositionLocalOf { "" }

private fun fmtDateTime(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private fun RepeatUnit.label(n: Int): String = when (this) {
    RepeatUnit.MINUTES -> "min"
    RepeatUnit.HOURS -> "godz."
    RepeatUnit.DAYS -> if (n == 1) "dzień" else "dni"
    RepeatUnit.WEEKS -> if (n == 1) "tydzień" else if (n % 10 in 2..4 && n % 100 !in 12..14) "tygodnie" else "tygodni"
}

/* ───────── opisy bloków ───────── */

private fun Block.title(): String = when (this) {
    is TapBlock -> "KLIKNIJ"
    is SwipeBlock -> "PRZESUŃ"
    is WaitBlock -> "CZEKAJ"
    is FindClickBlock -> "ZNAJDŹ WZORZEC I KLIKNIJ GO"
    is IfPatternBlock -> "JEŻELI WZORZEC ${if (negate) "NIE " else ""}ISTNIEJE TO…"
    is LoopBlock -> "PĘTLA"
    is BreakBlock -> "PRZERWIJ PĘTLĘ"
    is ContinueBlock -> "POMIŃ ITERACJĘ"
    is EndAlgorithmBlock -> "PRZERWIJ AKTUALNY ALGORYTM I IDŹ DALEJ"
    is StopBotBlock -> "ZATRZYMAJ BOTA"
    is AlgorithmBlock -> "ALGORYTM"
    is ForEachPatternBlock -> "DLA KAŻDEGO WZORCA Z ZESTAWU"
    is AtTimeBlock -> "O OKREŚLONEJ DACIE I GODZINIE"
    is RepeatEveryBlock -> "POWTARZAJ CO…"
}

private fun Block.color(): Color = when (this) {
    is TapBlock, is SwipeBlock, is FindClickBlock -> BlockAction
    is IfPatternBlock -> BlockIf
    is LoopBlock, is ForEachPatternBlock -> BlockLoop
    is WaitBlock, is AtTimeBlock, is RepeatEveryBlock -> BlockTime
    is AlgorithmBlock, is BreakBlock, is ContinueBlock, is EndAlgorithmBlock, is StopBotBlock -> BlockControl
}

private fun Block.accent(): Color = when (this) {
    is TapBlock, is SwipeBlock, is FindClickBlock -> BlockActionAccent
    is IfPatternBlock -> BlockIfAccent
    is LoopBlock, is ForEachPatternBlock -> BlockLoopAccent
    is WaitBlock, is AtTimeBlock, is RepeatEveryBlock -> BlockTimeAccent
    is AlgorithmBlock, is BreakBlock, is ContinueBlock, is EndAlgorithmBlock, is StopBotBlock -> BlockControlAccent
}

/** Wszystkie jasne tła – tekst zawsze ciemny. */
private fun Block.darkBg() = false

private fun Block.isContainer() = this is IfPatternBlock || this is LoopBlock || this is ForEachPatternBlock ||
    this is AtTimeBlock || this is RepeatEveryBlock
private fun Block.hasParams() = !(this is BreakBlock || this is ContinueBlock || this is EndAlgorithmBlock || this is StopBotBlock)

private fun Block.summary(patterns: List<PatternInfo>, algs: List<Algorithm>): Pair<String, Boolean> {
    fun pat(id: String?) = patterns.firstOrNull { it.id == id }?.name
    fun thr(t: Float?) = if (t == null) "domyślna dokładność" else "dokładność ${(t * 100).toInt()}%"
    return when (this) {
        is TapBlock -> "($x, $y) · ${durationMs} ms" to false
        is SwipeBlock -> "($x1, $y1) → ($x2, $y2) · ${durationMs} ms" to false
        is WaitBlock -> "$ms ms" to false
        is FindClickBlock -> {
            val p = pat(patternId)
            (p?.let { "„$it” · ${thr(threshold)} · limit ${timeoutMs} ms" } ?: "⚠ wybierz wzorzec") to (p == null)
        }
        is IfPatternBlock -> {
            val p = pat(patternId)
            (p?.let { "„$it” · ${thr(threshold)}" } ?: "⚠ wybierz wzorzec") to (p == null)
        }
        is LoopBlock -> when (mode) {
            LoopMode.COUNT -> "$count× " to false
            LoopMode.UNTIL_FOUND, LoopMode.UNTIL_MISSING -> {
                val p = pat(patternId)
                val what = if (mode == LoopMode.UNTIL_FOUND) "do pojawienia się" else "do zniknięcia"
                (p?.let { "$what „$it” (maks. $maxIterations)" } ?: "⚠ wybierz wzorzec") to (p == null)
            }
        }
        is AlgorithmBlock -> {
            val a = algs.firstOrNull { it.id == algorithmId }
            (a?.let { "„${it.name}” × $repeat" } ?: "⚠ wybierz algorytm") to (a == null)
        }
        is ForEachPatternBlock -> {
            val names = patternIds.map { pat(it) }
            val bad = patternIds.isEmpty() || names.any { it == null }
            val text = if (patternIds.isEmpty()) "⚠ dodaj wzorce do zestawu"
            else names.joinToString(", ") { it ?: "⚠ usunięty" } + " · ${thr(threshold)}" + if (clickFound) " · klika każdy" else ""
            text to bad
        }
        is AtTimeBlock ->
            "${fmtDateTime(atMs)} · gdy termin minął: ${if (ifPast == PastAction.SKIP) "pomiń" else "wykonaj od razu"}" to false
        is RepeatEveryBlock ->
            ("co $every ${unit.label(every)} · ${if (runFirstNow) "pierwszy raz od razu" else "pierwszy raz po interwale"} · " +
                if (maxRuns > 0) "maks. ${maxRuns}×" else "bez końca") to false
        else -> "" to false
    }
}

private val palette: List<Pair<String, () -> Block>> = listOf(
    "JEŻELI WZORZEC ISTNIEJE TO… W PRZECIWNYM RAZIE…" to { IfPatternBlock() },
    "ZNAJDŹ WZORZEC I KLIKNIJ GO" to { FindClickBlock() },
    "CZEKAJ" to { WaitBlock() },
    "PRZESUŃ" to { SwipeBlock() },
    "KLIKNIJ" to { TapBlock() },
    "PĘTLA" to { LoopBlock() },
    "DLA KAŻDEGO WZORCA Z ZESTAWU" to { ForEachPatternBlock() },
    "ALGORYTM" to { AlgorithmBlock() },
    "O OKREŚLONEJ DACIE I GODZINIE" to { AtTimeBlock() },
    "POWTARZAJ CO…" to { RepeatEveryBlock() },
    "PRZERWIJ PĘTLĘ" to { BreakBlock },
    "POMIŃ ITERACJĘ" to { ContinueBlock },
    "PRZERWIJ AKTUALNY ALGORYTM I IDŹ DALEJ" to { EndAlgorithmBlock },
    "ZATRZYMAJ BOTA" to { StopBotBlock },
)

/* ───────── ekran edytora ───────── */

@Composable
fun EditorScreen(app: App, id: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val alg = app.repo.algorithms.collectAsState().value.firstOrNull { it.id == id }
    if (alg == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var name by remember(alg.id) { mutableStateOf(alg.name) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val drag = remember { DragState() }
    drag.root = alg.blocks
    drag.onRootChange = { app.repo.saveAlgorithm(alg.copy(blocks = it)) }
    val scroll = rememberScrollState()
    val viewport = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val rootBox = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val density = LocalDensity.current

    // autoprzewijanie, gdy przeciągany blok jest blisko górnej / dolnej krawędzi listy
    LaunchedEffect(drag.active) {
        val edge = with(density) { 90.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (drag.active) {
            val vp = viewport[0]?.takeIf { it.isAttached }?.boundsInWindow()
            if (vp != null) {
                val y = drag.pointer.y
                val dy = when {
                    y < vp.top + edge -> -maxStep * ((vp.top + edge - y) / edge).coerceIn(0f, 1f)
                    y > vp.bottom - edge -> maxStep * ((y - (vp.bottom - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (dy != 0f) { scroll.scrollBy(dy); drag.recompute() }
            }
            delay(16)
        }
    }

    CompositionLocalProvider(LocalAlgId provides alg.id, LocalDrag provides drag) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootBox[0] = it }) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Icons.Default.Code, "Edycja algorytmu", "Bloki wykonują się od góry do dołu. Zapis jest automatyczny.", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Bg)
                .onGloballyPositioned { viewport[0] = it }
                .verticalScroll(scroll).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; app.repo.saveAlgorithm(alg.copy(name = it)) },
                label = { Text("NAZWA ALGORYTMU") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
                )
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("+ DODAJ BLOK", Primary, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { adding = true }
                AppButton("TESTUJ", Color(0xFF2E9E5B), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    val svc = BotAccessibilityService.instance
                    if (svc == null) {
                        ctx.toast("Najpierw włącz usługę dostępności")
                    } else if (alg.blocks.isEmpty()) {
                        ctx.toast("Algorytm jest pusty")
                    } else {
                        // panel pokazuje się nad innymi aplikacjami; test rusza dopiero po naciśnięciu ▶
                        svc.showBotPanel(testAlgorithmId = alg.id, testName = alg.name)
                        ctx.toast("Przejdź do aplikacji docelowej: ▶ uruchamia test, ⏮ ⏭ ☰ – tryb krok po kroku")
                        ctx.findActivity()?.moveTaskToBack(true)
                    }
                }
                Spacer(Modifier.weight(1f))
                AppButton("DUPLIKUJ", Color(0xFF55657A), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    // przechodzimy do edycji kopii – oryginał zostaje bez zmian
                    app.repo.duplicateAlgorithm(alg.id)?.let { ctx.toast("Utworzono „${it.name}” – edytujesz kopię"); onOpen(it.id) }
                }
                AppButton("USUŃ", Danger, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { confirmDelete = true }
            }
            if (alg.blocks.isEmpty()) {
                Text("Algorytm jest pusty. Dodaj pierwszy blok.", color = Muted, fontSize = 13.sp)
            }
            BlockList(emptyList(), alg.blocks, app, showAdd = false) { app.repo.saveAlgorithm(alg.copy(blocks = it)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    // „duch” przeciąganego bloku pod palcem
    val dragged = drag.block
    if (drag.active && dragged != null) {
        Box(
            Modifier
                .offset {
                    val o = rootBox[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
                    IntOffset((drag.pointer.x - o.x).toInt() - 60, (drag.pointer.y - o.y).toInt() - 70)
                }
                .clip(RoundedCornerShape(8.dp))
                .background(dragged.color())
                .border(2.dp, dragged.accent(), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(dragged.title(), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1B2733)) }
    }
    }
    }

    if (adding) AddBlockDialog({ adding = false }) {
        app.repo.saveAlgorithm(alg.copy(blocks = alg.blocks + it))
        adding = false
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć algorytm „${alg.name}”?") },
            confirmButton = { TextButton({ app.repo.deleteAlgorithm(alg.id); confirmDelete = false; onBack() }) { Text("USUŃ") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("ANULUJ") } }
        )
    }
}

/* ───────── przeciąganie bloków (także do wnętrza innych bloków) ───────── */

/** Krok ścieżki do listy zagnieżdżonej: blok o indeksie [index] w liście nadrzędnej, kieszeń nr [slot] (0 = TO / WYKONUJ, 1 = W PRZECIWNYM RAZIE). */
private data class PathStep(val index: Int, val slot: Int)

private class ListReg(val path: List<PathStep>) {
    var coords: LayoutCoordinates? = null
    val items = HashMap<Int, LayoutCoordinates>()
    var count = 0
}

private class DragState {
    val lists = HashMap<List<PathStep>, ListReg>()
    var root: List<Block> = emptyList()
    var onRootChange: (List<Block>) -> Unit = {}

    var active by mutableStateOf(false)
    var block by mutableStateOf<Block?>(null)
    var srcPath: List<PathStep> = emptyList()
    var srcIdx = -1
    var pointer by mutableStateOf(Offset.Zero)
    var targetPath by mutableStateOf<List<PathStep>?>(null)
    var targetIdx by mutableStateOf(0)

    fun start(path: List<PathStep>, idx: Int, b: Block, window: Offset) {
        srcPath = path; srcIdx = idx; block = b; pointer = window; active = true
        recompute()
    }

    fun move(delta: Offset) { if (active) { pointer += delta; recompute() } }

    fun end() {
        if (!active) return
        val tp = targetPath
        if (tp != null) moveBlock(root, srcPath, srcIdx, tp, targetIdx)?.let { onRootChange(it) }
        cancel()
    }

    fun cancel() { active = false; block = null; targetPath = null }

    /** Wylicza listę i pozycję, w którą trafi blok, na podstawie położenia palca. */
    fun recompute() {
        val p = pointer
        fun insideDragged(path: List<PathStep>) =
            path.size > srcPath.size && path.subList(0, srcPath.size) == srcPath && path[srcPath.size].index == srcIdx
        var best: ListReg? = null
        var bestArea = Float.MAX_VALUE
        for (reg in lists.values) {
            val c = reg.coords ?: continue
            if (!c.isAttached || insideDragged(reg.path)) continue
            val r = c.boundsInWindow()
            if (r.contains(p)) {
                val area = r.width * r.height
                if (area < bestArea) { best = reg; bestArea = area }   // najmniejsza = najgłębiej zagnieżdżona
            }
        }
        val reg = best ?: lists[emptyList()] ?: return
        var idx = 0
        for (i in 0 until reg.count) {
            val c = reg.items[i] ?: continue
            if (!c.isAttached) continue
            val r = c.boundsInWindow()
            if ((r.top + r.bottom) / 2f < p.y) idx = i + 1
        }
        targetPath = reg.path
        targetIdx = idx
    }
}

private val LocalDrag = compositionLocalOf { DragState() }

private fun childList(b: Block, slot: Int): List<Block>? = when (b) {
    is IfPatternBlock -> if (slot == 0) b.thenBlocks else b.elseBlocks
    is LoopBlock -> b.body
    is ForEachPatternBlock -> b.body
    is AtTimeBlock -> b.body
    is RepeatEveryBlock -> b.body
    else -> null
}

private fun withChild(b: Block, slot: Int, l: List<Block>): Block = when (b) {
    is IfPatternBlock -> if (slot == 0) b.copy(thenBlocks = l) else b.copy(elseBlocks = l)
    is LoopBlock -> b.copy(body = l)
    is ForEachPatternBlock -> b.copy(body = l)
    is AtTimeBlock -> b.copy(body = l)
    is RepeatEveryBlock -> b.copy(body = l)
    else -> b
}

private fun getList(root: List<Block>, path: List<PathStep>): List<Block>? {
    var cur = root
    for (st in path) cur = childList(cur.getOrNull(st.index) ?: return null, st.slot) ?: return null
    return cur
}

private fun setList(root: List<Block>, path: List<PathStep>, new: List<Block>): List<Block> {
    if (path.isEmpty()) return new
    val st = path.first()
    val b = root[st.index]
    val inner = setList(childList(b, st.slot) ?: return root, path.drop(1), new)
    return root.toMutableList().also { it[st.index] = withChild(b, st.slot, inner) }
}

/** Przenosi blok [f] z listy [src] do listy [tgt] na pozycję [t0]. Zwraca nowe drzewo albo null, gdy ruch jest niemożliwy / bez efektu. */
private fun moveBlock(root: List<Block>, src: List<PathStep>, f: Int, tgt: List<PathStep>, t0: Int): List<Block>? {
    val srcList = getList(root, src) ?: return null
    val blk = srcList.getOrNull(f) ?: return null
    // blok nie może wylądować we własnym wnętrzu
    if (tgt.size > src.size && tgt.subList(0, src.size) == src && tgt[src.size].index == f) return null
    // upuszczenie w to samo miejsce
    if (tgt == src && (t0 == f || t0 == f + 1)) return null
    val afterRemove = setList(root, src, srcList.toMutableList().also { it.removeAt(f) })
    // usunięcie przesuwa indeksy w liście źródłowej – poprawiamy ścieżkę docelową
    val adj = tgt.toMutableList()
    for (k in 0 until tgt.size) {
        if (tgt.subList(0, k) == src && adj[k].index > f) adj[k] = adj[k].copy(index = adj[k].index - 1)
    }
    val t = if (tgt == src && t0 > f) t0 - 1 else t0
    val tl = getList(afterRemove, adj) ?: return null
    return setList(afterRemove, adj, tl.toMutableList().also { it.add(t.coerceIn(0, it.size), blk) })
}

@Composable
private fun BlockList(path: List<PathStep>, blocks: List<Block>, app: App, showAdd: Boolean = true, onChange: (List<Block>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val drag = LocalDrag.current
    val reg = remember(path) { ListReg(path).also { drag.lists[path] = it } }
    reg.count = blocks.size
    DisposableEffect(reg) { onDispose { if (drag.lists[path] === reg) drag.lists.remove(path) } }
    val gapPx = with(LocalDensity.current) { 6.dp.toPx() }
    val barPx = with(LocalDensity.current) { 4.dp.toPx() }

    Column(
        Modifier
            .onGloballyPositioned { reg.coords = it }
            .drawWithContent {
                drawContent()
                val lc = reg.coords
                if (drag.active && drag.targetPath == path && lc != null && lc.isAttached) {
                    val ti = drag.targetIdx
                    val y = when {
                        blocks.isEmpty() -> 0f
                        ti <= 0 -> reg.items[0]?.takeIf { it.isAttached }?.let { lc.localPositionOf(it, Offset.Zero).y - gapPx / 2f } ?: 0f
                        else -> reg.items[ti - 1]?.takeIf { it.isAttached }
                            ?.let { lc.localPositionOf(it, Offset(0f, it.size.height.toFloat())).y + gapPx / 2f } ?: size.height
                    }
                    drawRoundRect(
                        color = Primary,
                        topLeft = Offset(0f, y - barPx / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width, barPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barPx)
                    )
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEachIndexed { i, b ->
            Box(
                Modifier
                    .graphicsLayer { alpha = if (drag.active && drag.srcPath == path && drag.srcIdx == i) 0.35f else 1f }
                    .onGloballyPositioned { reg.items[i] = it }
            ) {
                SwipeCard(
                    onSwipeLeft = { onChange(blocks.filterIndexed { j, _ -> j != i }) },
                    onSwipeRight = { onChange(blocks.toMutableList().also { it.add(i + 1, b) }) },
                    rightIcon = Icons.Default.ContentCopy,
                ) {
                BlockCard(
                    block = b, app = app, path = path, index = i,
                    onDragStart = { window -> drag.start(path, i, b, window) },
                    onDrag = { drag.move(it) },
                    onDragEnd = { drag.end() },
                    onDragCancel = { drag.cancel() },
                    onChange = { nb -> onChange(blocks.toMutableList().also { it[i] = nb }) },
                    onDelete = { onChange(blocks.filterIndexed { j, _ -> j != i }) },
                    onDuplicate = { edited -> onChange(blocks.toMutableList().also { it[i] = edited; it.add(i + 1, edited) }) },
                )
                }
            }
        }
        if (drag.active) {
            // w czasie przeciągania „+ BLOK” zamienia się w pole, w które można upuścić blok
            val hot = drag.targetPath == path && drag.targetIdx >= blocks.size
            Box(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (hot) Primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.5f))
                    .border(2.dp, if (hot) Primary else Color(0xFFB8C2CC), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) { Text("UPUŚĆ BLOK TUTAJ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (hot) Primary else Muted) }
        } else if (showAdd) {
            Box(Modifier.fillMaxWidth().height(36.dp).clickable { adding = true }, contentAlignment = Alignment.CenterStart) {
                Text("+ BLOK", color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    if (adding) AddBlockDialog({ adding = false }) { onChange(blocks + it); adding = false }
}

@Composable
private fun MiniIcon(icon: ImageVector, desc: String, tint: Color = Color(0xFF1B2733), onClick: () -> Unit) {
    Icon(icon, desc, Modifier.size(30.dp).clickable(onClick = onClick).padding(5.dp), tint = tint)
}

@Composable
private fun BlockCard(
    block: Block,
    app: App,
    path: List<PathStep>,
    index: Int,
    onChange: (Block) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: (Block) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val curStart by rememberUpdatedState(onDragStart)
    val curDrag by rememberUpdatedState(onDrag)
    val curEnd by rememberUpdatedState(onDragEnd)
    val curCancel by rememberUpdatedState(onDragCancel)
    val handle = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val patterns by app.repo.patterns.collectAsState()
    val algs by app.repo.algorithms.collectAsState()
    var expanded by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    val fg = if (block.darkBg()) Color.White else Color(0xFF1B2733)
    val (summary, warn) = block.summary(patterns, algs)
    val patIds: List<String> = when (block) {
        is FindClickBlock -> listOfNotNull(block.patternId)
        is IfPatternBlock -> listOfNotNull(block.patternId)
        is LoopBlock -> if (block.mode != LoopMode.COUNT) listOfNotNull(block.patternId) else emptyList()
        is ForEachPatternBlock -> block.patternIds
        else -> emptyList()
    }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(block.color())) {
    Box(Modifier.width(5.dp).fillMaxHeight().background(block.accent()))
    Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth().clickable { editing = true }.padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { editing = true }) {
                Text(block.title(), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg)
                if (summary.isNotEmpty()) {
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (patIds.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                patIds.take(6).forEach { PatternThumb(app, it, 32.dp) }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(summary, Modifier.weight(1f), fontSize = 11.sp, color = if (warn) Color(0xFFB3261E) else fg.copy(alpha = 0.8f))
                    }
                }
            }
            if (block.isContainer()) {
                MiniIcon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "Zwiń/rozwiń", fg) { expanded = !expanded }
            }
            // uchwyt przeciągania: przytrzymaj i przesuń blok w górę / w dół
            Icon(
                Icons.Default.DragHandle, "Przeciągnij blok",
                Modifier.size(30.dp).padding(3.dp)
                    .onGloballyPositioned { handle[0] = it }
                    .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { off -> curStart(handle[0]?.takeIf { it.isAttached }?.localToWindow(off) ?: Offset.Zero) },
                        onDrag = { change, d -> change.consume(); curDrag(d) },
                        onDragEnd = { curEnd() },
                        onDragCancel = { curCancel() },
                    )
                },
                tint = fg.copy(alpha = 0.6f)
            )
        }
        if (block.isContainer() && expanded) {
            when (block) {
                is IfPatternBlock -> {
                    Pocket("TO:") { BlockList(path + PathStep(index, 0), block.thenBlocks, app) { onChange(block.copy(thenBlocks = it)) } }
                    Pocket("W PRZECIWNYM RAZIE:") { BlockList(path + PathStep(index, 1), block.elseBlocks, app) { onChange(block.copy(elseBlocks = it)) } }
                }
                is LoopBlock -> Pocket("WYKONUJ:") { BlockList(path + PathStep(index, 0), block.body, app) { onChange(block.copy(body = it)) } }
                is ForEachPatternBlock -> Pocket("DLA KAŻDEGO ZNALEZIONEGO WYKONAJ:") { BlockList(path + PathStep(index, 0), block.body, app) { onChange(block.copy(body = it)) } }
                is AtTimeBlock -> Pocket("O TEJ PORZE WYKONAJ:") { BlockList(path + PathStep(index, 0), block.body, app) { onChange(block.copy(body = it)) } }
                is RepeatEveryBlock -> Pocket("W KAŻDYM CYKLU WYKONAJ:") { BlockList(path + PathStep(index, 0), block.body, app) { onChange(block.copy(body = it)) } }
                else -> Unit
            }
        }
    }
    }
    if (editing) BlockEditDialog(
        block, app, { editing = false },
        onDelete = { editing = false; onDelete() },
        onDuplicate = { editing = false; onDuplicate(it) },
    ) { onChange(it); editing = false }
}

@Composable
private fun Pocket(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 10.dp, end = 6.dp, bottom = 6.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B2733))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.4f)).padding(6.dp)) { content() }
    }
}

@Composable
private fun AddBlockDialog(onDismiss: () -> Unit, onPick: (Block) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BLOKI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                palette.forEach { (label, make) ->
                    val sample = make()
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(sample.color())
                            .clickable { onPick(make()) }.padding(horizontal = 12.dp, vertical = 12.dp)
                    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (sample.darkBg()) Color.White else Color(0xFF1B2733)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("ANULUJ") } }
    )
}

/* ───────── edycja parametrów ───────── */

/** Przycisk uruchamiający pobieranie współrzędnych fizycznym dotknięciem ekranu. */
@Composable
private fun PickButton(app: App, wantSwipe: Boolean, hasCoordinates: Boolean = false) {
    val ctx = LocalContext.current
    AppButton(
        if (hasCoordinates) "✥ EDYTUJ NA EKRANIE" else if (wantSwipe) "● POBIERZ PRZESUNIĘCIE Z EKRANU" else "● POBIERZ KLIKNIĘCIE Z EKRANU",
        Danger, Modifier.fillMaxWidth()
    ) {
        val svc = BotAccessibilityService.instance
        if (svc == null) {
            ctx.toast("Najpierw włącz usługę dostępności")
        } else {
            app.coordPick.value = null
            if (svc.startCoordinatePick(wantSwipe)) ctx.findActivity()?.moveTaskToBack(true)
        }
    }
    Text(
        "Aplikacja się zminimalizuje i pojawi się pływający przycisk. Przejdź na właściwy ekran, naciśnij przycisk, " +
            if (wantSwipe) "a potem przesuń palcem od punktu startu do końca." else "a potem dotknij miejsca, które ma zostać kliknięte.",
        fontSize = 11.sp, color = Muted
    )
}

@Composable
private fun ThresholdEditor(value: Float?, onChange: (Float?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = value == null, onCheckedChange = { onChange(if (it) null else 0.9f) })
        Text("Użyj domyślnej dokładności wzorca", fontSize = 12.sp)
    }
    if (value != null) {
        Text("Dokładność dopasowania: ${(value * 100).toInt()}%", fontSize = 12.sp)
        Slider(value, { onChange(it) }, valueRange = 0.5f..1f)
    }
}

@Composable
private fun BlockEditDialog(
    block: Block, app: App, onDismiss: () -> Unit,
    onDelete: () -> Unit, onDuplicate: (Block) -> Unit, onSave: (Block) -> Unit,
) {
    val patterns by app.repo.patterns.collectAsState()
    val algs by app.repo.algorithms.collectAsState()
    val selfId = LocalAlgId.current
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(block) }
    var confirmDel by remember { mutableStateOf(false) }
    val patOptions = patterns.map { it.id to (folderLabel(it.folder) + " / " + it.name) }

    // współrzędne pobrane z ekranu pływającym przyciskiem trafiają do otwartego dialogu
    val pick by app.coordPick.collectAsState()
    val openedAt = remember { System.currentTimeMillis() }
    var pickVersion by remember { mutableStateOf(0) }
    LaunchedEffect(pick) {
        val p = pick ?: return@LaunchedEffect
        if (p.ts < openedAt) return@LaunchedEffect
        when (val d = draft) {
            is TapBlock -> draft = d.copy(x = p.x1, y = p.y1, durationMs = if (p.durationMs >= 500) p.durationMs else d.durationMs)
            is SwipeBlock -> if (p.isSwipe) {
                draft = d.copy(x1 = p.x1, y1 = p.y1, x2 = p.x2, y2 = p.y2, durationMs = p.durationMs.coerceAtLeast(100))
            }
            else -> Unit
        }
        pickVersion++          // odtwarza pola liczbowe z nowymi wartościami
        app.coordPick.value = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(block.title(), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "USUŃ", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { confirmDel = true }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val d = draft) {
                    is TapBlock -> key(pickVersion) {
                        PickButton(app, wantSwipe = false, hasCoordinates = d.x != 0 || d.y != 0)
                        NumField("X (px)", d.x.toLong()) { draft = d.copy(x = it.toInt()) }
                        NumField("Y (px)", d.y.toLong()) { draft = d.copy(y = it.toInt()) }
                        NumField("Czas dotyku (ms) – >500 = przytrzymanie", d.durationMs) { draft = d.copy(durationMs = it) }
                    }
                    is SwipeBlock -> key(pickVersion) {
                        PickButton(app, wantSwipe = true, hasCoordinates = d.x1 != 0 || d.y1 != 0 || d.x2 != 0 || d.y2 != 0)
                        NumField("Start X", d.x1.toLong()) { draft = d.copy(x1 = it.toInt()) }
                        NumField("Start Y", d.y1.toLong()) { draft = d.copy(y1 = it.toInt()) }
                        NumField("Koniec X", d.x2.toLong()) { draft = d.copy(x2 = it.toInt()) }
                        NumField("Koniec Y", d.y2.toLong()) { draft = d.copy(y2 = it.toInt()) }
                        NumField("Czas przesunięcia (ms)", d.durationMs) { draft = d.copy(durationMs = it) }
                    }
                    is WaitBlock -> NumField("Czas oczekiwania (ms)", d.ms) { draft = d.copy(ms = it) }
                    is FindClickBlock -> {
                        PatternPicker(app, "Wybierz wzorzec…", patOptions, d.patternId) { draft = d.copy(patternId = it) }
                        ThresholdEditor(d.threshold) { draft = d.copy(threshold = it) }
                        NumField("Maks. czas szukania (ms)", d.timeoutMs) { draft = d.copy(timeoutMs = it) }
                        Text("Gdy nie znaleziono:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        listOf(
                            NotFoundAction.ERROR to "zatrzymaj bota z błędem",
                            NotFoundAction.SKIP to "pomiń ten blok i jedź dalej",
                            NotFoundAction.END_ALGORITHM to "przerwij algorytm i idź do następnego",
                        ).forEach { (v, label) ->
                            Row(Modifier.clickable { draft = d.copy(onNotFound = v) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(d.onNotFound == v, { draft = d.copy(onNotFound = v) }); Text(label, fontSize = 12.sp)
                            }
                        }
                    }
                    is IfPatternBlock -> {
                        PatternPicker(app, "Wybierz wzorzec…", patOptions, d.patternId) { draft = d.copy(patternId = it) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(d.negate, { draft = d.copy(negate = it) })
                            Text(if (d.negate) "JEŻELI wzorca NIE MA na ekranie" else "JEŻELI wzorzec JEST na ekranie", fontSize = 12.sp)
                        }
                        ThresholdEditor(d.threshold) { draft = d.copy(threshold = it) }
                    }
                    is AlgorithmBlock -> {
                        val options = algs.filter { it.id != selfId && !reachesAlgorithm(algs, it.id, selfId) }.map { it.id to it.name }
                        Picker("Wybierz algorytm…", options, d.algorithmId) { draft = d.copy(algorithmId = it) }
                        NumField("Powtórz (razy)", d.repeat.toLong()) { draft = d.copy(repeat = it.toInt().coerceAtLeast(1)) }
                        Text(
                            "Algorytm nie może wywoływać samego siebie – ani bezpośrednio, ani przez inne algorytmy, dlatego te są ukryte na liście. " +
                                "PRZERWIJ ALGORYTM w środku kończy tylko wywołany algorytm.",
                            fontSize = 11.sp, color = Muted
                        )
                    }
                    is ForEachPatternBlock -> {
                        Text("Wzorce w zestawie (${d.patternIds.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        d.patternIds.forEach { pid ->
                            val label = patOptions.firstOrNull { it.first == pid }?.second ?: "⚠ usunięty wzorzec"
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PatternThumb(app, pid, 32.dp)
                                Text(label, Modifier.weight(1f), fontSize = 12.sp)
                                MiniIcon(Icons.Default.Close, "Usuń z zestawu") { draft = d.copy(patternIds = d.patternIds - pid) }
                            }
                        }
                        PatternPicker(app, "+ Dodaj wzorzec do zestawu…", patOptions.filter { it.first !in d.patternIds }, null) {
                            draft = d.copy(patternIds = d.patternIds + it)
                        }
                        ThresholdEditor(d.threshold) { draft = d.copy(threshold = it) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(d.clickFound, { draft = d.copy(clickFound = it) })
                            Text("Kliknij znaleziony element przed wykonaniem bloków", fontSize = 12.sp)
                        }
                        NumField("Maks. liczba iteracji (zabezpieczenie)", d.maxIterations.toLong()) {
                            draft = d.copy(maxIterations = it.toInt().coerceAtLeast(1))
                        }
                        Text(
                            "Działanie: szuka na ekranie wszystkich wzorców z zestawu, bierze ten położony najwyżej, klika go (jeśli włączone), " +
                                "wykonuje bloki z kieszeni i skanuje ekran od nowa – aż nie zostanie żaden wzorzec z zestawu.",
                            fontSize = 11.sp, color = Muted
                        )
                    }
                    is AtTimeBlock -> {
                        val cal = Calendar.getInstance().apply { timeInMillis = d.atMs }
                        Text("Termin: ${fmtDateTime(d.atMs)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("USTAW DATĘ", Primary) {
                                DatePickerDialog(
                                    ctx,
                                    { _, y, m, day ->
                                        val c = Calendar.getInstance().apply {
                                            timeInMillis = d.atMs
                                            set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, day)
                                        }
                                        draft = d.copy(atMs = c.timeInMillis)
                                    },
                                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }
                            AppButton("USTAW GODZINĘ", Primary) {
                                TimePickerDialog(
                                    ctx,
                                    { _, h, min ->
                                        val c = Calendar.getInstance().apply {
                                            timeInMillis = d.atMs
                                            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                                        }
                                        draft = d.copy(atMs = c.timeInMillis)
                                    },
                                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
                                ).show()
                            }
                        }
                        Text("Jeśli termin już minął, gdy bot dojdzie do bloku:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        listOf(
                            PastAction.SKIP to "pomiń blok (nic nie rób)",
                            PastAction.RUN_NOW to "wykonaj od razu",
                        ).forEach { (v, label) ->
                            Row(Modifier.clickable { draft = d.copy(ifPast = v) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(d.ifPast == v, { draft = d.copy(ifPast = v) }); Text(label, fontSize = 12.sp)
                            }
                        }
                        Text(
                            "Bot czeka do wskazanej chwili (ekran musi pozostać włączony i odblokowany; pływający panel utrzymuje go włączonym). " +
                                "Czas oczekiwania nie wlicza się do limitu czasu działania.",
                            fontSize = 11.sp, color = Muted
                        )
                    }
                    is RepeatEveryBlock -> {
                        NumField("Powtarzaj co (liczba)", d.every.toLong()) { draft = d.copy(every = it.toInt().coerceAtLeast(1)) }
                        listOf(
                            RepeatUnit.MINUTES to "minut",
                            RepeatUnit.HOURS to "godzin",
                            RepeatUnit.DAYS to "dni (1 dzień = 24 h)",
                            RepeatUnit.WEEKS to "tygodni",
                        ).forEach { (v, label) ->
                            Row(Modifier.clickable { draft = d.copy(unit = v) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(d.unit == v, { draft = d.copy(unit = v) }); Text(label, fontSize = 12.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(d.runFirstNow, { draft = d.copy(runFirstNow = it) })
                            Text("Pierwsze wykonanie od razu (wyłączone: dopiero po upływie interwału)", fontSize = 12.sp)
                        }
                        NumField("Maks. liczba uruchomień (0 = bez końca)", d.maxRuns.toLong()) { draft = d.copy(maxRuns = it.toInt()) }
                        Text(
                            "Interwał liczony jest od startu poprzedniego uruchomienia. Bez limitu uruchomień algorytm nigdy się nie kończy – " +
                                "zatrzymasz go przyciskiem ■. Kolejne algorytmy z listy ruszą dopiero po zakończeniu tego bloku.",
                            fontSize = 11.sp, color = Muted
                        )
                    }
                    is LoopBlock -> {
                        listOf(
                            LoopMode.COUNT to "powtórz określoną liczbę razy",
                            LoopMode.UNTIL_FOUND to "powtarzaj do pojawienia się wzorca",
                            LoopMode.UNTIL_MISSING to "powtarzaj do zniknięcia wzorca",
                        ).forEach { (v, label) ->
                            Row(Modifier.clickable { draft = d.copy(mode = v) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(d.mode == v, { draft = d.copy(mode = v) }); Text(label, fontSize = 12.sp)
                            }
                        }
                        if (d.mode == LoopMode.COUNT) {
                            NumField("Liczba powtórzeń", d.count.toLong()) { draft = d.copy(count = it.toInt()) }
                        } else {
                            PatternPicker(app, "Wybierz wzorzec…", patOptions, d.patternId) { draft = d.copy(patternId = it) }
                            ThresholdEditor(d.threshold) { draft = d.copy(threshold = it) }
                            NumField("Maks. liczba iteracji (zabezpieczenie)", d.maxIterations.toLong()) { draft = d.copy(maxIterations = it.toInt().coerceAtLeast(1)) }
                        }
                    }
                    else -> Text("Ten blok nie ma parametrów do ustawienia.", fontSize = 12.sp, color = Muted)
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onDuplicate(draft) }) { Text("DUPLIKUJ", color = Primary, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text("ANULUJ") }
                TextButton({ onSave(draft) }) { Text("ZAPISZ") }
            }
        },
        dismissButton = null
    )
    if (confirmDel) {
        AlertDialog(
            onDismissRequest = { confirmDel = false },
            title = { Text("Usunąć ten blok?") },
            text = { Text(block.title(), fontSize = 12.sp, color = Muted) },
            confirmButton = { TextButton({ confirmDel = false; onDelete() }) { Text("USUŃ", color = Danger) } },
            dismissButton = { TextButton({ confirmDel = false }) { Text("ANULUJ") } }
        )
    }
}
