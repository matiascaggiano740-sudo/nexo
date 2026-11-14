package com.nexo.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Cream = Color(0xFFF3EEE4)
private val Brown = Color(0xFF3A342C)
private val Glass = Color.White.copy(alpha = 0.20f)

// Apps frecuentes con prioridad visual (se usa la primera instalada de cada grupo).
private val PRIORITY = listOf(
    listOf("com.whatsapp", "com.whatsapp.w4b"),
    listOf("com.spotify.music"),
    listOf("com.instagram.android"),
    listOf("com.google.android.apps.maps"),
    listOf("com.miui.gallery", "com.google.android.apps.photos"),
    listOf("com.android.chrome"),
)

@Composable
fun Home() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }
    var dock by remember { mutableStateOf(emptyList<AppItem>()) }
    var drawer by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            apps = loadApps(ctx)
            val chrome = apps.find { it.pkg == "com.android.chrome" }
            val sys = loadSystemDock(ctx)
            dock = buildList { sys.getOrNull(0)?.let(::add); sys.getOrNull(1)?.let(::add); chrome?.let(::add); sys.getOrNull(2)?.let(::add) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(60_000L - System.currentTimeMillis() % 60_000L) }
    }
    BackHandler(drawer) { drawer = false }

    val priority = PRIORITY.mapNotNull { g -> g.firstNotNullOfOrNull { p -> apps.find { it.pkg == p } } }
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("es")))
        .replaceFirstChar { it.uppercase() }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x1A000000), Color(0x70000000))))
            .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -25) drawer = true } }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 92.sp,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 24.dp))
            Text(date, color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Surface(color = Glass, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text("--°", color = Color.White, fontSize = 30.sp, fontFamily = FontFamily.Serif)
                    Text("Clima y ubicación: se configuran en el siguiente paso", color = Color.White, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            priority.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { AppIcon(it, 66.dp, Color.White) }
                }
            }
            Spacer(Modifier.height(6.dp))
            QuoteCard("Lo simple también es hermoso.")
            Spacer(Modifier.weight(1f))
            Surface(color = Glass, shape = RoundedCornerShape(23.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp).clickable { drawer = true }) {
                Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Buscar apps y web", color = Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(color = Glass, shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth().height(76.dp)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    dock.forEach { AppIcon(it, 52.dp, Color.White, showLabel = false) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        AnimatedVisibility(drawer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Drawer(apps) { drawer = false }
        }
    }
}

@Composable
fun QuoteCard(text: String) {
    Box(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFA89578), Color(0xFF6D5A45))))
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99281F18)))))
        Text(text, color = Color.White, fontSize = 26.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
            lineHeight = 30.sp, modifier = Modifier.align(Alignment.BottomStart).padding(20.dp).widthIn(max = 240.dp))
    }
}

@Composable
fun AppIcon(app: AppItem, size: Dp, textColor: Color, showLabel: Boolean = true, onLaunch: () -> Unit = {}) {
    val ctx = LocalContext.current
    Column(
        Modifier.width(size + 12.dp).clickable { onLaunch(); runCatching { ctx.startActivity(app.intent) } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(app.icon, app.label, Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)))
        if (showLabel) Text(app.label, color = textColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun Drawer(apps: List<AppItem>, close: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(apps, q) { apps.filter { it.label.contains(q, ignoreCase = true) } }
    Column(Modifier.fillMaxSize().background(Cream.copy(alpha = 0.96f)).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Buscar aplicaciones") },
            shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions.Default)
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, contentPadding = PaddingValues(bottom = 32.dp)) {
            items(list, key = { it.pkg }) { AppIcon(it, 56.dp, Brown, onLaunch = close) }
        }
    }
}
