package pl.autobot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import pl.autobot.app.ui.AutoBotTheme
import pl.autobot.app.ui.BotScreen
import pl.autobot.app.ui.CropScreen
import pl.autobot.app.ui.EditorScreen
import pl.autobot.app.ui.LogsScreen
import pl.autobot.app.ui.PatternsScreen
import pl.autobot.app.ui.Primary
import pl.autobot.app.ui.ScriptsScreen
import pl.autobot.app.ui.SettingsScreen

sealed interface Route {
    data class Editor(val id: String) : Route
    data object Crop : Route
    data object Logs : Route
}

class MainActivity : ComponentActivity() {
    private val external = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { AutoBotTheme { AppRoot(external) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("crop", false)) {
            external.value = Route.Crop
            i.removeExtra("crop")
        }
        i.getStringExtra("algorithm")?.let {
            external.value = Route.Editor(it)
            i.removeExtra("algorithm")
        }
    }
}

private val tabs = listOf("BOT", "Skrypty", "Wzorce", "Ustawienia")

@Composable
fun AppRoot(external: MutableState<Route?>) {
    val app = LocalContext.current.applicationContext as App
    var tab by rememberSaveable { mutableStateOf(0) }
    var sub by remember { mutableStateOf<Route?>(null) }
    var patternFolder by rememberSaveable { mutableStateOf("") }

    val ext = external.value
    LaunchedEffect(ext) {
        if (ext != null) {
            sub = ext
            if (ext is Route.Editor) tab = 1
            external.value = null
        }
    }

    val current = sub
    if (current != null) {
        BackHandler { sub = null }
        when (current) {
            is Route.Editor -> EditorScreen(app, current.id, onOpen = { sub = Route.Editor(it) }) { sub = null }
            is Route.Crop -> CropScreen(app, patternFolder) { sub = null; tab = 2 }
            is Route.Logs -> LogsScreen(app) { sub = null }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> BotScreen(app) { sub = Route.Editor(it) }
                1 -> ScriptsScreen(app) { sub = Route.Editor(it) }
                2 -> PatternsScreen(app, patternFolder, { patternFolder = it }) { sub = Route.Crop }
                else -> SettingsScreen(app) { sub = Route.Logs }
            }
        }
        NavigationBar(containerColor = Color.White) {
            val icons = listOf(Icons.Default.SmartToy, Icons.Default.Code, Icons.Default.Image, Icons.Default.Settings)
            tabs.forEachIndexed { i, label ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Icon(icons[i], label) },
                    label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary, selectedTextColor = Primary,
                        indicatorColor = Color(0xFFE3EAFD)
                    )
                )
            }
        }
    }
}
