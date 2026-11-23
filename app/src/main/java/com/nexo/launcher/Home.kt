package com.nexo.launcher

import android.content.Intent
import android.graphics.Rect
import android.graphics.Point
import android.graphics.Paint
import android.graphics.Canvas
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import android.view.View
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
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
import org.json.JSONArray
import org.json.JSONObject

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

private fun readUserFolders(ctx: android.content.Context): List<Pair<String, List<String>>> = runCatching {
    val raw = ctx.getSharedPreferences("nexo_home", 0).getString("user_folders", "[]") ?: "[]"
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name").trim()
        val pkgs = obj.optJSONArray("packages") ?: JSONArray()
        val values = (0 until pkgs.length()).mapNotNull { pkgs.optString(it).takeIf(String::isNotBlank) }
        if (name.isBlank() || values.isEmpty()) null else name to values
    }
}.getOrDefault(emptyList())

private fun writeUserFolders(ctx: android.content.Context, folders: List<Pair<String, List<String>>>) {
    val array = JSONArray()
    folders.forEach { (name, packages) ->
        array.put(JSONObject().put("name", name).put("packages", JSONArray(packages)))
    }
    ctx.getSharedPreferences("nexo_home", 0).edit().putString("user_folders", array.toString()).apply()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home() {
    val ctx = LocalContext.current
    val launcherActivity = ctx as? MainActivity
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }
    var dock by remember { mutableStateOf(emptyList<AppItem>()) }
    var dockPackages by remember {
        mutableStateOf(ctx.getSharedPreferences("nexo_home", 0).getString("dock_packages", "")
            ?.takeIf { it.isNotBlank() }?.split("|") ?: emptyList())
    }
    var homePackages by remember {
        val prefs = ctx.getSharedPreferences("nexo_home", 0)
        val savedPages = prefs.getString("pages", "")?.takeIf { it.isNotBlank() }
            ?.split("~")?.map { it.split("|").filter(String::isNotBlank) }
        mutableStateOf(savedPages?.firstOrNull() ?: prefs.getString("packages", "")
            ?.takeIf { it.isNotBlank() }?.split("|") ?: emptyList())
    }
    var homePages by remember {
        val prefs = ctx.getSharedPreferences("nexo_home", 0)
        val savedPages = prefs.getString("pages", "")?.takeIf { it.isNotBlank() }
            ?.split("~")?.map { it.split("|").filter(String::isNotBlank) }
        mutableStateOf(savedPages ?: listOf(homePackages))
    }
    var currentPage by remember { mutableIntStateOf(0) }
    var userFolders by remember { mutableStateOf(readUserFolders(ctx)) }
    var folderEditor by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showPageManager by remember { mutableStateOf(false) }
    var homeCapacity by remember { mutableIntStateOf(28) }
    var drawer by remember { mutableStateOf(false) }
                var editingHome by remember { mutableStateOf(false) }
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
            val defaultDock = listOfNotNull(sys.getOrNull(0), msgs, chrome, sys.getOrNull(2)).map { it.pkg }
            val savedDock = ctx.getSharedPreferences("nexo_home", 0).getString("dock_packages", "")
                ?.takeIf { it.isNotBlank() }?.split("|")
            val selectedDock = (savedDock ?: defaultDock).take(4).toMutableList()
            while (selectedDock.size < 4) selectedDock.add("-")
            dockPackages = selectedDock
            dock = selectedDock.mapNotNull { pkg -> list.find { it.pkg == pkg } }
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

    val homeSlots: List<String?> = if (homePackages.isEmpty() && currentPage == 0) {
        priority.map { it.pkg }
    } else {
        homePackages.map { it.takeIf { value -> value != "-" } }
    }

    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("es")))
        .replaceFirstChar { it.uppercase() }
    val w = weather

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x1A000000), Color(0x70000000))))
            .then(
                if (!drawer) {
                    Modifier.pointerInput(drawer) {
                        var startY = 0f
                        detectVerticalDragGestures(
                            onDragStart = { startY = it.y },
                            onVerticalDrag = { change, dy ->
                                if (startY >= size.height * 0.72f && dy < -25f) {
                                    drawer = true
                                    change.consume()
                                }
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 80.sp,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 4.dp).combinedClickable(onClick = {}, onLongClick = { editingHome = true }))
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
            val pageWidgetIds = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == currentPage }.orEmpty()
            val widgetRows = pageWidgetIds.sumOf { id ->
                val height = launcherActivity?.widgetHeightDp(id) ?: 132
                ((height + 99) / 100).coerceAtLeast(1)
            }.coerceAtMost(7)
            val widgetCellOffset = widgetRows * 4
            homeCapacity = (28 - widgetCellOffset).coerceIn(0, 28)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(currentPage, homePages.size, editingHome) {
                        if (!editingHome) {
                            var accumulated = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, amount -> change.consume(); accumulated += amount },
                                onDragEnd = {
                                    if (accumulated < -55 && currentPage < homePages.lastIndex) {
                                        currentPage++; homePackages = homePages.getOrNull(currentPage).orEmpty()
                                    } else if (accumulated > 55 && currentPage > 0) {
                                        currentPage--; homePackages = homePages.getOrNull(currentPage).orEmpty()
                                    }
                                }
                            )
                        }
                    },
                userScrollEnabled = true,
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = pageWidgetIds.size + homeCapacity,
                    key = { index -> if (index < pageWidgetIds.size) "widget-${pageWidgetIds[index]}" else "home-cell-${index - pageWidgetIds.size}" },
                    span = { index -> if (index < pageWidgetIds.size) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                ) { itemIndex ->
                    if (itemIndex < pageWidgetIds.size) {
                        val widgetId = pageWidgetIds[itemIndex]
                        var showWidgetPageMenu by remember(widgetId) { mutableStateOf(false) }
                        val widgetRowsHeight = (((launcherActivity?.widgetHeightDp(widgetId) ?: 132) + 99) / 100).coerceAtLeast(1) * 100
                        Box(Modifier.fillMaxWidth().height(widgetRowsHeight.dp)) {
                            Surface(color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxSize()) {
                                AndroidView(factory = { activity -> launcherActivity?.createWidgetHostView(widgetId) ?: android.widget.FrameLayout(activity) }, modifier = Modifier.fillMaxSize())
                            }
                            if (editingHome) Row(Modifier.align(Alignment.TopEnd).background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(20.dp))) {
                                TextButton(onClick = { launcherActivity?.reorderWidget(widgetId, -1) }) { Text("↑") }
                                Box {
                                    TextButton(onClick = { showWidgetPageMenu = true }) { Text("Página") }
                                    DropdownMenu(expanded = showWidgetPageMenu, onDismissRequest = { showWidgetPageMenu = false }) {
                                        homePages.indices.forEach { destination -> DropdownMenuItem(
                                            text = { Text("Página ${destination + 1}") },
                                            onClick = { launcherActivity?.moveWidgetToPage(widgetId, destination, homePages.size); showWidgetPageMenu = false }
                                        ) }
                                    }
                                }
                                TextButton(enabled = launcherActivity?.canResizeWidget(widgetId) == true, onClick = { launcherActivity?.resizeWidget(widgetId, -90) }) { Text("−") }
                                TextButton(onClick = { launcherActivity?.removeWidget(widgetId) }) { Text("Quitar", color = Color(0xFFB3261E)) }
                                TextButton(enabled = launcherActivity?.canResizeWidget(widgetId) == true, onClick = { launcherActivity?.resizeWidget(widgetId, 90) }) { Text("＋") }
                                TextButton(onClick = { launcherActivity?.reorderWidget(widgetId, 1) }) { Text("↓") }
                            }
                        }
                    } else {
                    val index = itemIndex - pageWidgetIds.size
                    val logicalIndex = index + widgetCellOffset
                    val slot = homeSlots.getOrNull(logicalIndex)
                    val folderName = slot?.takeIf { it.startsWith("folder:") }?.removePrefix("folder:")
                    val folderApps = folderName?.let { name -> userFolders.find { it.first == name }?.second.orEmpty().mapNotNull { pkg -> apps.find { it.pkg == pkg } } }.orEmpty()
                    val app = slot?.takeUnless { it.startsWith("folder:") }?.let { pkg -> apps.find { it.pkg == pkg } }
                    HomeAppCell(
                        app = app,
                        folderName = folderName,
                        folderApps = folderApps,
                        onFolderClick = { if (folderName != null) openFolder = "user:$folderName" },
                        onFolderEdit = { if (folderName != null) userFolders.find { it.first == folderName }?.let { folderEditor = it } },
                        index = index,
                        editingHome = editingHome,
                        onLongPress = { editingHome = true },
                        onRemove = { pkg ->
                            val slots = (if (homePackages.isEmpty() && currentPage == 0) priority.map { it.pkg }.toMutableList() else homePackages.toMutableList())
                            val idx = slots.indexOf(pkg)
                            if (idx >= 0) slots[idx] = "-"
                            if (pkg.startsWith("folder:")) {
                                val removedName = pkg.removePrefix("folder:")
                                userFolders = userFolders.filterNot { it.first == removedName }
                                writeUserFolders(ctx, userFolders)
                            }
                            val updatedPages = homePages.toMutableList().apply {
                                while (size <= currentPage) add(emptyList())
                                this[currentPage] = slots
                            }
                            ctx.getSharedPreferences("nexo_home", 0).edit()
                                .putString("packages", updatedPages.firstOrNull().orEmpty().joinToString("|"))
                                .putString("pages", updatedPages.joinToString("~") { it.joinToString("|") }).apply()
                            homePages = updatedPages
                            homePackages = slots
                        },
                        onDrop = { payload, targetIndex ->
                            val fromDock = payload.startsWith("dock:")
                            val draggedPkg = payload.removePrefix("home:").removePrefix("dock:")
                            val logicalTargetIndex = targetIndex + widgetCellOffset
                            val draggedFolderName = draggedPkg.removePrefix("folder:").takeIf { draggedPkg.startsWith("folder:") }
                            val draggedFolder = draggedFolderName?.let { name -> userFolders.find { it.first == name } }
                            val draggedAllowed = if (draggedFolderName != null) draggedFolder != null else apps.any { it.pkg == draggedPkg }
                            if (draggedAllowed && targetIndex in 0 until homeCapacity) {
                                val pages = homePages.map { it.toMutableList() }.toMutableList()
                                if (pages.isNotEmpty() && pages[0].isEmpty()) {
                                    pages[0].addAll(priority.map { it.pkg })
                                }
                                while (pages.size <= currentPage) pages.add(mutableListOf())
                                val dest = pages[currentPage]
                                while (dest.size <= logicalTargetIndex) dest.add("-")

                                val sourcePage = pages.indexOfFirst { it.contains(draggedPkg) }
                                var canApplyDrop = true
                                val target = dest[logicalTargetIndex]
                                if (target == draggedPkg) {
                                    canApplyDrop = false
                                } else if (target?.startsWith("folder:") == true) {
                                    val folderName = target.removePrefix("folder:")
                                    val targetFolder = userFolders.find { it.first == folderName }
                                    if (targetFolder != null) {
                                        val addPackages = draggedFolder?.second ?: listOf(draggedPkg)
                                        val updatedFolders = userFolders.mapNotNull { folder ->
                                            when (folder.first) {
                                                folderName -> folder.first to (folder.second + addPackages).distinct()
                                                draggedFolderName -> null
                                                else -> folder.second.filterNot { it in addPackages }.takeIf { it.isNotEmpty() }?.let { remaining -> folder.first to remaining }
                                            }
                                        }
                                        val removedNames = userFolders.map { it.first }.toSet() - updatedFolders.map { it.first }.toSet()
                                        pages.forEach { page -> page.indices.forEach { index -> if (page[index].startsWith("folder:") && page[index].removePrefix("folder:") in removedNames) page[index] = "-" } }
                                        userFolders = updatedFolders; writeUserFolders(ctx, updatedFolders)
                                        if (sourcePage >= 0) pages[sourcePage][pages[sourcePage].indexOf(draggedPkg)] = "-"
                                    }
                                } else if (target != null && target != "-") {
                                    val existingApp = apps.firstOrNull { it.pkg == target }
                                    val draggedApp = apps.firstOrNull { it.pkg == draggedPkg }
                                    if (draggedFolder != null && existingApp != null) {
                                        val updatedFolders = userFolders.map { folder ->
                                            if (folder.first == draggedFolder.first) folder.first to (folder.second + existingApp.pkg).distinct() else folder
                                        }
                                        userFolders = updatedFolders; writeUserFolders(ctx, updatedFolders)
                                        dest[logicalTargetIndex] = draggedPkg
                                        if (sourcePage >= 0) pages[sourcePage][pages[sourcePage].indexOf(draggedPkg)] = "-"
                                    } else if (existingApp != null && draggedApp != null) {
                                        var folderName = "Carpeta"
                                        var suffix = 1
                                        while (userFolders.any { it.first == folderName }) { folderName = "Carpeta $suffix"; suffix++ }
                                        val updatedFolders = userFolders + (folderName to listOf(existingApp.pkg, draggedApp.pkg).distinct())
                                        userFolders = updatedFolders; writeUserFolders(ctx, updatedFolders)
                                        dest[logicalTargetIndex] = "folder:$folderName"
                                        if (sourcePage >= 0) pages[sourcePage][pages[sourcePage].indexOf(draggedPkg)] = "-"
                                    }
                                } else if (sourcePage == currentPage) {
                                    val sourceIndex = dest.indexOf(draggedPkg)
                                    if (sourceIndex >= 0) {
                                        val displaced = dest[logicalTargetIndex]
                                        dest[logicalTargetIndex] = draggedPkg
                                        dest[sourceIndex] = displaced ?: "-"
                                    }
                                } else if (sourcePage >= 0) {
                                    val sourceSlots = pages[sourcePage]
                                    val sourceIndex = sourceSlots.indexOf(draggedPkg)
                                    val displaced = dest[logicalTargetIndex]
                                    sourceSlots[sourceIndex] = displaced ?: "-"
                                    dest[logicalTargetIndex] = draggedPkg
                                } else {
                                    val emptyIndex = dest.indexOfFirst { it == "-" }
                                    if (emptyIndex < 0) {
                                        Toast.makeText(ctx, "No hay espacio libre en esta página", Toast.LENGTH_SHORT).show()
                                        canApplyDrop = false
                                    } else {
                                        for (i in emptyIndex downTo logicalTargetIndex + 1) dest[i] = dest[i - 1]
                                        dest[logicalTargetIndex] = draggedPkg
                                    }
                                }

                                if (canApplyDrop) {
                                    val updatedPages: List<List<String>> = pages.map { it.toList() }
                                    ctx.getSharedPreferences("nexo_home", 0).edit()
                                        .putString("packages", updatedPages.firstOrNull().orEmpty().joinToString("|"))
                                        .putString("pages", updatedPages.joinToString("~") { it.joinToString("|") })
                                        .apply()
                                    homePages = updatedPages
                                    homePackages = dest.toList()
                                    if (fromDock) {
                                        dockPackages = dockPackages.map { if (it == draggedPkg) "-" else it }
                                        dock = dockPackages.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                                        ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", dockPackages.joinToString("|")).apply()
                                    }
                                    drawer = false
                                }
                            }
                        }
                    )
                    }
                }
            }

            if (homePages.size > 1 || editingHome) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (editingHome) TextButton(onClick = { launcherActivity?.launchWidgetPicker(currentPage) }) {
                        Text("＋ Widget", color = Color.White)
                    }
                    if (editingHome) TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
                            .onFailure { Toast.makeText(ctx, "No se pudo abrir la selección de fondo", Toast.LENGTH_SHORT).show() }
                    }) { Text("Fondo", color = Color.White) }
                    if (editingHome) TextButton(onClick = {
                        val expanded = (homePages + listOf(emptyList())).toMutableList()
                        ctx.getSharedPreferences("nexo_home", 0).edit()
                            .putString("pages", expanded.joinToString("~") { it.joinToString("|") }).apply()
                        homePages = expanded
                        currentPage = expanded.lastIndex
                        homePackages = emptyList()
                    }) { Text("＋ Página", color = Color.White) }
                    homePages.indices.forEach { page ->
                        HomePageIndicator(page = page, selected = currentPage == page,
                            onSelect = { currentPage = page; homePackages = homePages.getOrNull(page).orEmpty() },
                            onDrop = { payload ->
                                val fromDock = payload.startsWith("dock:")
                                val token = payload.removePrefix("home:").removePrefix("dock:")
                                val isFolder = token.startsWith("folder:") && userFolders.any { it.first == token.removePrefix("folder:") }
                                val valid = isFolder || apps.any { it.pkg == token }
                                if (valid && page in homePages.indices) {
                                    val pages = homePages.map { it.toMutableList() }.toMutableList()
                                    if (pages.isNotEmpty() && pages[0].isEmpty()) pages[0].addAll(priority.map { it.pkg })
                                    val sourcePage = pages.indexOfFirst { token in it }
                                    val dest = pages[page]
                                    val widgetCells = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == page }
                                        ?.sumOf { id -> ((launcherActivity.widgetHeightDp(id) + 99) / 100).coerceAtLeast(1) * 4 } ?: 0
                                    val start = widgetCells.coerceAtMost(28)
                                    val end = 28
                                    if (token in dest) {
                                        currentPage = page; homePackages = dest.toList()
                                    } else {
                                        while (dest.size < end) dest.add("-")
                                        val free = (start until end).firstOrNull { dest[it] == "-" }
                                        if (free == null) Toast.makeText(ctx, "No hay una celda libre en esa página", Toast.LENGTH_SHORT).show()
                                        else {
                                            if (sourcePage >= 0) pages[sourcePage][pages[sourcePage].indexOf(token)] = "-"
                                            dest[free] = token
                                            val saved = pages.map { it.toList() }
                                            ctx.getSharedPreferences("nexo_home", 0).edit()
                                                .putString("packages", saved.firstOrNull().orEmpty().joinToString("|"))
                                                .putString("pages", saved.joinToString("~") { it.joinToString("|") }).apply()
                                            homePages = saved; currentPage = page; homePackages = dest.toList()
                                            if (fromDock) {
                                                dockPackages = dockPackages.map { if (it == token) "-" else it }
                                                dock = dockPackages.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                                                ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", dockPackages.joinToString("|")).apply()
                                            }
                                        }
                                    }
                                }
                            })
                    }
                    if (editingHome) TextButton(onClick = { showPageManager = true }) { Text("Administrar", color = Color.White) }
                    if (editingHome) TextButton(onClick = { editingHome = false }) { Text("Listo", color = Color.White) }
                }
            }
            if (editingHome) TextButton(onClick = { showNewFolder = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("＋ Carpeta", color = Color.White)
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
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    repeat(4) { dockIndex ->
                        val dockApp = dockPackages.getOrNull(dockIndex)?.let { pkg -> apps.find { it.pkg == pkg } }
                        DockCell(modifier = Modifier.weight(1f).fillMaxHeight(), app = dockApp, index = dockIndex, editing = editingHome,
                            onLongPress = { editingHome = true },
                            onRemove = {
                                val updated = dockPackages.toMutableList().apply { while (size < 4) add("-"); this[dockIndex] = "-" }
                                dockPackages = updated
                                ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", updated.joinToString("|")).apply()
                                dock = updated.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                            },
                            onDrop = { payload ->
                                val fromHome = payload.startsWith("home:")
                                val fromDock = payload.startsWith("dock:")
                                val pkg = payload.removePrefix("home:").removePrefix("dock:")
                                if (apps.any { it.pkg == pkg }) {
                                    val updated = dockPackages.toMutableList().apply { while (size < 4) add("-") }
                                    val sourceIndex = updated.indexOf(pkg)
                                    if (sourceIndex >= 0 && sourceIndex != dockIndex) {
                                        val displaced = updated[dockIndex]
                                        updated[dockIndex] = pkg
                                        updated[sourceIndex] = displaced
                                    } else updated[dockIndex] = pkg
                                    dockPackages = updated
                                    ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", updated.joinToString("|")).apply()
                                    dock = updated.mapNotNull { item -> apps.find { it.pkg == item } }
                                    if (fromHome && !fromDock) {
                                        val pages = homePages.map { it.toMutableList() }.toMutableList()
                                        pages.forEach { page -> val index = page.indexOf(pkg); if (index >= 0) page[index] = "-" }
                                        homePages = pages.map { it.toList() }
                                        homePackages = homePages.getOrNull(currentPage).orEmpty()
                                        ctx.getSharedPreferences("nexo_home", 0).edit().putString("pages", homePages.joinToString("~") { it.joinToString("|") }).putString("packages", homePages.firstOrNull().orEmpty().joinToString("|")).apply()
                                    }
                                }
                            })
                    }
                }
            }

                        Spacer(Modifier.height(10.dp))
        }
        AnimatedVisibility(drawer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Drawer(apps, editingHome) { drawer = false }
        }
        openFolder?.let { name ->
            val contents = if (name.startsWith("user:")) {
                val key = name.removePrefix("user:")
                userFolders.find { it.first == key }?.second.orEmpty().mapNotNull { pkg -> apps.find { it.pkg == pkg } }
            } else emptyList()
            val userFolderName = name.removePrefix("user:").takeIf { name.startsWith("user:") }
            FolderPanel(name.removePrefix("user:"), contents, close = { openFolder = null }, onEdit = {
                userFolderName?.let { folderName -> userFolders.find { it.first == folderName }?.let { folderEditor = it } }
                openFolder = null
            })
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
            onDismissRequest = { editCard = false }, title = { Text("Tu tarjeta") },
            text = { Column { OutlinedTextField(t, { t = it.take(80) }, label = { Text("Frase") }, modifier = Modifier.fillMaxWidth()); TextButton({ picker.launch(arrayOf("image/*")) }) { Text("Elegir foto de la galería") } } },
            confirmButton = { TextButton({ val v = t.trim().ifBlank { DEFAULT_QUOTE }; saveQuote(ctx, v); quote = v; editCard = false }) { Text("Guardar") } },
            dismissButton = { TextButton({ editCard = false }) { Text("Cancelar") } },
        )
    }
    if (showNewFolder) UserFolderDialog(apps, null, onDismiss = { showNewFolder = false }, onSave = { name, packages ->
        val pages = homePages.map { it.toMutableList() }.toMutableList()
        while (pages.size <= currentPage) pages.add(mutableListOf())
        var existingFolderSlot = -1
        pages.forEachIndexed { pageIndex, savedPage -> savedPage.forEachIndexed { slotIndex, token ->
            if (token == "folder:$name") {
                if (pageIndex == currentPage && existingFolderSlot < 0) existingFolderSlot = slotIndex
                savedPage[slotIndex] = "-"
            }
        } }
        val page = pages[currentPage]
        if (page.isEmpty() && currentPage == 0) page.addAll(priority.map { it.pkg })
        var reusableIndex = -1
        pages.forEachIndexed { pageIndex, savedPage ->
            savedPage.forEachIndexed { slotIndex, token ->
                if (token in packages) {
                    if (pageIndex == currentPage && reusableIndex < 0) reusableIndex = slotIndex
                    savedPage[slotIndex] = "-"
                }
            }
        }
        val targetPage = pages[currentPage]
        val pageWidgetArea = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == currentPage }
            ?.sumOf { id -> ((launcherActivity.widgetHeightDp(id) + 99) / 100).coerceAtLeast(1) * 4 } ?: 0
        val pageCellLimit = (28 - pageWidgetArea).coerceAtLeast(0)
        val pageOffset = pageWidgetArea
        val visibleRange = pageOffset until (pageOffset + pageCellLimit).coerceAtMost(28)
        val emptyRelative = targetPage.drop(pageOffset).take(pageCellLimit).indexOfFirst { it == "-" }
        val emptySlot = if (emptyRelative >= 0) pageOffset + emptyRelative else -1
        val folderSlot = when {
            existingFolderSlot in visibleRange -> existingFolderSlot
            reusableIndex in visibleRange -> reusableIndex
            emptySlot >= 0 -> emptySlot
            targetPage.size < pageOffset + pageCellLimit -> (pageOffset + pageCellLimit).coerceAtMost(28).also { targetPage.add("-") }
            else -> -1
        }
        if (folderSlot < 0) {
            Toast.makeText(ctx, "No hay una celda libre para la carpeta", Toast.LENGTH_SHORT).show()
        } else {
            targetPage[folderSlot] = "folder:$name"
            val remainingFolders = userFolders.filterNot { it.first == name }.map { folder ->
                folder.first to folder.second.filterNot { it in packages }
            }.filterNot { it.second.isEmpty() }
            val updatedFolders = remainingFolders + (name to packages.distinct())
            val deletedNames = userFolders.map { it.first }.toSet() - updatedFolders.map { it.first }.toSet()
            pages.forEach { page -> page.indices.forEach { index ->
                if (page[index].startsWith("folder:") && page[index].removePrefix("folder:") in deletedNames) page[index] = "-"
            } }
            userFolders = updatedFolders; writeUserFolders(ctx, updatedFolders)
            homePages = pages.map { it.toList() }; homePackages = homePages.getOrNull(currentPage).orEmpty()
            ctx.getSharedPreferences("nexo_home", 0).edit().putString("pages", homePages.joinToString("~") { it.joinToString("|") }).putString("packages", homePages.firstOrNull().orEmpty().joinToString("|")).apply()
            showNewFolder = false
        }
    })
    folderEditor?.let { existing -> UserFolderDialog(apps, existing, onDismiss = { folderEditor = null }, onSave = { name, packages ->
        val baseFolders = userFolders.filterNot { it.first == existing.first || it.first == name }.map { folder ->
            folder.first to folder.second.filterNot { it in packages }
        }.filterNot { it.second.isEmpty() }
        val updated = baseFolders + (name to packages.distinct())
        val removedNames = userFolders.map { it.first }.toSet() - updated.map { it.first }.toSet()
        val renamedPages = homePages.map { page -> page.map { token ->
            when {
                token == "folder:${existing.first}" -> "folder:$name"
                token.startsWith("folder:") && token.removePrefix("folder:") in removedNames -> "-"
                else -> token
            }
        } }
        userFolders = updated; writeUserFolders(ctx, updated)
        homePages = renamedPages; homePackages = renamedPages.getOrNull(currentPage).orEmpty()
        ctx.getSharedPreferences("nexo_home", 0).edit().putString("pages", renamedPages.joinToString("~") { it.joinToString("|") }).putString("packages", renamedPages.firstOrNull().orEmpty().joinToString("|")).apply()
        folderEditor = null
    }, onDelete = {
        val updated = userFolders.filterNot { it.first == existing.first }
        userFolders = updated; writeUserFolders(ctx, updated)
        val changedPages = homePages.map { page -> page.map { if (it == "folder:${existing.first}") "-" else it } }
        homePages = changedPages; homePackages = changedPages.getOrNull(currentPage).orEmpty()
        ctx.getSharedPreferences("nexo_home", 0).edit().putString("pages", changedPages.joinToString("~") { it.joinToString("|") }).putString("packages", changedPages.firstOrNull().orEmpty().joinToString("|")).apply()
        folderEditor = null
    }) }
    if (showPageManager) PageManagerDialog(homePages, currentPage, launcherActivity?.hostedWidgetPages?.values?.toSet().orEmpty(),
        onDismiss = { showPageManager = false },
        onSelect = { currentPage = it; homePackages = homePages.getOrNull(it).orEmpty(); showPageManager = false },
        onMove = { from, to ->
            if (from in homePages.indices && to in homePages.indices) {
                val oldCurrent = currentPage
                val mutable = homePages.toMutableList(); val item = mutable.removeAt(from); mutable.add(to, item)
                currentPage = when {
                    oldCurrent == from -> to
                    from < to && oldCurrent in (from + 1)..to -> oldCurrent - 1
                    from > to && oldCurrent in to until from -> oldCurrent + 1
                    else -> oldCurrent
                }
                homePages = mutable; homePackages = mutable[currentPage]
                launcherActivity?.moveWidgetPages(from, to)
                ctx.getSharedPreferences("nexo_home", 0).edit().putString("pages", mutable.joinToString("~") { it.joinToString("|") }).putString("packages", mutable.firstOrNull().orEmpty().joinToString("|")).apply()
            }
        },
        onDelete = { index ->
            if (index in homePages.indices && homePages[index].all { it == "-" } && (launcherActivity?.hostedWidgetPages?.values?.none { it == index } ?: true)) {
                val mutable = homePages.toMutableList().also { it.removeAt(index) }.ifEmpty { mutableListOf(emptyList()) }
                homePages = mutable; currentPage = currentPage.coerceAtMost(mutable.lastIndex); homePackages = mutable[currentPage]
                launcherActivity?.deleteWidgetPage(index)
                ctx.getSharedPreferences("nexo_home", 0).edit().putString("pages", mutable.joinToString("~") { it.joinToString("|") }).putString("packages", mutable.firstOrNull().orEmpty().joinToString("|")).apply()
            }
        })
}
@Composable
private fun UserFolderDialog(
    apps: List<AppItem>, existing: Pair<String, List<String>>?, onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit, onDelete: (() -> Unit)? = null
) {
    var name by remember(existing) { mutableStateOf(existing?.first.orEmpty()) }
    val selected = remember(existing) { mutableStateListOf<String>().apply { addAll(existing?.second.orEmpty()) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Nueva carpeta" else "Editar carpeta") },
        text = { Column {
            OutlinedTextField(name, { value -> name = value.filter { it != '|' && it != '~' }.take(24) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                apps.sortedBy { it.label.lowercase() }.forEach { app ->
                    Row(Modifier.fillMaxWidth().clickable { if (app.pkg in selected) selected.remove(app.pkg) else selected.add(app.pkg) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(app.pkg in selected, onCheckedChange = null)
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = { onSave(name.trim(), selected.toList()) }) { Text("Guardar") } },
        dismissButton = { Row(verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Borrar", color = Color(0xFFB3261E)) }
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        } })
}

@Composable
private fun PageManagerDialog(
    pages: List<List<String>>, current: Int, widgetPages: Set<Int>, onDismiss: () -> Unit, onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit, onDelete: (Int) -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Páginas de inicio") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            pages.forEachIndexed { index, page ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSelect(index) }, modifier = Modifier.weight(1f)) {
                        Text("Página ${index + 1}${if (index == current) " · actual" else ""} · ${page.count { it != "-" }} apps", maxLines = 1)
                    }
                    TextButton(enabled = index > 0, onClick = { onMove(index, index - 1) }) { Text("↑") }
                    TextButton(enabled = index < pages.lastIndex, onClick = { onMove(index, index + 1) }) { Text("↓") }
                    TextButton(enabled = index > 0 && page.all { it == "-" } && index !in widgetPages, onClick = { onDelete(index) }) { Text("×") }
                }
            }
            Text("Solo se pueden borrar páginas vacías.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } })
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
fun FolderIcon(name: String, apps: List<AppItem>, size: Dp, onClick: () -> Unit, dragPayload: String? = null) {
    val dragView = LocalView.current
    val density = LocalDensity.current.density
    Column(Modifier.width(size + 12.dp).combinedClickable(onClick = onClick, onLongClick = {
        if (dragPayload != null && apps.isNotEmpty()) {
            val clip = ClipData.newPlainText("nexo-folder", dragPayload)
            dragView.startDragAndDrop(clip, AppIconDragShadowBuilder(dragView, apps.first().icon.asAndroidBitmap(), (size.value * density).toInt().coerceAtLeast(48)), dragPayload, View.DRAG_FLAG_GLOBAL)
        }
    }), horizontalAlignment = Alignment.CenterHorizontally) {
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
fun FolderPanel(name: String, apps: List<AppItem>, close: () -> Unit, onEdit: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99201810)).pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp).clip(RoundedCornerShape(32.dp)).background(Cream.copy(alpha = 0.95f))
                .pointerInput(Unit) { detectTapGestures { } }.padding(22.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Brown, fontSize = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (onEdit != null) TextButton(onClick = onEdit) { Text("Editar") }
            }
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
private class AppIconDragShadowBuilder(
    view: View,
    private val bitmap: android.graphics.Bitmap,
    private val sizePx: Int
) : View.DragShadowBuilder(view) {
    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(sizePx, sizePx)
        outShadowTouchPoint.set(sizePx / 2, sizePx / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, Rect(0, 0, sizePx, sizePx), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}

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
    val dragView = LocalView.current
    val density = LocalDensity.current.density
    val iconModifier = Modifier
        .width(size + 12.dp)
        .combinedClickable(
            onClick = {
                onLaunch()
                runCatching { ctx.startActivity(app.intent) }
            },
            onLongClick = {
                onLongPress?.invoke()
                if (dragPayload != null) {
                    val clip = ClipData.newPlainText("nexo-app", dragPayload)
                    dragView.startDragAndDrop(
                        clip,
                        AppIconDragShadowBuilder(dragView, app.icon.asAndroidBitmap(), (size.value * density).toInt().coerceAtLeast(48)),
                        dragPayload,
                        View.DRAG_FLAG_GLOBAL
                    )
                }
            }
        )
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
private fun HomePageIndicator(page: Int, selected: Boolean, onSelect: () -> Unit, onDrop: (String) -> Unit) {
    val target = remember(page, onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                if (clip.itemCount == 0) return false
                val payload = clip.getItemAt(0).text?.toString() ?: return false
                onDrop(payload)
                return true
            }
        }
    }
    Box(Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription
            ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true }, target = target
    )) {
        TextButton(onClick = onSelect) {
            Text(if (selected) "●" else "○", color = Color.White, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DockCell(
    modifier: Modifier, app: AppItem?, index: Int, editing: Boolean, onLongPress: () -> Unit,
    onRemove: () -> Unit, onDrop: (String) -> Unit
) {
    val target = remember(index, onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                if (clip.itemCount == 0) return false
                val pkg = clip.getItemAt(0).text?.toString() ?: return false
                onDrop(pkg)
                return true
            }
        }
    }
    Box(modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription
            ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true }, target = target
    ), contentAlignment = Alignment.Center) {
        if (app != null) AppIcon(app, 52.dp, Color.White, showLabel = false,
            onLongPress = onLongPress, dragPayload = "dock:${app.pkg}")
        if (editing && app != null) Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clickable { onRemove() }) {
            Box(contentAlignment = Alignment.Center) { Text("−", color = Color.White, fontSize = 15.sp, lineHeight = 15.sp) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeAppCell(
    app: AppItem?,
    folderName: String? = null,
    folderApps: List<AppItem> = emptyList(),
    onFolderClick: () -> Unit = {},
    onFolderEdit: () -> Unit = {},
    index: Int,
    editingHome: Boolean,
    onLongPress: () -> Unit,
    onRemove: (String) -> Unit,
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
            .then(
                if (app == null) {
                    Modifier.pointerInput(index) {
                        detectTapGestures(onLongPress = { onLongPress() })
                    }
                } else Modifier
            )
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
            AppIcon(app, 60.dp, Color.White, onLongPress = onLongPress, dragPayload = "home:${app.pkg}")
            if (editingHome) {
                Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clickable { onRemove(app.pkg) }) {
                    Box(contentAlignment = Alignment.Center) { Text("−", color = Color.White, fontSize = 16.sp, lineHeight = 16.sp) }
                }
            }
        } else if (folderName != null) {
            FolderIcon(folderName, folderApps, 56.dp, onFolderClick, dragPayload = "folder:$folderName")
            if (editingHome) {
                Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clickable { onRemove("folder:$folderName") }) {
                    Box(contentAlignment = Alignment.Center) { Text("−", color = Color.White, fontSize = 16.sp, lineHeight = 16.sp) }
                }
                TextButton(onClick = onFolderEdit, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Text("Editar", color = Color.White, fontSize = 9.sp)
                }
            }
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
        Modifier.fillMaxWidth(),
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
