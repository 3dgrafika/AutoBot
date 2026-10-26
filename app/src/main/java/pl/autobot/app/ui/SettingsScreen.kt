package pl.autobot.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.autobot.app.App
import pl.autobot.app.data.BackupSummary
import pl.autobot.app.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        content()
    }
}

/** Jeden limit: przełącznik + pole wartości; po wyłączeniu pokazuje ostrzeżenie, co przestaje być ograniczane. */
@Composable
private fun LimitRow(title: String, enabled: Boolean, onToggle: (Boolean) -> Unit, warning: String, field: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(enabled, onToggle)
        Text(if (enabled) "$title – włączony" else "$title – WYŁĄCZONY", fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
    if (!enabled) Text("⚠ $warning", fontSize = 11.sp, color = Danger)
    field()
}

@Composable
fun SettingsScreen(app: App, onLogs: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.repo.settings.collectAsState()
    var importUri by remember { mutableStateOf<Uri?>(null) }

    /** Zapis automatyczny – zawsze na podstawie najnowszych ustawień. */
    fun upd(f: (Settings) -> Settings) = app.repo.saveSettings(f(app.repo.settings.value))

    fun summaryText(b: BackupSummary) =
        "${plural(b.algorithms, "algorytm", "algorytmy", "algorytmów")}, " +
            plural(b.patterns, "wzorzec", "wzorce", "wzorców")

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { app.repo.exportBackup(it) }
                        ?: error("nie można otworzyć pliku do zapisu")
                }
            }
            r.onSuccess { ctx.toast("Zapisano kopię: ${summaryText(it)}") }
                .onFailure { ctx.toast("Błąd zapisu kopii: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }

    fun doImport(uri: Uri, replace: Boolean) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { app.repo.importBackup(it, replace) }
                        ?: error("nie można otworzyć pliku")
                }
            }
            r.onSuccess { ctx.toast("Wczytano: ${summaryText(it)}") }
                .onFailure { ctx.toast("Nie udało się wczytać kopii: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Icons.Default.Settings, "Ustawienia", "Limity, losowość, kopie zapasowe i logi")
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Bg).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Section("Logi") {
                Text("Historia pracy bota, przyczyny zatrzymań i błędy.", fontSize = 12.sp, color = Muted)
                AppButton("POKAŻ LOGI", Primary, onClick = onLogs)
            }

            Section("Limity bezpieczeństwa") {
                Text(
                    "Każdy limit włączasz i wyłączasz osobno. Włączony limit zatrzymuje bota (lub skraca oczekiwanie / pętlę) po jego przekroczeniu.",
                    fontSize = 11.sp, color = Muted
                )
                LimitRow("Maks. czas działania", s.limitRuntimeEnabled, { on -> upd { it.copy(limitRuntimeEnabled = on) } },
                    "Bez limitu bot może pracować bez końca (zatrzymasz go przyciskiem ■).") {
                    NumField("Maks. czas działania (min)", s.maxRuntimeMin.toLong(), enabled = s.limitRuntimeEnabled) { v ->
                        upd { it.copy(maxRuntimeMin = v.toInt().coerceAtLeast(1)) }
                    }
                }
                LimitRow("Maks. liczba akcji", s.limitActionsEnabled, { on -> upd { it.copy(limitActionsEnabled = on) } },
                    "Bez limitu liczba kliknięć i przesunięć nie jest ograniczana.") {
                    NumField("Maks. liczba akcji", s.maxActions.toLong(), enabled = s.limitActionsEnabled) { v ->
                        upd { it.copy(maxActions = v.toInt().coerceAtLeast(1)) }
                    }
                }
                LimitRow("Maks. iteracji jednej pętli", s.limitLoopEnabled, { on -> upd { it.copy(limitLoopEnabled = on) } },
                    "Bez limitu pętle „do skutku” nie mają zabezpieczenia, a powtórzenia makr i algorytmów nie są ograniczane.") {
                    NumField("Maks. iteracji jednej pętli", s.maxLoopIterations.toLong(), enabled = s.limitLoopEnabled) { v ->
                        upd { it.copy(maxLoopIterations = v.toInt().coerceAtLeast(1)) }
                    }
                }
                LimitRow("Maks. pojedyncze oczekiwanie", s.limitWaitEnabled, { on -> upd { it.copy(limitWaitEnabled = on) } },
                    "Bez limitu bloki CZEKAJ i czas szukania wzorca nie są skracane.") {
                    NumField("Maks. pojedyncze oczekiwanie (s)", s.maxWaitMs / 1000, enabled = s.limitWaitEnabled) { v ->
                        upd { it.copy(maxWaitMs = v.coerceAtLeast(1) * 1000) }
                    }
                }
            }

            Section("Uruchamianie") {
                NumField("Odliczanie przed startem (s)", s.startDelaySec.toLong()) { v ->
                    upd { it.copy(startDelaySec = v.toInt().coerceIn(0, 60)) }
                }
            }

            Section("Test algorytmu") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(s.showClickMarkers, { on -> upd { it.copy(showClickMarkers = on) } })
                    Text(
                        if (s.showClickMarkers) "Pokazuj miejsca kliknięć – włączone" else "Pokazuj miejsca kliknięć – wyłączone",
                        fontWeight = FontWeight.Medium, fontSize = 13.sp
                    )
                }
                Text(
                    "Podczas testu (TESTUJ w edytorze oraz tryb krokowy ⏮ ⏭ w pływającym panelu) bot rysuje na ekranie " +
                        "pulsujący znacznik tam, gdzie klika (dla przesunięcia – ścieżkę ze strzałką) wraz ze współrzędnymi. " +
                        "Znaczniki nie pojawiają się podczas zwykłej pracy bota i znikają przed każdym zrzutem ekranu, " +
                        "więc nie zakłócają szukania wzorców.",
                    fontSize = 11.sp, color = Muted
                )
            }

            Section("Losowość działań") {
                Text(
                    "Każde kliknięcie i przesunięcie jest losowo przesuwane o −X…+X pikseli (dla małych wzorców mniej – " +
                        "nigdy poza ich obrys). Do każdego oczekiwania (blok CZEKAJ) dodawane jest " +
                        "losowo 0…Y ms. Wpisz 0, aby wyłączyć.",
                    fontSize = 11.sp, color = Muted
                )
                NumField("Losowe przesunięcie kliknięcia ± (px)", s.clickJitterPx.toLong()) { v ->
                    upd { it.copy(clickJitterPx = v.toInt().coerceIn(0, 200)) }
                }
                NumField("Losowy czas dodany do oczekiwania: 0 do (ms)", s.extraDelayMaxMs.toLong()) { v ->
                    upd { it.copy(extraDelayMaxMs = v.toInt().coerceIn(0, 60_000)) }
                }
            }

            Section("Kopia zapasowa") {
                Text(
                    "Zapisuje do jednego pliku ZIP wszystkie algorytmy, wzorce (obrazy) i ustawienia – " +
                        "bez logów, więc plik jest mały. Po aktualizacji lub reinstalacji aplikacji wczytaj go z powrotem.",
                    fontSize = 11.sp, color = Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("ZAPISZ KOPIĘ", Primary) {
                        exportLauncher.launch("AutoBot-kopia-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip")
                    }
                    AppButton("WCZYTAJ KOPIĘ", Color(0xFF55657A)) { importLauncher.launch(arrayOf("*/*")) }
                }
            }
        }
    }

    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Wczytaj kopię zapasową") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "DOŁĄCZ: dodaje zawartość kopii do tego, co już masz; elementy o tym samym identyfikatorze są aktualizowane. " +
                            "Ustawienia pozostają bez zmian.",
                        fontSize = 12.sp
                    )
                    AppButton("DOŁĄCZ / ZAKTUALIZUJ", Primary, Modifier.fillMaxWidth()) { importUri = null; doImport(uri, false) }
                    Text(
                        "ZASTĄP: usuwa obecne algorytmy, wzorce i ustawienia, a w ich miejsce wstawia zawartość kopii.",
                        fontSize = 12.sp
                    )
                    AppButton("ZASTĄP WSZYSTKO", Danger, Modifier.fillMaxWidth()) { importUri = null; doImport(uri, true) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ importUri = null }) { Text("ANULUJ") } }
        )
    }
}
