package pl.autobot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.autobot.app.App

/** Ekran logów – otwierany przyciskiem z zakładki Ustawienia. */
@Composable
fun LogsScreen(app: App, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val logs by app.repo.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Icons.Default.Description, "Logi", "Historia pracy bota i wykaz błędów", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Bg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
                AppButton("WYCZYŚĆ LOGI", Danger) { app.repo.clearLogs() }
                AppButton("KOPIUJ LOGI", Primary) {
                    clipboard.setText(AnnotatedString(logs.joinToString("\n") { app.repo.formatLog(it) }))
                    ctx.toast("Skopiowano ${logs.size} wpisów")
                }
            }
            if (logs.isEmpty()) Text("Brak wpisów.", color = Muted, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(logs) { e ->
                    val color = when (e.level) {
                        "ERROR" -> Color(0xFFB3261E)
                        "WARN" -> Color(0xFF9A6700)
                        else -> Color(0xFF1B2733)
                    }
                    Text(app.repo.formatLog(e), color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}
