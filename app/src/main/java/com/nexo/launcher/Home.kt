package com.nexo.launcher

import android.content.Intent
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ClipDescription
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Cream = Color(0xFFF3EEE4)
private val Brown = Color(0xFF3A342C)

private val Glass = Color.White.copy(alpha = 0.20f)

private val PRIORITY = listOf(
    listOf("com.whatsapp", "com.whatsapp.w4b"),
    listOf("com.spotify.music"),
        listOf("com.instagram.android"),
    listOf("com.google.android.apps.maps"),
    listOf("com.miui.gallery", "com.google.android.apps.photos"),
    listOf("com.android.chrome"),
)

private val MESSAGES = listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging")

private val FOLDERS = listOf(
    "Social" to listOf(
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill", "com.pinterest", "org.telegram.messenger", "com.twitter.android",
        "com.instagram.barcelona", "com.snapchat.android", "com.facebook.lite",
    ),
    "Google" to listOf(
        "com.google.android.apps.maps", "com.google.android.gm", "com.google.android.youtube",
        "com.google.android.apps.docs", "com.google.android.googlequicksearchbox",
        "com.google.android.apps.photos", "com.google.android.apps.translate",
    ),
    "Herramientas" to listOf(
        "com.miui.calculator", "com.google.android.calculator", "com.android.deskclock",
        "com.google.android.deskclock", "com.android.calendar", "com.google.android.calendar",
        "com.miui.securitycenter", "com.miui.notes",
    ),
)

@Composable
fun Home() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }
    var dock by remember { mutableStateOf(emptyList<AppItem>()) }
            var homePackages by remember {
        mutableStateOf(
            ctx.getSharedPreferences("nexo_home", 0)
                .getString("packages", "")
                                                ?.takeIf { it.isNotBlank() }
                ?.split("|")
                ?: emptyList()
        )
    }
    var homeCapacity by remember { mutableIntStateOf(0) }
    val homeRowHeightPx = with(LocalDensity.current) { 90.dp.roundToPx() }
    var drawer by remember { mutableStateOf(false) }
                var editingHome by remember { mutableStateOf(true) }
