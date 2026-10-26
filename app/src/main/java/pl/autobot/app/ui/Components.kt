package pl.autobot.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import pl.autobot.app.App
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* Paleta z prototypu Figma */
val Bg = Color(0xFFD9E7EE)
val Primary = Color(0xFF2F5BEA)
val Danger = Color(0xFFC95A57)
val Ink = Color(0xFF1B2733)
val Muted = Color(0xFF66737F)
/* Bloki: tło + kolor akcentu (pasek z lewej strony karty) */
val BlockAction = Color(0xFFEEF2FF);  val BlockActionAccent = Color(0xFF6366F1)   // akcje / kliknięcia
val BlockIf = Color(0xFFFEF3C7);      val BlockIfAccent = Color(0xFFD97706)       // warunki
val BlockLoop = Color(0xFFFCE7F3);    val BlockLoopAccent = Color(0xFFDB2777)     // pętle
val BlockTime = Color(0xFFE0F2FE);    val BlockTimeAccent = Color(0xFF0284C7)     // czas / harmonogram
val BlockControl = Color(0xFFD1FAE5); val BlockControlAccent = Color(0xFF059669)  // sterowanie / przepływ

fun folderLabel(f: String): String = if (f.isEmpty()) "WZORCE" else "WZORCE / " + f.replace("/", " / ")

fun formatStamp(ms: Long): String =
    if (ms <= 0L) "brak daty" else java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
fun AutoBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Primary, background = Bg, surface = Color.White, error = Danger, onSurface = Ink
        ),
        content = content
    )
}

/** Obraz przekazywany między usługą/galerią a ekranem kadrowania wzorca. */
object PendingImage {
    @Volatile
    var bitmap: Bitmap? = null
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.openAccessibilitySettings() {
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun ScreenHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
            } else {
                Spacer(Modifier.width(6.dp))
            }
            Icon(icon, null, tint = Ink)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(subtitle, fontSize = 12.sp, color = Muted, lineHeight = 15.sp)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun AppButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = contentPadding
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun WhiteCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun NumField(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val f = t.filter { it.isDigit() }.take(9)
            text = f
            onChange(f.toLongOrNull() ?: 0L)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

/** Polska odmiana liczebnika: 1 krok, 2 kroki, 5 kroków. */
fun plural(n: Int, one: String, few: String, many: String): String = when {
    n == 1 -> "$n $one"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n $few"
    else -> "$n $many"
}

/** Lista rozwijana (id → etykieta) używana do wyboru wzorca lub algorytmu. */
@Composable
fun Picker(
    placeholder: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selectedId }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current?.second ?: placeholder, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("(brak – najpierw utwórz)") }, onClick = { open = false })
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}

/** Mała miniatura wzorca (obraz zapisany w pamięci aplikacji). */
@Composable
fun PatternThumb(app: App, id: String?, size: Dp = 28.dp) {
    val img = remember(id) { id?.let { app.repo.patternBitmap(it)?.asImageBitmap() } }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(3.dp)).background(Color(0xFFEFEFEF)),
        contentAlignment = Alignment.Center
    ) {
        if (img != null) Image(img, null, Modifier.size(size), contentScale = ContentScale.Fit)
    }
}

/** Lista rozwijana wzorców z miniaturami przed nazwą. */
@Composable
fun PatternPicker(
    app: App,
    placeholder: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selectedId }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            if (current != null) { PatternThumb(app, current.first, 24.dp); Spacer(Modifier.width(8.dp)) }
            Text(current?.second ?: placeholder, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("(brak – najpierw utwórz)") }, onClick = { open = false })
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { PatternThumb(app, id, 32.dp) },
                    onClick = { onSelect(id); open = false }
                )
            }
        }
    }
}

/**
 * Karta przesuwana palcem: z prawej na lewo ([onSwipeLeft]) pokazuje kosz, z lewej na prawo ([onSwipeRight])
 * ikonę dodania do BOT-a. Po wykonaniu gestu karta wraca na miejsce – akcja (np. pytanie o potwierdzenie) dzieje się osobno.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeCard(
    onSwipeLeft: (() -> Unit)?,
    onSwipeRight: (() -> Unit)? = null,
    rightIcon: ImageVector = Icons.Default.SmartToy,
    content: @Composable () -> Unit,
) {
    val left by rememberUpdatedState(onSwipeLeft)
    val right by rememberUpdatedState(onSwipeRight)
    // gest jest zaliczony dopiero, gdy kartę przesunięto co najmniej o połowę szerokości ekranu
    val halfScreenPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() } / 2f
    var stateRef: SwipeToDismissBoxState? = null
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        val moved = runCatching { kotlin.math.abs(stateRef?.requireOffset() ?: 0f) }.getOrDefault(0f)
        if (moved >= halfScreenPx) {
            when (v) {
                SwipeToDismissBoxValue.EndToStart -> left?.invoke()
                SwipeToDismissBoxValue.StartToEnd -> right?.invoke()
                else -> Unit
            }
        }
        false   // karta zawsze wraca na miejsce
    })
    stateRef = state
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = onSwipeLeft != null,
        enableDismissFromStartToEnd = onSwipeRight != null,
        backgroundContent = {
            val dir = state.dismissDirection
            val toLeft = dir == SwipeToDismissBoxValue.EndToStart
            val toRight = dir == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                    .background(if (toLeft) Danger else if (toRight) Primary else Color.Transparent),
                contentAlignment = if (toLeft) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                if (toLeft) Icon(Icons.Default.Delete, "Usuń", tint = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
                if (toRight) Icon(rightIcon, "Akcja", tint = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
            }
        },
        content = { content() }
    )
}
