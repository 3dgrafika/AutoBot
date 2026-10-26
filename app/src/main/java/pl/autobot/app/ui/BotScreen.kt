package pl.autobot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.autobot.app.App
import pl.autobot.app.data.Algorithm
import pl.autobot.app.engine.BotState
import pl.autobot.app.service.BotAccessibilityService

@Composable
fun BotScreen(app: App, onEdit: (String) -> Unit) {
    val ctx = LocalContext.current
    val algs by app.repo.algorithms.collectAsState()
    val state by app.engine.state.collectAsState()
    val status by app.engine.status.collectAsState()
    val connected by BotAccessibilityService.connected.collectAsState()
    var showLoad by remember { mutableStateOf(false) }
    val loaded = algs.filter { it.inBot }

    val reorder = remember { ReorderState() }
    val scroll = rememberScrollState()
    val viewport = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val listBox = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val rootBox = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val density = LocalDensity.current

    // autoprzewijanie przy przeciąganiu blisko krawędzi listy
    LaunchedEffect(reorder.active) {
        val edge = with(density) { 80.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (reorder.active) {
            val vp = viewport[0]?.takeIf { it.isAttached }?.boundsInWindow()
            if (vp != null) {
                val y = reorder.pointer.y
                val dy = when {
                    y < vp.top + edge -> -maxStep * ((vp.top + edge - y) / edge).coerceIn(0f, 1f)
                    y > vp.bottom - edge -> maxStep * ((y - (vp.bottom - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (dy != 0f) { scroll.scrollBy(dy); reorder.recompute() }
            }
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootBox[0] = it }) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            Icons.Default.SmartToy, "BOT",
            "Zaznacz wybrane algorytmy, ustaw je w odpowiedniej kolejności i uruchom bota."
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Bg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!connected) {
                Column(Modifier.fillMaxWidth().background(Color(0xFFFFE9E7)).padding(10.dp)) {
                    Text("Usługa dostępności jest wyłączona", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Bez niej bot nie może klikać ani robić zrzutów ekranu. Włącz „AutoBot” w ustawieniach dostępności. " +
                            "Android 13+: jeśli opcja jest wyszarzona, wejdź w Informacje o aplikacji → ⋮ → „Zezwól na ustawienia z ograniczeniami”.",
                        fontSize = 11.sp, color = Muted
                    )
                    AppButton("WŁĄCZ USŁUGĘ", Danger) { ctx.openAccessibilitySettings() }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                AppButton("WCZYTAJ ALGORYTMY", Primary) { showLoad = true }
            }
            if (state != BotState.IDLE) {
                Column(Modifier.fillMaxWidth().background(Color.White).padding(10.dp)) {
                    Text(if (state == BotState.PAUSED) "⏸ WSTRZYMANY" else "▶ PRACUJE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(status.ifEmpty { "…" }, fontSize = 12.sp, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    if (state == BotState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state == BotState.RUNNING) AppButton("PAUZA", Primary) { app.engine.pause() }
                        else AppButton("WZNÓW", Primary) { app.engine.resume() }
                    }
                }
            }
            if (loaded.isEmpty()) {
                Text(
                    "Brak algorytmów w zakładce BOT. Wczytaj zapisane przyciskiem WCZYTAJ ALGORYTMY (nowe algorytmy tworzysz w zakładce Skrypty).",
                    color = Muted, fontSize = 13.sp
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned { viewport[0] = it }
                    .verticalScroll(scroll)
                    .onGloballyPositioned { listBox[0] = it }
                    .drawWithContent {
                        drawContent()
                        val lc = listBox[0]
                        if (reorder.active && lc != null && lc.isAttached && loaded.isNotEmpty()) {
                            val t = reorder.targetIdx.coerceIn(0, loaded.size)
                            val y = if (t == 0) reorder.items[loaded[0].id]?.takeIf { it.isAttached }?.let { lc.localPositionOf(it, Offset.Zero).y - 3.dp.toPx() }
                            else reorder.items[loaded[t - 1].id]?.takeIf { it.isAttached }?.let { lc.localPositionOf(it, Offset(0f, it.size.height.toFloat())).y + 3.dp.toPx() }
                            if (y != null) drawRoundRect(Primary, Offset(0f, y - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                loaded.forEach { a ->
                    val handleBox = remember { arrayOfNulls<LayoutCoordinates>(1) }
                    Box(
                        Modifier
                            .graphicsLayer { alpha = if (reorder.active && reorder.draggedId == a.id) 0.35f else 1f }
                            .onGloballyPositioned { reorder.items[a.id] = it }
                    ) {
                        WhiteCard {
                            Checkbox(a.selected, { app.repo.saveAlgorithm(a.copy(selected = it)) })
                            Column(Modifier.weight(1f).clickable { onEdit(a.id) }) {
                                Text(a.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                Text(plural(a.blocks.size, "blok", "bloki", "bloków"), fontSize = 11.sp, color = Muted)
                            }
                            Icon(
                                Icons.Default.DragHandle, "Przeciągnij, aby zmienić kolejność",
                                Modifier.size(36.dp).padding(6.dp)
                                    .onGloballyPositioned { handleBox[0] = it }
                                    .pointerInput(a.id, loaded.map { it.id }) {
                                        detectDragGestures(
                                            onDragStart = { off ->
                                                reorder.order = loaded.map { it.id }
                                                reorder.start(a.id, handleBox[0]?.takeIf { it.isAttached }?.localToWindow(off) ?: Offset.Zero)
                                            },
                                            onDrag = { change, d -> change.consume(); reorder.move(d) },
                                            onDragEnd = { reorder.end { id, idx -> app.repo.reorderBot(id, idx) } },
                                            onDragCancel = { reorder.cancel() },
                                        )
                                    },
                                tint = Muted
                            )
                        }
                    }
                }
            }
            val running = state != BotState.IDLE
            AppButton(
                if (running) "ZATRZYMAJ BOTA" else "URUCHOM BOTA",
                if (running) Danger else Color(0xFF111111),
                Modifier.align(Alignment.CenterHorizontally)
            ) {
                when {
                    running -> app.engine.stop()
                    !connected -> { ctx.toast("Najpierw włącz usługę dostępności"); ctx.openAccessibilitySettings() }
                    loaded.none { it.selected } -> ctx.toast("Zaznacz co najmniej jeden algorytm")
                    else -> {
                        BotAccessibilityService.instance?.showBotPanel()
                        ctx.toast("Przejdź do gry i naciśnij ▶ w pływającym panelu")
                        ctx.findActivity()?.moveTaskToBack(true)
                    }
                }
            }
            Text(
                "Panel ▶ ■ pojawi się nad innymi aplikacjami. Odsuń go od miejsc, w które bot ma klikać.",
                fontSize = 11.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    val dragged = loaded.firstOrNull { it.id == reorder.draggedId }
    if (reorder.active && dragged != null) {
        Box(
            Modifier
                .offset {
                    val o = rootBox[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
                    IntOffset((reorder.pointer.x - o.x).toInt() - 80, (reorder.pointer.y - o.y).toInt() - 60)
                }
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(2.dp, Primary, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) { Text(dragged.name, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
    }

    if (showLoad) LoadAlgorithmsDialog(app, algs) { showLoad = false }
}

/** Stan przeciągania kart algorytmów w zakładce BOT (zmiana kolejności uruchamiania). */
private class ReorderState {
    val items = HashMap<String, LayoutCoordinates>()
    var order: List<String> = emptyList()
    var active by mutableStateOf(false)
    var draggedId by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var targetIdx by mutableStateOf(0)

    fun start(id: String, window: Offset) { draggedId = id; pointer = window; active = true; recompute() }
    fun move(d: Offset) { if (active) { pointer += d; recompute() } }
    fun recompute() {
        var idx = 0
        order.forEachIndexed { i, id ->
            val c = items[id]
            if (c != null && c.isAttached) {
                val r = c.boundsInWindow()
                if ((r.top + r.bottom) / 2f < pointer.y) idx = i + 1
            }
        }
        targetIdx = idx
    }
    fun end(onMove: (String, Int) -> Unit) {
        val id = draggedId
        if (active && id != null) {
            val from = order.indexOf(id)
            var t = targetIdx
            if (t > from) t -= 1
            if (from >= 0 && t != from) onMove(id, t)
        }
        cancel()
    }
    fun cancel() { active = false; draggedId = null }
}

/** Wybór, które zapisane algorytmy mają być widoczne (wczytane) w zakładce BOT. */
@Composable
private fun LoadAlgorithmsDialog(app: App, algs: List<Algorithm>, onDismiss: () -> Unit) {
    val checked = remember { mutableStateListOf<String>().apply { addAll(algs.filter { it.inBot }.map { it.id }) } }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = remember(algs, q) {
        algs.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }.sortedByDescending { it.stamp }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wczytaj algorytmy do BOT-a") },
        text = {
            Column {
                if (algs.isEmpty()) {
                    Text("Brak zapisanych algorytmów. Utwórz je w zakładce Skrypty.", fontSize = 12.sp, color = Muted)
                } else {
                    Text("Zaznacz algorytmy, które mają być widoczne w zakładce BOT. Odznaczenie tylko je ukrywa – nie usuwa.", fontSize = 12.sp, color = Muted)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Szukaj po nazwie…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Wyczyść") } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    if (shown.isEmpty()) Text("Nic nie pasuje do „$q”.", fontSize = 12.sp, color = Muted)
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { a ->
                    val on = a.id in checked
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) checked.remove(a.id) else checked.add(a.id) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, { if (it) checked.add(a.id) else checked.remove(a.id) })
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontSize = 14.sp, maxLines = 1)
                            Text(plural(a.blocks.size, "blok", "bloki", "bloków") + " · " + formatStamp(a.stamp), fontSize = 11.sp, color = Muted)
                        }
                    }
                }
                }
            }
        },
        confirmButton = {
            TextButton({ app.repo.setBotLoaded(checked.toSet()); onDismiss() }) { Text("WCZYTAJ") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ANULUJ") } }
    )
}