var lastPackageRefresh by remember { mutableStateOf(0) }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var city by remember { mutableStateOf(savedCity(ctx)) }
    var weather by remember { mutableStateOf<WeatherInfo?>(null) }
    var editCity by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf(savedQuote(ctx)) }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var photoTick by remember { mutableStateOf(0) }
    var editCard by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            savePhoto(ctx, uri.toString())
            photoTick++
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val list = loadApps(ctx)
            val sys = loadSystemDock(ctx)
            val msgs = list.firstOrNull { it.pkg in MESSAGES } ?: sys.getOrNull(1)
            val chrome = list.find { it.pkg == "com.android.chrome" }
            dock = listOfNotNull(sys.getOrNull(0), msgs, chrome, sys.getOrNull(2))
            apps = list
        }
    }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(60_000L - System.currentTimeMillis() % 60_000L) }
    }
    LaunchedEffect(city) {
        while (city.isNotBlank()) {
            weather = withContext(Dispatchers.IO) { loadWeather(city) } ?: weather
            delay(30 * 60_000L)
        }
    }
    LaunchedEffect(photoTick) { photo = withContext(Dispatchers.IO) { loadCardPhoto(ctx) } }

    BackHandler(drawer) { drawer = false }
    BackHandler(openFolder != null) { openFolder = null }

            val priority = if (homePackages.isEmpty()) {
        PRIORITY.mapNotNull { group ->
            group.firstNotNullOfOrNull { pkg -> apps.find { it.pkg == pkg } }
        }
    } else {
        homePackages.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
    }

    val homeSlots: List<AppItem?> = if (homePackages.isEmpty()) {
        priority
    } else {
        homePackages.map { pkg ->
            apps.firstOrNull { it.pkg == pkg }
        }
    }

    val folders = FOLDERS.mapNotNull { (name, pkgs) ->
        val found = pkgs.mapNotNull { p -> apps.find { it.pkg == p } }
        if (found.isEmpty()) null else name to found
    }

    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("es")))
        .replaceFirstChar { it.uppercase() }
    val w = weather
        val homeDropTarget = remember(editingHome, homePackages, priority, apps, homeCapacity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                if (clip.itemCount == 0) return false

                val pkg = clip.getItemAt(0).text?.toString() ?: return false
                if (apps.none { it.pkg == pkg }) return false

                val current = if (homePackages.isEmpty()) {
                    priority.map { it.pkg }
                } else {
                    homePackages
                }

                if (pkg !in current) {
                    if (current.size >= homeCapacity) {
                        Toast.makeText(ctx, "No hay espacio libre en inicio", Toast.LENGTH_SHORT).show()
                        return true
                    }

                    val updated = current + pkg
                    ctx.getSharedPreferences("nexo_home", 0)
                        .edit()
                        .putString("packages", updated.joinToString("|"))
                        .apply()
                    homePackages = updated
                }
                drawer = false
                return true
            }
        }
        }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x1A000000), Color(0x70000000))))
            .then(
                if (!drawer) {
                    Modifier
                        .pointerInput(drawer) {
                            detectTapGestures(onLongPress = { editingHome = true })
                        }
                        .pointerInput(drawer) {
                            detectVerticalDragGestures { _, dy ->
                                if (dy < -25) drawer = true
                            }
                        }
                } else Modifier
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                                        false

                },
                target = homeDropTarget
            )
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 80.sp,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 4.dp))
            Text(date, color = Color.White, fontSize = 15.sp)
                                    Spacer(Modifier.height(12.dp))
            Surface(
                color = Glass,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { editCity = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (w != null) "${w.temp}°" else "--°",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Serif
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                                                                        if (w != null) w.label + " · " + w.city
                        else if (city.isBlank()) "Toca para elegir tu ciudad"
                        else "Cargando clima...",
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val rows = size.height / homeRowHeightPx
                        homeCapacity = (rows * 4).coerceIn(0, 28)
                    },
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = homeCapacity,
                    key = { index -> "home-cell-$index" }
                ) { index ->
                                        val app = homeSlots.getOrNull(index)
                    HomeAppCell(
                        app = app,
                        index = index,
                        editingHome = editingHome,
                        onLongPress = { editingHome = true },
                        onDrop = { draggedPkg, targetIndex ->
                            if (apps.any { it.pkg == draggedPkg }) {
                                val slots = (if (homePackages.isEmpty()) {
                                    priority.map { it.pkg }
                                } else {
                                    homePackages
                                }).toMutableList()

                                while (slots.size < homeCapacity) {
                                    slots.add("-")
                                }

                                if (targetIndex in slots.indices) {
                                    val sourceIndex = slots.indexOf(draggedPkg)

                                    if (sourceIndex >= 0) {
                                        if (sourceIndex != targetIndex) {
                                            val displaced = slots[targetIndex]
                                            slots[targetIndex] = draggedPkg
                                            slots[sourceIndex] = displaced
                                        }
                                    } else if (slots[targetIndex] == "-") {
                                        slots[targetIndex] = draggedPkg
                                    } else {
                                        Toast.makeText(
                                            ctx,
                                            "Esa celda ya está ocupada",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }

                                    ctx.getSharedPreferences("nexo_home", 0)
                                        .edit()
                                        .putString("packages", slots.joinToString("|"))
                                        .apply()
                                    homePackages = slots
                                    drawer = false
                                }
                            }
                        }
                    )
                }
                        }


            if (folders.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    folders.forEach { (name, list) ->
                        FolderIcon(name, list, 60.dp) { openFolder = name }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            QuoteCard(quote, photo) { editCard = true }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Glass,
                    shape = RoundedCornerShape(23.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp).clickable { drawer = true }
                ) {
                    Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Buscar apps y web", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                color = Glass,
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.fillMaxWidth().height(76.dp)
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dock.forEach { AppIcon(it, 52.dp, Color.White, showLabel = false) }
                }
            }

                        Spacer(Modifier.height(10.dp))
        }
        AnimatedVisibility(drawer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Drawer(apps, editingHome) { drawer = false }
        }
        openFolder?.let { name ->
            folders.find { it.first == name }?.let { FolderPanel(name, it.second) { openFolder = null } }
        }
    }
    if (editCity) {
        var t by remember { mutableStateOf(city) }
        AlertDialog(
            onDismissRequest = { editCity = false },
            title = { Text("Tu ciudad") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("Ej: Buenos Aires") }) },
            confirmButton = { TextButton({ saveCity(ctx, t.trim()); city = t.trim(); weather = null; editCity = false }) { Text("Guardar") } },
            dismissButton = { TextButton({ editCity = false }) { Text("Cancelar") } },
        )
    }
    if (editCard) {
        var t by remember { mutableStateOf(quote) }
        AlertDialog(
            onDismissRequest = { editCard = false },
            title = { Text("Tu tarjeta") },
            text = {
                Column {
                    OutlinedTextField(t, { t = it.take(80) }, label = { Text("Frase") }, modifier = Modifier.fillMaxWidth())
                    TextButton({ picker.launch(arrayOf("image/*")) }) { Text("Elegir foto de la galería") }
                }
            },
            confirmButton = {
                TextButton({
                    val v = t.trim().ifBlank { DEFAULT_QUOTE }
                    saveQuote(ctx, v); quote = v; editCard = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton({ editCard = false }) { Text("Cancelar") } },
        )
    }
}
@Composable
private fun HomeAppsDialog(
    apps: List<AppItem>,
    selectedPackages: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val selected = remember(selectedPackages) {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(selectedPackages)
        }
    }

    val displayedApps =
        selected.mapNotNull { pkg -> apps.find { it.pkg == pkg } } +
            apps.filter { app -> app.pkg !in selected }
                .sortedBy { it.label.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accesos del inicio") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                Text("Elegí las apps y ordená las seleccionadas con las flechas.")
                Spacer(Modifier.height(10.dp))

                displayedApps.forEach { app ->
                    val position = selected.indexOf(app.pkg)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = position >= 0,
                            onCheckedChange = { checked ->
                                if (checked && app.pkg !in selected) {
                                    selected.add(app.pkg)
                                } else if (!checked) {
                                    selected.remove(app.pkg)
                                }
                            }
                        )

                        Text(
                            app.label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (position >= 0) {
                            TextButton(
                                enabled = position > 0,
                                onClick = {
                                    val item = selected.removeAt(position)
                                    selected.add(position - 1, item)
                                }
                            ) {
                                Text("↑")
                            }

                            TextButton(
                                enabled = position < selected.lastIndex,
                                onClick = {
                                    val item = selected.removeAt(position)
                                    selected.add(position + 1, item)
                                }
                            ) {
                                Text("↓")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onSave(selected.toList()) }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun QuoteCard(text: String, photo: ImageBitmap?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFA89578), Color(0xFF6D5A45))))
            .clickable { onClick() }
    ) {
        if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99281F18)))))
        Text(text, color = Color.White, fontSize = 21.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
            lineHeight = 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).widthIn(max = 260.dp))
    }
}

