package pl.autobot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.autobot.app.App
import pl.autobot.app.data.Algorithm
import pl.autobot.app.service.BotAccessibilityService

/** Zakładka „Skrypty”: wszystkie zapisane algorytmy (także nagrane makra) w jednej, przeszukiwalnej liście. */
@Composable
fun ScriptsScreen(app: App, onEditAlgorithm: (String) -> Unit) {
    val ctx = LocalContext.current
    val algs by app.repo.algorithms.collectAsState()
    val connected by BotAccessibilityService.connected.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var sortByDate by rememberSaveable { mutableStateOf(true) }   // domyślnie: najnowsze na górze

    val shown = remember(algs, query, sortByDate) {
        val q = query.trim()
        val filtered = algs.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        if (sortByDate) filtered.sortedByDescending { it.stamp } else filtered.sortedBy { it.name.lowercase() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Icons.Default.Code, "Skrypty", "Algorytmy i nagrane makra w jednym miejscu. Przesuń kartę o pół ekranu w lewo, aby usunąć, w prawo, aby dodać do BOT-a.")
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Bg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
                AppButton("● NAGRAJ MAKRO", Danger) {
                    val svc = BotAccessibilityService.instance
                    if (!connected || svc == null) {
                        ctx.toast("Najpierw włącz usługę dostępności")
                        ctx.openAccessibilitySettings()
                    } else {
                        svc.startRecording()
                    }
                }
                AppButton("+ NOWY ALGORYTM", Primary) {
                    val a = Algorithm(name = "Algorytm ${algs.size + 1}")
                    app.repo.saveAlgorithm(a)
                    onEditAlgorithm(a.id)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Szukaj po nazwie…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Wyczyść") }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
            )
            IconButton({ sortByDate = true }, Modifier.size(40.dp)) {
                Icon(Icons.Default.Schedule, "Sortuj wg daty modyfikacji", tint = if (sortByDate) Primary else Muted)
            }
            IconButton({ sortByDate = false }, Modifier.size(40.dp)) {
                Icon(Icons.Default.SortByAlpha, "Sortuj alfabetycznie", tint = if (!sortByDate) Primary else Muted)
            }
            }
            if (algs.isEmpty()) {
                Text(
                    "Brak skryptów. Nagraj makro (wykonaj czynności w dowolnej aplikacji, a na końcu naciśnij ■ w pływającym panelu) albo utwórz algorytm.",
                    color = Muted, fontSize = 13.sp
                )
            } else if (shown.isEmpty()) {
                Text("Nic nie pasuje do „${query.trim()}”.", color = Muted, fontSize = 13.sp)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { a ->
                    SwipeCard(
                        onSwipeLeft = { app.repo.deleteAlgorithm(a.id); ctx.toast("Usunięto „${a.name}”") },
                        onSwipeRight = {
                            if (a.inBot) ctx.toast("„${a.name}” już jest w zakładce BOT")
                            else { app.repo.saveAlgorithm(a.copy(inBot = true)); ctx.toast("Dodano „${a.name}” do zakładki BOT") }
                        },
                    ) {
                        WhiteCard(onClick = { onEditAlgorithm(a.id) }) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                Row {
                                    Text(plural(a.blocks.size, "blok", "bloki", "bloków"), fontSize = 11.sp, color = Muted)
                                    if (a.inBot) Text(" · w BOT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary)
                                }
                                Text(formatStamp(a.stamp), fontSize = 11.sp, color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}
