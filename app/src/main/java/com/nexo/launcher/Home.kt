package com.nexo.launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.ShortcutInfo
import android.os.Process
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch
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
    // Dock shortcuts are intentional launch points, but are not repeated in the home grid.
    val defaultHomeApps = PRIORITY.mapNotNull { group ->
        group.firstNotNullOfOrNull { pkg -> apps.find { it.pkg == pkg } }
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
    var showHomeMoreActions by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var showLockAdminDialog by remember { mutableStateOf(false) }
    var showIconThemeDialog by remember { mutableStateOf(false) }
    var iconTheme by remember { mutableStateOf(savedIconTheme(ctx)) }
    var shortcutTarget by remember { mutableStateOf<AppItem?>(null) }
    var shortcutRows by remember { mutableStateOf<List<ShortcutInfo>>(emptyList()) }
    var shortcutAccessDenied by remember { mutableStateOf(false) }
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

    fun addAppToCurrentHome(app: AppItem) {
        val pages = homePages.map { it.toMutableList() }.toMutableList()
        while (pages.size <= currentPage) pages.add(mutableListOf())
        val page = pages[currentPage]
        if (currentPage == 0 && page.isEmpty()) {
            page.addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
        }
        val existingPage = pages.indexOfFirst { app.pkg in it }
        if (existingPage >= 0) {
            currentPage = existingPage
            homePackages = pages[existingPage]
            drawer = false
            Toast.makeText(ctx, "La aplicación ya está en la página ${existingPage + 1}", Toast.LENGTH_SHORT).show()
            return
        }
        val occupiedWidgetCells = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == currentPage }
            ?.sumOf { id -> ((launcherActivity.widgetHeightDp(id) + 99) / 100).coerceAtLeast(1) * 4 } ?: 0
        val firstAppCell = (8 + occupiedWidgetCells).coerceIn(0, 28)
        while (page.size < 28) page.add("-")
        val appEndCell = (28 - if (currentPage == 0) 8 else 0).coerceAtLeast(firstAppCell)
        val slot = (firstAppCell until appEndCell).firstOrNull { page[it] == "-" }
        if (slot == null) {
            Toast.makeText(ctx, "No hay una celda libre en esta página", Toast.LENGTH_LONG).show()
            return
        }
        page[slot] = app.pkg
        val saved = pages.map { it.toList() }
        homePages = saved
        homePackages = saved.getOrNull(currentPage).orEmpty()
        ctx.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", saved.joinToString("~") { it.joinToString("|") })
            .putString("packages", saved.firstOrNull().orEmpty().joinToString("|")).apply()
        drawer = false
    }

    fun showShortcutsFor(app: AppItem) {
        val launcherApps = ctx.getSystemService(LauncherApps::class.java)
        if (launcherApps == null || !launcherApps.hasShortcutHostPermission()) {
            shortcutTarget = app
            shortcutRows = emptyList()
            shortcutAccessDenied = true
            return
        }
        val shortcuts = runCatching {
            val query = LauncherApps.ShortcutQuery().setPackage(app.pkg).setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty()
                .sortedWith(compareBy<ShortcutInfo> { if (it.isDynamic) 1 else 0 }.thenBy { it.rank })
        }.getOrDefault(emptyList())
        shortcutTarget = app
        shortcutRows = shortcuts
        shortcutAccessDenied = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            savePhoto(ctx, uri.toString())
            photoTick++
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                val contents = LauncherBackup.export(ctx, launcherActivity).toByteArray(Charsets.UTF_8)
                val output = ctx.contentResolver.openOutputStream(uri) ?: error("No se pudo crear el archivo")
                output.use { it.write(contents) }
            }.onSuccess { Toast.makeText(ctx, "Respaldo exportado", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "No se pudo exportar el respaldo", Toast.LENGTH_LONG).show() }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = LauncherBackup.read(ctx, uri)
                LauncherBackup.parse(text)
                pendingImportText = text
            }.onFailure { Toast.makeText(ctx, it.message ?: "El archivo no es un respaldo válido", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(iconTheme) {
        val requestedTheme = iconTheme
        val loaded = withContext(Dispatchers.IO) {
            val list = loadApps(ctx, requestedTheme)
            val sys = loadSystemDock(ctx, requestedTheme)
            val msgs = list.firstOrNull { it.pkg in MESSAGES } ?: sys.getOrNull(1)
            val chrome = list.find { it.pkg == "com.android.chrome" }
            val defaultDock = listOfNotNull(sys.getOrNull(0), msgs, chrome, sys.getOrNull(2)).map { it.pkg }
            val savedDock = ctx.getSharedPreferences("nexo_home", 0).getString("dock_packages", "")
                ?.takeIf { it.isNotBlank() }?.split("|")
            val selectedDock = (savedDock ?: defaultDock).take(4).toMutableList()
            while (selectedDock.size < 4) selectedDock.add("-")
            list to selectedDock
        }
        if (iconTheme == requestedTheme) {
            apps = loaded.first
            dockPackages = loaded.second
            dock = loaded.second.mapNotNull { pkg -> loaded.first.find { it.pkg == pkg } }
        }
    }
    val packageRefreshScope = rememberCoroutineScope()
    DisposableEffect(ctx) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val changedPackage = intent?.data?.schemeSpecificPart
                val removed = intent?.let { it.action == Intent.ACTION_PACKAGE_REMOVED && !it.getBooleanExtra(Intent.EXTRA_REPLACING, false) } == true
                val requestedTheme = savedIconTheme(ctx)
                packageRefreshScope.launch {
                    val latest = withContext(Dispatchers.IO) { loadApps(ctx, requestedTheme) }
                    if (savedIconTheme(ctx) != requestedTheme) return@launch
                    apps = latest
                    dock = dockPackages.mapNotNull { pkg -> latest.find { it.pkg == pkg } }
                    if (removed && changedPackage != null) {
                        val previousFolders = userFolders
                        userFolders = userFolders.map { it.first to it.second.filterNot { pkg -> pkg == changedPackage } }
                            .filterNot { it.second.isEmpty() }
                        val removedFolders = previousFolders.map { it.first }.toSet() - userFolders.map { it.first }.toSet()
                        val pages = homePages.map { page -> page.map { token ->
                            if (token == changedPackage || (token.startsWith("folder:") && token.removePrefix("folder:") in removedFolders)) "-" else token
                        } }
                        homePages = pages
                        homePackages = pages.getOrNull(currentPage).orEmpty()
                        writeUserFolders(ctx, userFolders)
                        dockPackages = dockPackages.map { if (it == changedPackage) "-" else it }
                        dock = dockPackages.mapNotNull { pkg -> latest.find { it.pkg == pkg } }
                        ctx.getSharedPreferences("nexo_home", 0).edit()
                            .putString("pages", pages.joinToString("~") { it.joinToString("|") })
                            .putString("packages", pages.firstOrNull().orEmpty().joinToString("|"))
                            .putString("dock_packages", dockPackages.joinToString("|")).apply()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
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
    BackHandler(editingHome && !drawer && openFolder == null) { editingHome = false }

    val priority = if (homePackages.isEmpty() && currentPage == 0) {
        defaultHomeApps
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

    var pageSwipeX by remember { mutableFloatStateOf(0f) }
    val pageSwipeScope = rememberCoroutineScope()
    val pageSwipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x1A000000), Color(0x70000000))))
            .graphicsLayer { translationX = pageSwipeX }
            .pointerInput(homePages.size, editingHome, drawer) {
                if (!editingHome && !drawer && homePages.size > 1) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            pageSwipeX += amount
                        },
                        onDragCancel = {
                            val dragStart = pageSwipeX
                            pageSwipeScope.launch {
                                Animatable(dragStart).animateTo(0f, tween(180)) { pageSwipeX = value }
                            }
                        },
                        onDragEnd = {
                            val dragStart = pageSwipeX
                            val direction = when {
                                dragStart < -pageSwipeThresholdPx && currentPage < homePages.lastIndex -> 1
                                dragStart > pageSwipeThresholdPx && currentPage > 0 -> -1
                                else -> 0
                            }
                            val width = size.width.toFloat()
                            pageSwipeScope.launch {
                                val animation = Animatable(dragStart)
                                if (direction != 0) {
                                    animation.animateTo(if (direction > 0) -width else width, tween(180)) {
                                        pageSwipeX = value
                                    }
                                    val nextPage = currentPage + direction
                                    currentPage = nextPage
                                    homePackages = homePages.getOrNull(nextPage).orEmpty()
                                    animation.snapTo(if (direction > 0) width else -width)
                                    pageSwipeX = animation.value
                                }
                                animation.animateTo(0f, tween(180)) { pageSwipeX = value }
                            }
                        }
                    )
                }
            }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            val pageWidgetIds = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == currentPage }.orEmpty()
            val nativeWidgetRows = pageWidgetIds.sumOf { id ->
                val height = launcherActivity?.widgetHeightDp(id) ?: 132
                ((height + 99) / 100).coerceAtLeast(1)
            }.coerceAtMost(7)
            val quoteRows = if (currentPage == 0) 2 else 0
            val quoteItemCount = if (quoteRows > 0) 1 else 0
            val headerCellCount = 8
            val widgetCellOffset = nativeWidgetRows * 4
            val quoteCellCount = quoteRows * 4
            val firstAppCell = (headerCellCount + widgetCellOffset).coerceAtMost(28)
            val appEndCell = (28 - quoteCellCount).coerceAtLeast(firstAppCell)
            val pageCapacity = (appEndCell - firstAppCell).coerceAtLeast(0)
            val pageAppCellCount = (if (homePackages.isEmpty() && currentPage == 0) defaultHomeApps.size else homePackages.count { it != "-" }) + headerCellCount + quoteCellCount
            val widgetLayoutKey = launcherActivity?.hostedWidgetIds?.joinToString(",") { id ->
                "$id:${launcherActivity.hostedWidgetPages[id] ?: 0}:${launcherActivity.widgetHeightDp(id)}"
            }.orEmpty()
            LaunchedEffect(homePages, widgetLayoutKey, apps, dockPackages) {
                if (apps.isEmpty()) return@LaunchedEffect
                val widgetRowsByPage = launcherActivity?.hostedWidgetIds.orEmpty().groupBy { id ->
                    launcherActivity?.hostedWidgetPages?.get(id) ?: 0
                }.mapValues { (_, ids) -> ids.sumOf { id -> ((launcherActivity?.widgetHeightDp(id) ?: 132) + 99) / 100 } }
                val migrated = HomeGrid.normalize(
                    pages = homePages,
                    widgetRowsByPage = widgetRowsByPage,
                    defaultPackages = defaultHomeApps.map { it.pkg },
                )
                if (migrated != homePages) {
                    homePages = migrated
                    homePackages = migrated.getOrNull(currentPage).orEmpty()
                    ctx.getSharedPreferences("nexo_home", 0).edit()
                        .putString("packages", migrated.firstOrNull().orEmpty().joinToString("|"))
                        .putString("pages", migrated.joinToString("~") { it.joinToString("|") }).apply()
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gridGap = 8.dp
                val cellHeight = ((maxHeight - gridGap * 6) / 7).coerceAtLeast(1.dp)
                val quoteHeight = cellHeight * quoteRows
                val headerHeight = cellHeight * 2
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(gridGap)
                ) {
                    items(
                        count = 1 + pageWidgetIds.size + pageCapacity + quoteItemCount,
                        key = { index ->
                            when {
                                index == 0 -> "home-header-page-$currentPage"
                                index <= pageWidgetIds.size -> "widget-${pageWidgetIds[index - 1]}"
                                quoteItemCount > 0 && index == 1 + pageWidgetIds.size + pageCapacity -> "quote-widget-page-0"
                                else -> "home-cell-$currentPage-${index - 1 - pageWidgetIds.size}"
                            }
                        },
                        span = { index -> if (index == 0 || index in 1..pageWidgetIds.size || (quoteItemCount > 0 && index == 1 + pageWidgetIds.size + pageCapacity)) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                    ) { itemIndex ->
                    if (itemIndex == 0) {
                        HomeHeader(
                            now = now,
                            date = date,
                            weather = w,
                            city = city,
                            modifier = Modifier.fillMaxWidth().height(headerHeight),
                            onLongPress = { editingHome = true },
                            onWeatherClick = { editCity = true }
                        )
                    } else if (itemIndex <= pageWidgetIds.size) {
                        val widgetId = pageWidgetIds[itemIndex - 1]
                        val widgetHostView = remember(widgetId) { launcherActivity?.createWidgetHostView(widgetId) }
                        var showWidgetPageMenu by remember(widgetId) { mutableStateOf(false) }
                        val widgetRowsHeight = (((launcherActivity?.widgetHeightDp(widgetId) ?: 132) + 99) / 100).coerceAtLeast(1)
                        Box(Modifier.fillMaxWidth().height(cellHeight * widgetRowsHeight + gridGap * (widgetRowsHeight - 1))) {
                            Surface(color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxSize()) {
                                if (widgetHostView != null) {
                                    AndroidView(factory = { widgetHostView }, modifier = Modifier.fillMaxSize())
                                } else {
                                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("No se pudo mostrar este widget. Quitalo y volvé a agregarlo.", color = Color(0xFF4A433A), textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            if (editingHome) Row(Modifier.align(Alignment.TopEnd).background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(20.dp))) {
                                TextButton(onClick = { launcherActivity?.reorderWidget(widgetId, -1) }) { Text("↑") }
                                Box {
                                    TextButton(onClick = { showWidgetPageMenu = true }) { Text("Página") }
                                    DropdownMenu(expanded = showWidgetPageMenu, onDismissRequest = { showWidgetPageMenu = false }) {
                                        homePages.indices.forEach { destination -> DropdownMenuItem(
                                            text = { Text("Página ${destination + 1}") },
                                            onClick = { launcherActivity?.moveWidgetToPage(widgetId, destination, homePages.size, (if (homePages.getOrNull(destination).orEmpty().isEmpty() && destination == 0) defaultHomeApps.size else homePages.getOrNull(destination).orEmpty().count { it != "-" }) + 8 + if (destination == 0) 8 else 0); showWidgetPageMenu = false }
                                        ) }
                                    }
                                }
                                TextButton(enabled = launcherActivity?.canResizeWidget(widgetId) == true, onClick = { launcherActivity?.resizeWidget(widgetId, -90, pageAppCellCount) }) { Text("−") }
                                TextButton(onClick = { launcherActivity?.removeWidget(widgetId) }) { Text("Quitar", color = Color(0xFFB3261E)) }
                                TextButton(enabled = launcherActivity?.canResizeWidget(widgetId) == true, onClick = { launcherActivity?.resizeWidget(widgetId, 90, pageAppCellCount) }) { Text("＋") }
                                TextButton(onClick = { launcherActivity?.reorderWidget(widgetId, 1) }) { Text("↓") }
                            }
                        }
                    } else if (quoteItemCount > 0 && itemIndex == 1 + pageWidgetIds.size + pageCapacity) {
                        Box(Modifier.fillMaxWidth().height(quoteHeight), contentAlignment = Alignment.Center) {
                            QuoteCard(quote, photo, onClick = { editCard = true },
                                height = minOf(quoteHeight, 150.dp), modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                    val index = itemIndex - 1 - pageWidgetIds.size
                    val logicalIndex = index + firstAppCell
                    val slot = homeSlots.getOrNull(logicalIndex)
                    val folderName = slot?.takeIf { it.startsWith("folder:") }?.removePrefix("folder:")
                    val folderApps = folderName?.let { name -> userFolders.find { it.first == name }?.second.orEmpty().mapNotNull { pkg -> apps.find { it.pkg == pkg } } }.orEmpty()
                    val app = slot?.takeUnless { it.startsWith("folder:") }?.let { pkg -> apps.find { it.pkg == pkg } }
                    val iconSizing = homeIconSizing(cellHeight.value)
                    HomeAppCell(
                        app = app,
                        folderName = folderName,
                        folderApps = folderApps,
                        onFolderClick = { if (folderName != null) openFolder = "user:$folderName" },
                        onFolderEdit = { if (folderName != null) userFolders.find { it.first == folderName }?.let { folderEditor = it } },
                        index = logicalIndex,
                        editingHome = editingHome,
                        cellHeight = cellHeight,
                        iconSize = iconSizing.iconSizeDp.dp,
                        onLongPress = { editingHome = true },
                        onDoubleTapEmpty = {
                            if (launcherActivity?.isLockAdminActive() == true) launcherActivity.lockDeviceNow()
                            else showLockAdminDialog = true
                        },
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
                            val logicalTargetIndex = targetIndex
                            val draggedFolderName = draggedPkg.removePrefix("folder:").takeIf { draggedPkg.startsWith("folder:") }
                            val draggedFolder = draggedFolderName?.let { name -> userFolders.find { it.first == name } }
                            val draggedAllowed = if (draggedFolderName != null) draggedFolder != null else apps.any { it.pkg == draggedPkg }
                            if (draggedAllowed && targetIndex in firstAppCell until (firstAppCell + pageCapacity)) {
                                val pages = homePages.map { it.toMutableList() }.toMutableList()
                                if (pages.isNotEmpty() && pages[0].isEmpty()) {
                                    pages[0].addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
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
                                        if (sourcePage >= 0) pages[sourcePage][pages[sourcePage].indexOf(draggedPkg)] = "-"
                                        dest[logicalTargetIndex] = draggedPkg
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
                                    dest[logicalTargetIndex] = draggedPkg
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

            if (editingHome) {
                Surface(
                    color = Color(0xD922201F),
                    shape = RoundedCornerShape(26.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        HomeEditAction("Widget", onClick = { launcherActivity?.launchWidgetPicker(currentPage, pageAppCellCount) })
                        HomeEditAction("Fondo", onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
                                .onFailure { Toast.makeText(ctx, "No se pudo abrir la selección de fondo", Toast.LENGTH_SHORT).show() }
                        })
                        HomeEditAction("+ Página", onClick = {
                            val expanded = (homePages + listOf(emptyList())).toMutableList()
                            ctx.getSharedPreferences("nexo_home", 0).edit()
                                .putString("pages", expanded.joinToString("~") { it.joinToString("|") }).apply()
                            homePages = expanded
                            currentPage = expanded.lastIndex
                            homePackages = emptyList()
                        })
                        HomeEditAction("+ Carpeta", onClick = { showNewFolder = true })
                        Box {
                            HomeEditAction("Más", onClick = { showHomeMoreActions = true })
                            DropdownMenu(expanded = showHomeMoreActions, onDismissRequest = { showHomeMoreActions = false }) {
                                DropdownMenuItem(text = { Text("Administrar páginas") }, onClick = { showHomeMoreActions = false; showPageManager = true })
                                DropdownMenuItem(text = { Text("Opciones de inicio de Android") }, onClick = {
                                    showHomeMoreActions = false
                                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                                        .onFailure { Toast.makeText(ctx, "No se pudieron abrir las opciones de inicio de Android", Toast.LENGTH_LONG).show() }
                                })
                                DropdownMenuItem(text = { Text("Iconos") }, onClick = { showHomeMoreActions = false; showIconThemeDialog = true })
                                DropdownMenuItem(text = { Text("Respaldo") }, onClick = { showHomeMoreActions = false; showBackupDialog = true })
                            }
                        }
                        HomeEditAction("Listo", onClick = { editingHome = false; showHomeMoreActions = false })
                    }
                }
            }
            if (homePages.size > 1) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                                    if (pages.isNotEmpty() && pages[0].isEmpty()) pages[0].addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
                                    val sourcePage = pages.indexOfFirst { token in it }
                                    val dest = pages[page]
                                    val widgetCells = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == page }
                                        ?.sumOf { id -> ((launcherActivity.widgetHeightDp(id) + 99) / 100).coerceAtLeast(1) * 4 } ?: 0
                                    val appStart = (8 + widgetCells).coerceAtMost(28)
                                    val appEnd = (28 - if (page == 0) 8 else 0).coerceAtLeast(appStart)
                                    if (token in dest) {
                                        if (sourcePage >= 0 && sourcePage != page) {
                                            val duplicateIndex = pages[sourcePage].indexOf(token)
                                            if (duplicateIndex >= 0) pages[sourcePage][duplicateIndex] = "-"
                                            val saved = pages.map { it.toList() }
                                            ctx.getSharedPreferences("nexo_home", 0).edit()
                                                .putString("packages", saved.firstOrNull().orEmpty().joinToString("|"))
                                                .putString("pages", saved.joinToString("~") { it.joinToString("|") }).apply()
                                            homePages = saved
                                        }
                                        currentPage = page; homePackages = pages[page].toList()
                                    } else {
                                        while (dest.size < 28) dest.add("-")
                                        val free = (appStart until appEnd).firstOrNull { dest.getOrNull(it) == "-" }
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
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Glass,
                    shape = RoundedCornerShape(23.dp),
                    modifier = Modifier.weight(1f).height(48.dp).clickable { drawer = true }
                ) {
                    Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Buscar apps y web", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { drawer = true }, modifier = Modifier.testTag("open-app-drawer"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("Apps", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
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
                                val fromDock = payload.startsWith("dock:")
                                val pkg = payload.removePrefix("home:").removePrefix("dock:")
                                if (apps.any { it.pkg == pkg }) {
                                    val originalTarget = dockPackages.getOrNull(dockIndex) ?: "-"
                                    val updated = dockPackages.toMutableList().apply { while (size < 4) add("-") }
                                    val sourceDockIndex = updated.indexOf(pkg)
                                    if (sourceDockIndex >= 0 && sourceDockIndex != dockIndex) {
                                        val displaced = updated[dockIndex]
                                        updated[dockIndex] = pkg
                                        updated[sourceDockIndex] = displaced
                                    } else updated[dockIndex] = pkg
                                    dockPackages = updated
                                    ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", updated.joinToString("|")).apply()
                                    dock = updated.mapNotNull { item -> apps.find { it.pkg == item } }
                                    if (!fromDock) {
                                        val pages = homePages.map { it.toMutableList() }.toMutableList()
                                        val sourcePage = pages.indexOfFirst { pkg in it }
                                        if (sourcePage >= 0) {
                                            val sourceSlot = pages[sourcePage].indexOf(pkg)
                                            pages[sourcePage][sourceSlot] = if (sourceDockIndex < 0 && originalTarget in apps.map { it.pkg }) originalTarget else "-"
                                            homePages = pages.map { it.toList() }
                                            homePackages = homePages.getOrNull(currentPage).orEmpty()
                                            ctx.getSharedPreferences("nexo_home", 0).edit()
                                                .putString("pages", homePages.joinToString("~") { it.joinToString("|") })
                                                .putString("packages", homePages.firstOrNull().orEmpty().joinToString("|")).apply()
                                        }
                                    }
                                }
                            })
                    }
                }
            }
            }

                        Spacer(Modifier.height(10.dp))
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                Drawer(apps, editingHome, onAppLongPress = { app -> showShortcutsFor(app) }) { drawer = false }
            }
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
        if (editingHome && !drawer && openFolder == null) {
            HomeEditOverview(
                pages = homePages,
                currentPage = currentPage,
                apps = apps,
                folders = userFolders,
                dockPackages = dockPackages,
                quote = quote,
                timeText = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                dateText = date,
                weatherText = if (w != null) "${w.temp}° · ${w.label} · ${w.city}" else city.ifBlank { "Tocá para elegir ciudad" },
                defaultPackages = defaultHomeApps.map { it.pkg },
                onSelectPage = { page -> currentPage = page; homePackages = homePages.getOrNull(page).orEmpty() },
                onClose = { editingHome = false; showHomeMoreActions = false },
                onWidget = {
                    val count = (if (homePages.getOrNull(currentPage).orEmpty().isEmpty() && currentPage == 0) defaultHomeApps.size else homePages.getOrNull(currentPage).orEmpty().count { it != "-" }) + 8 + if (currentPage == 0) 8 else 0
                    editingHome = false
                    launcherActivity?.launchWidgetPicker(currentPage, count)
                },
                onWallpaper = {
                    editingHome = false
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
                        .onFailure { Toast.makeText(ctx, "No se pudo abrir la selección de fondo", Toast.LENGTH_SHORT).show() }
                },
                onAddPage = {
                    val expanded = (homePages + listOf(emptyList())).toMutableList()
                    ctx.getSharedPreferences("nexo_home", 0).edit()
                        .putString("pages", expanded.joinToString("~") { it.joinToString("|") }).apply()
                    homePages = expanded
                    currentPage = expanded.lastIndex
                    homePackages = emptyList()
                },
                onFolder = { showNewFolder = true },
                onIcons = { editingHome = false; showIconThemeDialog = true },
                onManage = { editingHome = false; showPageManager = true },
                onBackup = { editingHome = false; showBackupDialog = true }
            )
        }
    }
    val availableWidgetProviders = launcherActivity?.availableWidgetProviders.orEmpty()
    if (availableWidgetProviders.isNotEmpty()) AlertDialog(
        onDismissRequest = { launcherActivity?.cancelWidgetPicker() },
        title = { Text("Elegí un widget") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(availableWidgetProviders, key = { it.provider.flattenToString() }) { provider ->
                    val label = runCatching { provider.loadLabel(ctx.packageManager).toString() }
                        .getOrDefault(provider.provider.className)
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            launcherActivity?.selectWidgetProvider(provider)
                        }.padding(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(provider.provider.packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { launcherActivity?.cancelWidgetPicker() }) { Text("Cancelar") } }
    )
    if (showIconThemeDialog) AlertDialog(
        onDismissRequest = { showIconThemeDialog = false },
        title = { Text("Estilo de iconos") },
        text = { Column {
            Text("Las imágenes se usan tal como fueron entregadas. Las apps sin una correspondencia identificable conservan el icono original del POCO.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            IconTheme.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("icon-theme-option-${option.key}").clickable {
                    showIconThemeDialog = false
                    if (option != iconTheme) {
                        ctx.getSharedPreferences("nexo_icon_settings", 0).edit().putString("theme", option.key).apply()
                        iconTheme = option
                    }
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = iconTheme == option, onClick = {
                        showIconThemeDialog = false
                        if (option != iconTheme) {
                            ctx.getSharedPreferences("nexo_icon_settings", 0).edit().putString("theme", option.key).apply()
                            iconTheme = option
                        }
                    })
                    Text(option.title, modifier = Modifier.padding(start = 8.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { showIconThemeDialog = false }) { Text("Cerrar") } }
    )
    if (shortcutTarget != null && !shortcutAccessDenied) AlertDialog(
        onDismissRequest = { shortcutTarget = null; shortcutRows = emptyList() },
        title = { Text("${shortcutTarget?.label} · accesos directos") },
        text = { Column {
            if (shortcutRows.isEmpty()) Text("Esta aplicación no ofrece accesos directos ahora.")
            else shortcutRows.forEach { shortcut ->
                TextButton(onClick = {
                    val app = shortcutTarget ?: return@TextButton
                    runCatching { ctx.getSystemService(LauncherApps::class.java)?.startShortcut(app.pkg, shortcut.id, null, null, Process.myUserHandle()) }
                        .onFailure { Toast.makeText(ctx, "Ese acceso directo ya no está disponible", Toast.LENGTH_SHORT).show() }
                    shortcutTarget = null
                    shortcutRows = emptyList()
                    drawer = false
                }, modifier = Modifier.fillMaxWidth()) { Text((shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.id).toString(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        } },
        confirmButton = { TextButton(onClick = { shortcutTarget?.let(::addAppToCurrentHome); shortcutTarget = null; shortcutRows = emptyList() }) { Text("Agregar al inicio") } },
        dismissButton = { TextButton(onClick = { shortcutTarget = null; shortcutRows = emptyList() }) { Text("Cerrar") } }
    )
    if (shortcutTarget != null && shortcutAccessDenied) AlertDialog(
        onDismissRequest = { shortcutTarget = null; shortcutAccessDenied = false },
        title = { Text("Accesos directos de ${shortcutTarget?.label}") },
        text = { Text("Android solo permite que el launcher predeterminado lea estos accesos directos. NEXO todavía se está usando como aplicación normal, así que este acceso aparece cuando lo establezcas como inicio predeterminado.") },
        confirmButton = { TextButton(onClick = {
            shortcutTarget = null
            shortcutAccessDenied = false
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
        }) { Text("Elegir launcher") } },
        dismissButton = { TextButton(onClick = { shortcutTarget = null; shortcutAccessDenied = false }) { Text("Cerrar") } }
    )
    if (showLockAdminDialog) AlertDialog(
        onDismissRequest = { showLockAdminDialog = false },
        title = { Text("Doble toque para bloquear") },
        text = { Text("Android requiere activar NEXO como administrador del dispositivo para bloquear la pantalla. El permiso se usa solo para esta acción; el bloqueo exigirá tu método de desbloqueo habitual. Podés cancelar ahora y activarlo después.") },
        confirmButton = { TextButton(onClick = {
            showLockAdminDialog = false
            runCatching { launcherActivity?.requestLockAdminActivation() }
                .onFailure { Toast.makeText(ctx, "No se pudieron abrir los ajustes de administrador", Toast.LENGTH_LONG).show() }
        }) { Text("Revisar permiso") } },
        dismissButton = { TextButton(onClick = { showLockAdminDialog = false }) { Text("Ahora no") } }
    )
    if (showBackupDialog) AlertDialog(
        onDismissRequest = { showBackupDialog = false },
        title = { Text("Respaldo de NEXO") },
        text = { Text("Exporta o restaura páginas, posiciones, carpetas, dock, ciudad, frase, imagen de la tarjeta y categorías manuales. La restauración reemplaza esos datos. Los widgets solo se reasignan si ya existen en este teléfono; los demás habrá que agregarlos y configurarlos. El fondo del sistema no se incluye.") },
        confirmButton = { TextButton(onClick = {
            showBackupDialog = false
            exportBackup.launch("NEXO-respaldo.json")
        }) { Text("Exportar archivo") } },
        dismissButton = { Row {
            TextButton(onClick = {
                showBackupDialog = false
                importBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            }) { Text("Importar") }
            TextButton(onClick = { showBackupDialog = false }) { Text("Cancelar") }
        } }
    )
    pendingImportText?.let { backupText ->
        AlertDialog(
            onDismissRequest = { pendingImportText = null },
            title = { Text("¿Restaurar este respaldo?") },
            text = { Text("Se reemplazarán la distribución del inicio, las carpetas, el dock, la ciudad, la frase, la imagen de tarjeta, las categorías manuales y el conjunto de widgets actuales. Los widgets que no formen parte del respaldo se quitarán. Los que no estén disponibles en este teléfono tendrás que agregarlos y configurarlos de nuevo.") },
            confirmButton = { TextButton(onClick = {
                runCatching {
                    val saved = LauncherBackup.restore(ctx, LauncherBackup.parse(backupText))
                    iconTheme = savedIconTheme(ctx)
                    homePages = saved.pages
                    homePackages = saved.pages.firstOrNull().orEmpty()
                    currentPage = 0
                    dockPackages = saved.dock
                    dock = saved.dock.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                    packageRefreshScope.launch {
                        val refreshed = withContext(Dispatchers.IO) { loadApps(ctx) }
                        apps = refreshed
                        dock = saved.dock.mapNotNull { pkg -> refreshed.find { it.pkg == pkg } }
                    }
                    userFolders = saved.folders
                    city = saved.city
                    quote = saved.quote
                    photoTick++
                    val widgetCounts = launcherActivity?.restoreWidgetPlacements(saved.widgetSpecs, saved.pages.size) ?: Triple(0, saved.widgetSpecs.length(), 0)
                    pendingImportText = null
                    Toast.makeText(ctx, "Respaldo restaurado. ${widgetCounts.second} widget(s) hay que agregarlos de nuevo; se quitaron ${widgetCounts.third} widget(s) ajenos al respaldo.", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(ctx, it.message ?: "No se pudo restaurar el respaldo", Toast.LENGTH_LONG).show() }
            }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { pendingImportText = null }) { Text("Cancelar") } }
        )
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
        if (currentPage == 0 && pages[0].isEmpty()) pages[0].addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
        var existingFolderSlot = -1
        pages.forEachIndexed { pageIndex, savedPage -> savedPage.forEachIndexed { slotIndex, token ->
            if (token == "folder:$name") {
                if (pageIndex == currentPage && existingFolderSlot < 0) existingFolderSlot = slotIndex
                savedPage[slotIndex] = "-"
            }
        } }
        val page = pages[currentPage]
        if (page.isEmpty() && currentPage == 0) page.addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
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
        val quoteCellArea = if (currentPage == 0) 8 else 0
        val pageOffset = (8 + pageWidgetArea).coerceAtMost(28)
        val pageCellLimit = (28 - pageOffset - quoteCellArea).coerceAtLeast(0)
        val visibleRange = pageOffset until (28 - quoteCellArea).coerceAtLeast(pageOffset)
        val emptyRelative = targetPage.drop(pageOffset).take(pageCellLimit).indexOfFirst { it == "-" }
        val emptySlot = if (emptyRelative >= 0) pageOffset + emptyRelative else -1
        val folderSlot = when {
            existingFolderSlot in visibleRange -> existingFolderSlot
            reusableIndex in visibleRange -> reusableIndex
            emptySlot >= 0 -> emptySlot
            pageCellLimit > 0 && targetPage.size < pageOffset + pageCellLimit -> maxOf(targetPage.size, pageOffset).also { slot -> while (targetPage.size <= slot) targetPage.add("-") }
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
            dockPackages = dockPackages.map { if (it in packages) "-" else it }
            dock = dockPackages.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
            homePages = pages.map { it.toList() }; homePackages = homePages.getOrNull(currentPage).orEmpty()
            ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", dockPackages.joinToString("|"))
                .putString("pages", homePages.joinToString("~") { it.joinToString("|") }).putString("packages", homePages.firstOrNull().orEmpty().joinToString("|")).apply()
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
        dockPackages = dockPackages.map { if (it in packages) "-" else it }
        dock = dockPackages.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
        homePages = renamedPages; homePackages = renamedPages.getOrNull(currentPage).orEmpty()
        ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", dockPackages.joinToString("|"))
            .putString("pages", renamedPages.joinToString("~") { it.joinToString("|") }).putString("packages", renamedPages.firstOrNull().orEmpty().joinToString("|")).apply()
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
                val previousCurrent = currentPage
                val mutable = homePages.toMutableList().also { it.removeAt(index) }.ifEmpty { mutableListOf(emptyList()) }
                currentPage = when {
                    previousCurrent > index -> previousCurrent - 1
                    previousCurrent == index -> index.coerceAtMost(mutable.lastIndex)
                    else -> previousCurrent
                }
                homePages = mutable; homePackages = mutable[currentPage]
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
private fun HomeHeader(
    now: LocalDateTime,
    date: String,
    weather: WeatherInfo?,
    city: String,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onWeatherClick: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val clockFontSize = (maxHeight.value * 0.32f).coerceIn(34f, 58f).sp
        Column(Modifier.fillMaxSize().padding(top = 2.dp)) {
        Text(
            now.format(DateTimeFormatter.ofPattern("HH:mm")),
            color = Color.White,
            fontSize = clockFontSize,
            maxLines = 1,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Light,
            modifier = Modifier.testTag("home-clock").combinedClickable(onClick = {}, onLongClick = onLongPress)
        )
        Text(date, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = Glass,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onWeatherClick)
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(weather?.let { "${it.temp}°" } ?: "--°", color = Color.White, fontSize = 24.sp, fontFamily = FontFamily.Serif)
                Spacer(Modifier.width(12.dp))
                Text(
                    weather?.let { "${it.label} · ${it.city}" } ?: if (city.isBlank()) "Toca para elegir tu ciudad" else "Cargando clima...",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        }
    }
}

@Composable
fun QuoteCard(
    text: String,
    photo: ImageBitmap?,
    onClick: () -> Unit,
    height: Dp = 110.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(26.dp))
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
        Box(Modifier.size(size)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.3f)).background(Glass).padding(7.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    apps.take(4).chunked(2).forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            r.forEach { item -> Image(item.icon, null, Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                        }
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
            Modifier.padding(horizontal = 28.dp).clip(RoundedCornerShape(32.dp)).background(Cream.copy(alpha = 0.86f))
                .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(32.dp))
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
    labelFontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    onLaunch: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onContextMenu: (() -> Unit)? = null,
    dragPayload: String? = null,
    testTag: String? = null
) {
    val ctx = LocalContext.current
    val dragView = LocalView.current
    val density = LocalDensity.current.density
    val iconModifier = Modifier
        .width(size + 12.dp)
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
        .combinedClickable(
            onClick = {
                onLaunch()
                runCatching { ctx.startActivity(app.intent) }
            },
            onLongClick = {
                if (onContextMenu != null) {
                    onContextMenu.invoke()
                    return@combinedClickable
                }
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
        Box(Modifier.size(size)) {
            Image(app.icon, app.label, Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.3f)), contentScale = ContentScale.Crop)
        }
        if (showLabel) {
            Text(
                app.label,
                color = textColor,
                fontSize = labelFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 9.dp)
            )
        }
    }
}
@Composable
private fun HomeEditOverview(
    pages: List<List<String>>,
    currentPage: Int,
    apps: List<AppItem>,
    folders: List<Pair<String, List<String>>>,
    dockPackages: List<String>,
    quote: String,
    timeText: String,
    dateText: String,
    weatherText: String,
    defaultPackages: List<String>,
    onSelectPage: (Int) -> Unit,
    onClose: () -> Unit,
    onWidget: () -> Unit,
    onWallpaper: () -> Unit,
    onAddPage: () -> Unit,
    onFolder: () -> Unit,
    onIcons: () -> Unit,
    onManage: () -> Unit,
    onBackup: () -> Unit
) {
    Column(
        Modifier.fillMaxSize()
            .background(Color(0xF2171717))
            .clickable(onClick = {})
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Personalizar inicio", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Deslizá entre páginas y elegí una para editar", color = Cream.copy(alpha = 0.68f), fontSize = 12.sp)
            }
            TextButton(onClick = onClose, modifier = Modifier.testTag("finish-home-edit"), shape = RoundedCornerShape(22.dp)) {
                Text("Listo", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            pages.forEachIndexed { index, page ->
                HomeMiniPagePreview(
                    index = index,
                    selected = index == currentPage,
                    packages = if (index == 0 && page.isEmpty()) List(8) { "-" } + defaultPackages else page,
                    apps = apps,
                    folders = folders,
                    dockPackages = dockPackages,
                    quote = quote,
                    timeText = timeText,
                    dateText = dateText,
                    weatherText = weatherText,
                    onClick = { onSelectPage(index) },
                    modifier = Modifier.fillMaxHeight(0.94f).width(238.dp).testTag("page-preview-$index")
                )
            }
        }
        Text(
            "Tocá una miniatura para cambiar de página. Mantené apretado un espacio vacío para volver a esta vista.",
            color = Cream.copy(alpha = 0.68f), fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HomeEditActionCard("Widgets", "Agregar widgets", Modifier.weight(1f), onWidget)
                HomeEditActionCard("Fondo", "Cambiar imagen", Modifier.weight(1f), onWallpaper)
                HomeEditActionCard("+ Página", "Crear pantalla", Modifier.weight(1f), onAddPage, testTag = "add-home-page")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HomeEditActionCard("+ Carpeta", "Agrupar apps", Modifier.weight(1f), onFolder)
                HomeEditActionCard("Iconos", "Elegir estilo", Modifier.weight(1f), onIcons, testTag = "icon-theme-open")
                HomeEditActionCard("Páginas", "Ordenar o borrar", Modifier.weight(1f), onManage)
            }
            HomeEditActionCard("Respaldo", "Guardar o restaurar la distribución del inicio", Modifier.fillMaxWidth(), onBackup, compact = true)
        }
    }
}

@Composable
private fun HomeMiniPagePreview(
    index: Int,
    selected: Boolean,
    packages: List<String>,
    apps: List<AppItem>,
    folders: List<Pair<String, List<String>>>,
    dockPackages: List<String>,
    quote: String,
    timeText: String,
    dateText: String,
    weatherText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = if (selected) Color(0xFFFFD37A) else Color.White.copy(alpha = 0.17f)
    Surface(
        color = Color(0xFF292722), shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, outline),
        modifier = modifier.semantics {
            contentDescription = "Página ${index + 1}${if (selected) ", seleccionada" else ""} · ${packages.count { it != "-" }} apps"
        }.clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF39352F), Color(0xFF201F1D))))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            Text("Pantalla ${index + 1}", color = Cream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("$timeText   ·   $dateText", color = Cream.copy(alpha = 0.7f), fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(27.dp)) {
                Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(weatherText, color = Cream, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(if (index == 0) 3 else 5) { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(4) { column ->
                            val slot = 8 + row * 4 + column
                            val token = packages.getOrNull(slot)?.takeIf { it != "-" }
                            val folderName = token?.removePrefix("folder:")?.takeIf { token.startsWith("folder:") }
                            val folderApps = folderName?.let { key -> folders.find { it.first == key }?.second.orEmpty() }.orEmpty()
                            val app = token?.takeUnless { it.startsWith("folder:") }?.let { pkg -> apps.find { it.pkg == pkg } }
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                when {
                                    app != null -> Image(
                                        app.icon, app.label,
                                        Modifier.size(23.dp).clip(RoundedCornerShape(7.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    folderName != null -> {
                                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                            folderApps.take(2).forEach { pkg ->
                                                apps.find { it.pkg == pkg }?.let { item ->
                                                    Image(item.icon, null, Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Crop)
                                                }
                                            }
                                        }
                                    }
                                    else -> Spacer(Modifier.size(23.dp))
                                }
                                if (app != null) Text(app.label, color = Color.White, fontSize = 5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (index == 0 && quote.isNotBlank()) {
                Surface(color = Color.White.copy(alpha = 0.10f), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(quote, color = Cream, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }
            Surface(color = Color.White.copy(alpha = 0.13f), shape = RoundedCornerShape(11.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(22.dp)) {
                Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) { Text("Buscar apps y web", color = Cream.copy(alpha = 0.8f), fontSize = 7.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(4) { i ->
                    val app = dockPackages.getOrNull(i)?.let { pkg -> apps.find { it.pkg == pkg } }
                    if (app != null) Image(app.icon, app.label, Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.12f)))
                }
            }
        }
    }
}

@Composable
private fun HomeEditActionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    compact: Boolean = false,
    testTag: String? = null
) {
    Surface(
        color = Color.White.copy(alpha = 0.095f),
        shape = RoundedCornerShape(17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
        modifier = modifier.height(if (compact) 53.dp else 60.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)).clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 7.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Cream.copy(alpha = 0.68f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HomeEditAction(label: String, onClick: () -> Unit, testTag: String? = null) {
    TextButton(
        onClick = onClick,
        modifier = if (testTag == null) Modifier else Modifier.testTag(testTag),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
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
    Box(Modifier.size(40.dp)
        .testTag(if (selected) "home-page-indicator-selected-$page" else "home-page-indicator-$page")
        .semantics { contentDescription = if (selected) "Ir a la página ${page + 1}, seleccionada" else "Ir a la página ${page + 1}" }
        .clickable(onClick = onSelect)
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription
                ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true }, target = target
        ), contentAlignment = Alignment.Center) {
        Text(if (selected) "●" else "○", color = Color.White, fontSize = 12.sp)
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
            onLongPress = onLongPress, dragPayload = "dock:${app.pkg}", testTag = "dock-app-${app.pkg}")
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
    cellHeight: Dp,
    iconSize: Dp,
    onLongPress: () -> Unit,
    onDoubleTapEmpty: () -> Unit = {},
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
            .height(cellHeight)
            .clipToBounds()
            .then(
                if (app == null && folderName == null) {
                    Modifier.pointerInput(index) {
                        detectTapGestures(onLongPress = { onLongPress() }, onDoubleTap = { onDoubleTapEmpty() })
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
            AppIcon(
                app, iconSize, Color.White,
                showLabel = homeIconSizing(cellHeight.value).showLabel,
                labelFontSize = 11.sp,
                testTag = "home-page-app",
                onLongPress = onLongPress,
                dragPayload = "home:${app.pkg}"
            )
            if (editingHome) {
                Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clickable { onRemove(app.pkg) }) {
                    Box(contentAlignment = Alignment.Center) { Text("−", color = Color.White, fontSize = 16.sp, lineHeight = 16.sp) }
                }
            }
        } else if (folderName != null) {
            FolderIcon(folderName, folderApps, iconSize, onFolderClick, dragPayload = "folder:$folderName")
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
fun Drawer(apps: List<AppItem>, editingHome: Boolean, onAppLongPress: (AppItem) -> Unit = {}, close: () -> Unit) {
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
    Column(Modifier.fillMaxSize().background(Color(0xFF171411)).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
    var menuExpanded by remember { mutableStateOf(false) }

    Spacer(Modifier.height(10.dp))

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = close, modifier = Modifier.testTag("close-app-drawer"), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            Text("Cerrar", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(6.dp))
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
    val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    val canShowPlatformShortcuts = launcherApps?.hasShortcutHostPermission() == true
    AppIcon(app, 56.dp, Color.White, onLaunch = close,
        onLongPress = close,
        onContextMenu = if (canShowPlatformShortcuts && !editingHome) ({ onAppLongPress(app) }) else null,
        dragPayload = app.pkg)
}


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