@Composable
fun FolderIcon(name: String, apps: List<AppItem>, size: Dp, onClick: () -> Unit) {
    Column(Modifier.width(size + 12.dp).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Glass).padding(7.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                apps.take(4).chunked(2).forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        r.forEach { Image(it.icon, null, Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))) }
                    }
                }
            }
        }
        Text(name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun FolderPanel(name: String, apps: List<AppItem>, close: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99201810)).pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp).clip(RoundedCornerShape(32.dp)).background(Cream.copy(alpha = 0.95f))
                .pointerInput(Unit) { detectTapGestures { } }.padding(22.dp)
        ) {
            Text(name, color = Brown, fontSize = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(18.dp))
                        Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                apps.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { AppIcon(it, 56.dp, Brown, onLaunch = close) }
                        repeat(3 - row.size) { Spacer(Modifier.width(68.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun AppIcon(
    app: AppItem,
    size: Dp,
    textColor: Color,
    showLabel: Boolean = true,
    onLaunch: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    dragPayload: String? = null
) {
    val ctx = LocalContext.current
    val dragModifier = if (dragPayload != null) {
                Modifier.dragAndDropSource { _: androidx.compose.ui.geometry.Offset ->
            DragAndDropTransferData(
                ClipData.newPlainText("nexo-app", dragPayload)
            )
        }
    } else {
        Modifier
    }
    val iconModifier = Modifier
        .width(size + 12.dp)
        .then(dragModifier)
        .let { base ->
            if (dragPayload != null) {
                base.clickable {
                    onLaunch()
                    runCatching { ctx.startActivity(app.intent) }
                }
            } else {
                base.combinedClickable(
                    onClick = {
                        onLaunch()
                        runCatching { ctx.startActivity(app.intent) }
                    },
                    onLongClick = onLongPress
                )
            }
        }
    Column(
        iconModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(app.icon, app.label, Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)))
        if (showLabel) {
            Text(
                app.label,
                color = textColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeAppCell(
    app: AppItem?,
    index: Int,
    editingHome: Boolean,
    onLongPress: () -> Unit,
    onDrop: (String, Int) -> Unit
) {
    val dropTarget = remember(editingHome, index, onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                if (clip.itemCount == 0) return false

                val pkg = clip.getItemAt(0).text?.toString() ?: return false
                onDrop(pkg, index)
                return true
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(90.dp)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.toAndroidDragEvent().clipDescription
                        ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                },
                target = dropTarget
            ),
        contentAlignment = Alignment.Center
    ) {
        if (app != null) {
            AppIcon(
                app,
                60.dp,
                Color.White,
                onLongPress = onLongPress,
                dragPayload = app.pkg
            )
        }
    }
}

private val SOCIAL_PACKAGES = setOf(
    "com.whatsapp",
    "com.whatsapp.w4b",
    "com.instagram.android",
    "com.facebook.katana",
    "com.facebook.orca",
    "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill",
    "com.pinterest",
    "org.telegram.messenger",
    "com.twitter.android",
    "com.instagram.barcelona",
    "com.facebook.lite",
    "com.discord",
    "com.linkedin.android",
    "com.reddit.frontpage",
)

private fun AppItem.matchesAny(vararg terms: String): Boolean {
    val text = (label + " " + pkg).lowercase(Locale.ROOT)
    return terms.any { term -> text.contains(term) }
}

private fun appCategory(app: AppItem): String {
    val info = app.category

    return when {
        app.pkg in SOCIAL_PACKAGES ||
            info == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL ->
            "Redes Sociales"

        app.pkg == "com.spotify.music" ||
            app.pkg == "com.snapchat.android" ||
            info == android.content.pm.ApplicationInfo.CATEGORY_AUDIO ||
            info == android.content.pm.ApplicationInfo.CATEGORY_VIDEO ||
            info == android.content.pm.ApplicationInfo.CATEGORY_IMAGE ||
            app.matchesAny(
                "spotify", "snapchat", "music", "música", "podcast",
                "radio", "netflix", "youtube", "video", "vídeo",
                "camera", "cámara", "gallery", "galería", "photos",
                "fotos", "editor", "capcut"
            ) ->
            "Multimedia"

        info == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
            app.matchesAny(
                "game", "juego", "pubg", "supercell", "garena",
                "activision", "roblox", "moonton", "netmarble",
                "clash", "minecraft"
            ) ->
            "Juegos"

        app.pkg == "com.android.chrome" ||
            app.pkg.startsWith("com.google.android.") ||
            app.pkg.startsWith("com.google.") ||
            app.matchesAny("google chrome", "gmail", "google drive") ->
            "Google"

        info == android.content.pm.ApplicationInfo.CATEGORY_MAPS ||
            app.matchesAny(
                "maps", "mapas", "navigation", "navegación",
                "uber", "cabify", "booking", "airbnb", "despegar"
            ) ->
            "Mapas y viajes"

        app.matchesAny(
            "mercadopago", "mercado pago", "banco", "bank",
            "wallet", "billetera", "paypal", "prex", "uala",
            "brubank", "finanzas"
        ) ->
            "Finanzas"

        app.matchesAny(
            "shopping", "shop", "compras", "store", "tienda",
            "mercadolibre", "mercado libre", "amazon",
            "aliexpress", "temu", "shein", "pedidosya", "rappi"
        ) ->
            "Compras"

        info == android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY ||
            app.matchesAny(
                "calendar", "calendario", "docs", "document",
                "office", "notas", "notes", "task", "tarea",
                "todo", "drive", "mail", "email"
            ) ->
            "Productividad"

        app.matchesAny(
            "whatsapp", "telegram", "messenger", "messages",
            "mensajes", "phone", "teléfono", "dialer",
            "llamadas", "contactos", "contacts"
        ) ->
            "Comunicación"

        info == android.content.pm.ApplicationInfo.CATEGORY_NEWS ||
            app.matchesAny(
                "news", "noticias", "diario", "reader",
                "lectura", "kindle", "book", "libro"
            ) ->
            "Noticias y lectura"

        app.pkg.startsWith("com.android.") ||
            app.pkg.startsWith("com.miui.") ||
            app.matchesAny(
                "settings", "ajustes", "calculator", "calculadora",
                "clock", "reloj", "security", "seguridad",
                "file manager", "archivos", "tools", "herramientas"
            ) ->
            "Herramientas"

        else ->
            "Otras"
    }
}

@Composable
private fun DrawerCategoryTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF24D69B)))
        Surface(color = Color(0xFF171411), shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        }
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF24D69B)))
    }
}
@Composable
private fun AppWithCategoryMenu(
    app: AppItem,
    category: String,
    close: () -> Unit,
    onCategoryChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(app, 56.dp, Color.White, onLaunch = close)

        Box {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Mover")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                listOf(
                    "Redes Sociales", "Multimedia", "Juegos", "Google",
                    "Mapas y viajes", "Finanzas", "Compras", "Productividad",
                    "Comunicación", "Noticias y lectura", "Herramientas", "Otras"
                ).forEach { name ->
                    DropdownMenuItem(
                        text = { Text(if (name == category) "✓ $name" else name) },
                        onClick = {
                            onCategoryChange(name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

}
@Composable
fun Drawer(apps: List<AppItem>, editingHome: Boolean, close: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val drawerListState = rememberLazyListState()
    var showOrganizer by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
var playCategories by remember {
    mutableStateOf(
        ctx.getSharedPreferences("nexo_play_categories", 0)
            .all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()
    )
}
var manualCategories by remember {
    mutableStateOf(
        ctx.getSharedPreferences("nexo_app_categories", 0)
            .all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()
    )
}
LaunchedEffect(apps) {
    val missing = apps.map { it.pkg }.filterNot { playCategories.containsKey(it) }
    if (missing.isEmpty()) return@LaunchedEffect

    val semaphore = Semaphore(4)
    val fetched = coroutineScope {
        missing.map { pkg ->
            async {
                semaphore.withPermit {
                    runCatching {
                        withContext(Dispatchers.IO) { loadPlayStoreCategory(pkg) }
                    }.getOrNull()?.let { category -> pkg to category }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    if (fetched.isNotEmpty()) {
        val cache = ctx.getSharedPreferences("nexo_play_categories", 0).edit()
        fetched.forEach { (pkg, category) -> cache.putString(pkg, category) }
        cache.apply()
        playCategories = playCategories + fetched
    }
}
      val categories = remember(apps, q, playCategories, manualCategories) {
        val filtered = apps.filter { it.label.contains(q, ignoreCase = true) }
         listOf("Redes Sociales", "Multimedia", "Juegos", "Google", "Mapas y viajes", "Finanzas", "Compras", "Comida", "Productividad", "Comunicación", "Noticias y lectura", "Herramientas", "Otras").mapNotNull { name ->
              val members = filtered.filter { app ->
    (
        manualCategories[app.pkg]
            ?: playCategories[app.pkg]
            ?: if (app.matchesAny("grido", "chef", "daniel", "mcdonald", "pedidosya", "rappi")) "Comida"
            else appCategory(app)
    ) == name
}

            if (members.isEmpty()) null else name to members
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF171411)).statusBarsPadding().padding(horizontal = 20.dp)) {
    var menuExpanded by remember { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))

    Row(
    Modifier.fillMaxWidth().pointerInput(Unit) {
        var totalDrag = 0f
        var alreadyClosed = false
        detectVerticalDragGestures(onVerticalDrag = { change, dragAmount ->
            totalDrag += dragAmount
            if (!alreadyClosed && totalDrag > 60f) {
                alreadyClosed = true
                close()
                change.consume()
            }
        })
    },
    verticalAlignment = Alignment.CenterVertically
) {

        OutlinedTextField(
            q,
            { q = it },
            singleLine = true,
            placeholder = { Text("Buscar aplicaciones") },
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions.Default
        )

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Text("⋮", color = Color.White, fontSize = 26.sp)
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Organizar aplicaciones") },
                    onClick = {
    menuExpanded = false
    showOrganizer = true
}

                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
        LazyColumn(state = drawerListState, contentPadding = PaddingValues(bottom = 32.dp)) {
            categories.forEach { (title, members) ->
                item(key = "category-$title") { DrawerCategoryTitle(title) }
                items(members.chunked(4), key = { row -> "$title-${row.joinToString { it.pkg }}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { app ->
    AppIcon(app, 56.dp, Color.White, onLaunch = close, dragPayload = app.pkg)
}


                                                repeat(4 - row.size) { Spacer(Modifier.width(68.dp)) }
 
                    }

                }
            }        }
        if (showOrganizer) {
            OrganizeAppsDialog(
                apps = apps,
                playCategories = playCategories,
                manualCategories = manualCategories,
                onCategoryChange = { pkg, name ->
                    ctx.getSharedPreferences("nexo_app_categories", 0)
                        .edit()
                        .putString(pkg, name)
                        .apply()
                    manualCategories = manualCategories + (pkg to name)
                },
                onDismiss = { showOrganizer = false }
            )
        }
    }
}
@Composable
private fun OrganizeAppsDialog(

    apps: List<AppItem>,
    playCategories: Map<String, String>,
    manualCategories: Map<String, String>,
    onCategoryChange: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val categoryNames = listOf(
        "Redes Sociales", "Multimedia", "Juegos", "Google",
        "Mapas y viajes", "Finanzas", "Compras", "Comida", "Productividad",
        "Comunicación", "Noticias y lectura", "Herramientas", "Otras"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Organizar aplicaciones") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                apps.sortedBy { it.label.lowercase() }.forEach { app ->
                    var expanded by remember(app.pkg) { mutableStateOf(false) }
                    val currentCategory =
    manualCategories[app.pkg]
        ?: playCategories[app.pkg]
        ?: if (app.matchesAny("grido", "chef", "daniel", "mcdonald", "pedidosya", "rappi")) "Comida"
        else appCategory(app)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app.label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(currentCategory, maxLines = 1)
                            }

                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                categoryNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            onCategoryChange(app.pkg, name)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Listo")
            }
        }
    )
}
private fun loadPlayStoreCategory(pkg: String): String? {
    val connection = java.net.URL(
        "https://play.google.com/store/apps/details?id=$pkg&hl=es_AR&gl=AR"
    ).openConnection() as java.net.HttpURLConnection

    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")

    return try {
        val html = connection.inputStream.bufferedReader().use { it.readText() }
        val genreStart = html.indexOf("itemprop=\"genre\"")

        if (genreStart < 0) {
            null
        } else {
            val section = html.substring(
                genreStart,
                (genreStart + 4000).coerceAtMost(html.length)
            )
            val marker = "/store/apps/category/"
            val markerStart = section.indexOf(marker)

            if (markerStart < 0) {
                null
            } else {
                val code = section
                    .substring(markerStart + marker.length)
                    .takeWhile { it.isUpperCase() || it == '_' }

                when (code) {
                    "SOCIAL" -> "Redes Sociales"
                    "MUSIC_AND_AUDIO", "VIDEO_PLAYERS", "PHOTOGRAPHY" -> "Multimedia"
                    "GAME" -> "Juegos"
                    "MAPS_AND_NAVIGATION" -> "Mapas y viajes"
                    "FINANCE" -> "Finanzas"
                    "FOOD_AND_DRINK" -> "Comida"
                    "SHOPPING" -> "Compras"
                    "PRODUCTIVITY" -> "Productividad"
                    "COMMUNICATION" -> "Comunicación"
                    "NEWS_AND_MAGAZINES", "BOOKS_AND_REFERENCE" -> "Noticias y lectura"
                    "TOOLS" -> "Herramientas"
                    else -> null
                }
            }
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}
