package com.nexo.launcher

import android.content.Intent
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetManager
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Offset as ComposeOffset
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val requestedPage = prefs.getInt("current_page", 0).coerceAtLeast(0)
        mutableStateOf(savedPages?.getOrNull(requestedPage) ?: savedPages?.firstOrNull() ?: prefs.getString("packages", "")
            ?.takeIf { it.isNotBlank() }?.split("|") ?: emptyList())
    }
    var homePages by remember {
        val prefs = ctx.getSharedPreferences("nexo_home", 0)
        val savedPages = prefs.getString("pages", "")?.takeIf { it.isNotBlank() }
            ?.split("~")?.map { it.split("|").filter(String::isNotBlank) }
        mutableStateOf(savedPages ?: listOf(homePackages))
    }
    val homeCellBounds = remember { mutableStateMapOf<Int, ComposeRect>() }
    val dockCellBounds = remember { mutableStateMapOf<Int, ComposeRect>() }
    val pageIndicatorBounds = remember { mutableStateMapOf<Int, ComposeRect>() }
    var currentPage by remember {
        mutableIntStateOf(ctx.getSharedPreferences("nexo_home", 0).getInt("current_page", 0).coerceAtLeast(0))
    }
    LaunchedEffect(homePages.size, currentPage) {
        val last = (homePages.size - 1).coerceAtLeast(0)
        val normalized = currentPage.coerceIn(0, last)
        if (normalized != currentPage) currentPage = normalized
        else ctx.getSharedPreferences("nexo_home", 0).edit().putInt("current_page", currentPage).apply()
    }
    fun selectHomePage(index: Int) {
        if (index !in homePages.indices) return
        currentPage = index
        homePackages = homePages[index]
        ctx.getSharedPreferences("nexo_home", 0).edit().putInt("current_page", index).apply()
    }
    var userFolders by remember { mutableStateOf(readUserFolders(ctx)) }
    var folderEditor by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showPageManager by remember { mutableStateOf(false) }
    var pagePendingDelete by remember { mutableStateOf<Int?>(null) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (ctx.getSharedPreferences("nexo_updates", 0).getLong("pending_version", -1L) > 0L) showUpdateDialog = true
    }
    var showIconThemeDialog by remember { mutableStateOf(false) }
    var iconTheme by remember { mutableStateOf(savedIconTheme(ctx)) }
    var drawer by remember { mutableStateOf(false) }
                var editingHome by remember { mutableStateOf(false) }
var lastPackageRefresh by remember { mutableStateOf(0) }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForeground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> isForeground = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var dragPreview by remember { mutableStateOf<Pair<AppItem, ComposeOffset>?>(null) }
    var editingWidgetDragId by remember { mutableIntStateOf(-1) }
    var trashTargetBounds by remember { mutableStateOf<ComposeRect?>(null) }
    var city by remember { mutableStateOf(savedCity(ctx)) }
    var weather by remember { mutableStateOf<WeatherInfo?>(null) }
    var editCity by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf(savedQuote(ctx)) }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var photoTick by remember { mutableStateOf(0) }
    var editCard by remember { mutableStateOf(false) }
    var wallpaperDim by remember { mutableFloatStateOf(ctx.getSharedPreferences("nexo", 0).getFloat("wallpaper_dim", 0.04f).coerceIn(0f, 0.30f)) }

    fun dropAppOnDock(targetIndex: Int, payload: String) {
        val fromDock = payload.startsWith("dock:")
        val pkg = payload.removePrefix("home:").removePrefix("dock:")
        if (apps.none { it.pkg == pkg } || targetIndex !in 0..3) return
        val originalTarget = dockPackages.getOrNull(targetIndex) ?: "-"
        val updated = dockPackages.toMutableList().apply { while (size < 4) add("-") }
        val sourceDockIndex = updated.indexOf(pkg)
        if (sourceDockIndex >= 0 && sourceDockIndex != targetIndex) {
            val displaced = updated[targetIndex]
            updated[targetIndex] = pkg
            updated[sourceDockIndex] = displaced
        } else updated[targetIndex] = pkg
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

    fun dropAppOnHomePage(targetPage: Int, payload: String) {
        val fromDock = payload.startsWith("dock:")
        val token = payload.removePrefix("home:").removePrefix("dock:")
        val isFolder = token.startsWith("folder:") && userFolders.any { it.first == token.removePrefix("folder:") }
        if ((!isFolder && apps.none { it.pkg == token }) || targetPage !in homePages.indices) return
        val pages = homePages.map { it.toMutableList() }.toMutableList()
        if (pages.isNotEmpty() && pages[0].isEmpty()) pages[0].addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
        val sourcePage = pages.indexOfFirst { token in it }
        val destination = pages[targetPage]
        if (token in destination) {
            if (sourcePage >= 0 && sourcePage != targetPage) {
                val duplicateIndex = pages[sourcePage].indexOf(token)
                if (duplicateIndex >= 0) pages[sourcePage][duplicateIndex] = "-"
                val saved = pages.map { it.toList() }
                ctx.getSharedPreferences("nexo_home", 0).edit()
                    .putString("packages", saved.firstOrNull().orEmpty().joinToString("|"))
                    .putString("pages", saved.joinToString("~") { it.joinToString("|") }).apply()
                homePages = saved
            }
            selectHomePage(targetPage)
            return
        }
        val widgetCells = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == targetPage }
            ?.sumOf { id -> widgetRowsForHeight(launcherActivity.widgetHeightDp(id), launcherActivity.homeCellHeightDp.floatValue) * 4 } ?: 0
        val appStart = (8 + widgetCells).coerceAtMost(28)
        val appEnd = (28 - if (targetPage == 0) 8 else 0).coerceAtLeast(appStart)
        while (destination.size < 28) destination.add("-")
        val free = (appStart until appEnd).firstOrNull { destination.getOrNull(it) == "-" }
        if (free == null) {
            Toast.makeText(ctx, "No hay una celda libre en esa página", Toast.LENGTH_SHORT).show()
            return
        }
        if (sourcePage >= 0 && sourcePage != targetPage) {
            val sourceIndex = pages[sourcePage].indexOf(token)
            if (sourceIndex >= 0) pages[sourcePage][sourceIndex] = "-"
        }
        destination[free] = token
        val saved = pages.map { it.toList() }
        val currentPrefs = ctx.getSharedPreferences("nexo_home", 0).edit()
            .putString("packages", saved.firstOrNull().orEmpty().joinToString("|"))
            .putString("pages", saved.joinToString("~") { it.joinToString("|") })
            .putInt("current_page", targetPage).commit()
        if (!currentPrefs) return
        homePages = saved
        currentPage = targetPage
        homePackages = saved[targetPage]
        if (fromDock) {
            dockPackages = dockPackages.map { if (it == token) "-" else it }
            dock = dockPackages.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
            ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", dockPackages.joinToString("|")).apply()
        }
    }

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
            ?.sumOf { id -> widgetRowsForHeight(launcherActivity.widgetHeightDp(id), launcherActivity.homeCellHeightDp.floatValue) * 4 } ?: 0
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

    fun deleteHomePage(index: Int) {
        if (index !in homePages.indices || homePages.size <= 1) return
        val updatedPages = homePages.toMutableList().apply { removeAt(index) }
            .ifEmpty { mutableListOf(emptyList()) }
            .mapIndexed { pageIndex, page ->
                if (index == 0 && pageIndex == 0 && page.isEmpty()) List(28) { "-" } else page.toList()
            }
        val nextCurrentPage = when {
            currentPage > index -> currentPage - 1
            currentPage == index -> index.coerceAtMost(updatedPages.lastIndex)
            else -> currentPage.coerceAtMost(updatedPages.lastIndex)
        }
        val saved = ctx.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", updatedPages.joinToString("~") { it.joinToString("|") })
            .putString("packages", updatedPages.firstOrNull().orEmpty().joinToString("|"))
            .putInt("current_page", nextCurrentPage)
            .commit()
        if (!saved) {
            Toast.makeText(ctx, "No se pudo guardar la eliminación de la página", Toast.LENGTH_LONG).show()
            return
        }
        val pageWidgetIds = launcherActivity?.hostedWidgetIds
            ?.filter { launcherActivity.hostedWidgetPages[it] == index }
            ?.toList().orEmpty()
        pageWidgetIds.forEach { launcherActivity?.removeWidget(it) }
        launcherActivity?.deleteWidgetPage(index)
        currentPage = nextCurrentPage
        homePages = updatedPages
        homePackages = updatedPages.getOrNull(currentPage).orEmpty()
    }

    fun requestDeleteHomePage(index: Int) {
        if (index !in homePages.indices || homePages.size <= 1) return
        val hasApps = homePages[index].any { it.isNotBlank() && it != "-" } ||
            (index == 0 && homePages[index].isEmpty() && defaultHomeApps.isNotEmpty())
        val hasWidgets = launcherActivity?.hostedWidgetPages?.values?.any { it == index } == true
        if (hasApps || hasWidgets) pagePendingDelete = index else deleteHomePage(index)
    }

    fun removeAppFromHomePage(pageIndex: Int, packageName: String) {
        if (pageIndex !in homePages.indices || packageName.isBlank()) return
        val pages = homePages.map { it.toMutableList() }.toMutableList()
        val page = pages[pageIndex]
        if (page.isEmpty() && pageIndex == 0) {
            page.addAll(List(8) { "-" } + defaultHomeApps.map { it.pkg })
        }
        val slot = page.indexOf(packageName)
        if (slot < 0) return
        page[slot] = "-"
        val savedPages = pages.map { it.toList() }
        val committed = ctx.getSharedPreferences("nexo_home", 0).edit()
            .putString("pages", savedPages.joinToString("~") { it.joinToString("|") })
            .putString("packages", savedPages.firstOrNull().orEmpty().joinToString("|"))
            .commit()
        if (!committed) {
            Toast.makeText(ctx, "No se pudo quitar la aplicación de esta página", Toast.LENGTH_LONG).show()
            return
        }
        homePages = savedPages
        homePackages = savedPages.getOrNull(currentPage).orEmpty()
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
    LaunchedEffect(isForeground) {
        while (isForeground) {
            now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    LaunchedEffect(city, isForeground) {
        if (!isForeground) return@LaunchedEffect
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
            .testTag("home-surface")
            .background(Color.Black.copy(alpha = if (editingHome) wallpaperDim.coerceAtMost(0.12f) else wallpaperDim.coerceIn(0f, 0.30f)))
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
                                    selectHomePage(nextPage)
                                    animation.snapTo(if (direction > 0) width else -width)
                                    pageSwipeX = animation.value
                                }
                                animation.animateTo(0f, tween(180)) { pageSwipeX = value }
                            }
                        }
                    )
                }
            }
            .pointerInput(drawer, editingHome) {
                if (!drawer && !editingHome) {
                    var verticalDistance = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            verticalDistance += amount
                            change.consume()
                        },
                        onDragEnd = {
                            if (verticalDistance < -viewConfiguration.touchSlop * 2f) drawer = true
                            verticalDistance = 0f
                        },
                        onDragCancel = { verticalDistance = 0f }
                    )
                }
            }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            val pageWidgetIds = launcherActivity?.hostedWidgetIds?.filter { launcherActivity.hostedWidgetPages[it] == currentPage }.orEmpty()
            val nativeWidgetRows = launcherActivity?.widgetRowsForPage(
                pageWidgetIds, launcherActivity.homeCellHeightDp.floatValue
            ) ?: 0
            val quoteRows = if (currentPage == 0 && quote.isNotBlank()) 2 else 0
            val quoteItemCount = if (quoteRows > 0) 1 else 0
            val headerCellCount = 8
            val widgetCellOffset = nativeWidgetRows * 4
            val quoteCellCount = quoteRows * 4
            val firstAppCell = (headerCellCount + widgetCellOffset).coerceAtMost(28)
            val appEndCell = (28 - quoteCellCount).coerceAtLeast(firstAppCell)
            val pageCapacity = (appEndCell - firstAppCell).coerceAtLeast(0)
            val pageAppCellCount = (if (homePackages.isEmpty() && currentPage == 0) defaultHomeApps.size else homePackages.count { it != "-" }) + headerCellCount + quoteCellCount
            val widgetLayoutKey = launcherActivity?.hostedWidgetIds?.joinToString(",") { id ->
                "$id:${launcherActivity.hostedWidgetPages[id] ?: 0}:${launcherActivity.widgetHeightDp(id)}:${launcherActivity.widgetColumnSpan(id)}"
            }.orEmpty()
            LaunchedEffect(homePages, widgetLayoutKey, apps, dockPackages, launcherActivity?.homeCellHeightDp?.floatValue, launcherActivity?.homeCellHeightMeasured?.value) {
                if (apps.isEmpty() || launcherActivity?.homeCellHeightMeasured?.value != true) return@LaunchedEffect
                val widgetRowsByPage = launcherActivity?.hostedWidgetIds.orEmpty().groupBy { id ->
                    launcherActivity?.hostedWidgetPages?.get(id) ?: 0
                }.mapValues { (_, ids) -> launcherActivity?.widgetRowsForPage(ids, launcherActivity.homeCellHeightDp.floatValue) ?: 0 }
                val migrated = HomeGrid.normalize(
                    pages = homePages,
                    widgetRowsByPage = widgetRowsByPage,
                    defaultPackages = defaultHomeApps.map { it.pkg },
                    quoteVisible = quote.isNotBlank(),
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
                val cellHeight = homeGridCellHeightDp(maxHeight.value, quoteRows > 0, gridGap.value).dp
                SideEffect {
                    launcherActivity?.let { activity ->
                        if (kotlin.math.abs(activity.homeCellHeightDp.floatValue - cellHeight.value) > 0.25f) {
                            activity.homeCellHeightDp.floatValue = cellHeight.value
                        }
                        if (!activity.homeCellHeightMeasured.value) activity.homeCellHeightMeasured.value = true
                    }
                }
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
                        span = { index ->
                            when {
                                index == 0 || (quoteItemCount > 0 && index == 1 + pageWidgetIds.size + pageCapacity) -> GridItemSpan(maxLineSpan)
                                index in 1..pageWidgetIds.size -> GridItemSpan(launcherActivity?.widgetColumnSpan(pageWidgetIds[index - 1]) ?: maxLineSpan)
                                else -> GridItemSpan(1)
                            }
                        }
                    ) { itemIndex ->
                    if (itemIndex == 0) {
                        HomeHeader(
                            now = now,
                            date = date,
                            weather = w,
                            city = city,
                            modifier = Modifier.fillMaxWidth().height(headerHeight),
                            onLongPress = { editingHome = true },
                            onWeatherClick = { editCity = true },
                            onSettings = { showSettingsDialog = true }
                        )
                    } else if (itemIndex <= pageWidgetIds.size) {
                        val widgetId = pageWidgetIds[itemIndex - 1]
                        val widgetHostView = remember(widgetId) { launcherActivity?.createWidgetHostView(widgetId) }
                        var showWidgetPageMenu by remember(widgetId) { mutableStateOf(false) }
                        var widgetCoordinates by remember(widgetId) { mutableStateOf<LayoutCoordinates?>(null) }
                        val widgetRowsHeight = widgetRowsForHeight(launcherActivity?.widgetHeightDp(widgetId) ?: 132, cellHeight.value)
                        Box(Modifier.fillMaxWidth().height(widgetSpanHeightDp(widgetRowsHeight, cellHeight.value, gridGap.value).dp)
                            .testTag("home-widget-$widgetId")
                            .onGloballyPositioned { widgetCoordinates = it }
                            .pointerInput(widgetId) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val startedAt = down.uptimeMillis
                                    val slop = viewConfiguration.touchSlop
                                    var change = down
                                    var longPressed = false
                                    var cancelled = false
                                    var rootPosition = widgetCoordinates?.localToRoot(down.position) ?: down.position
                                    while (change.pressed) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val delta = change.position - down.position
                                        if (!longPressed && delta.getDistance() > slop) {
                                            cancelled = true
                                            break
                                        }
                                        if (!longPressed && change.uptimeMillis - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                            longPressed = true
                                            editingHome = true
                                            editingWidgetDragId = widgetId
                                        }
                                        if (longPressed) {
                                            change.consume()
                                            rootPosition = widgetCoordinates?.localToRoot(change.position) ?: change.position
                                        }
                                    }
                                    if (!cancelled && !longPressed && change.uptimeMillis - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                        longPressed = true
                                        editingHome = true
                                        editingWidgetDragId = widgetId
                                    }
                                    if (longPressed) {
                                        if (trashTargetBounds?.contains(rootPosition) == true) launcherActivity?.removeWidget(widgetId)
                                        editingWidgetDragId = -1
                                    }
                                }
                            }) {
                            Surface(color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxSize()) {
                                if (widgetHostView != null) {
                                    AndroidView(factory = { widgetHostView }, modifier = Modifier.fillMaxSize())
                                } else {
                                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("No se pudo mostrar este widget. Quitalo y volvé a agregarlo.", color = Color(0xFF4A433A), textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            if (editingHome) Surface(color = Color(0xD9212226), shape = RoundedCornerShape(22.dp),
                                shadowElevation = 8.dp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                                Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { launcherActivity?.reorderWidget(widgetId, -1) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text("↑", color = Color.White) }
                                    Box {
                                        TextButton(onClick = { showWidgetPageMenu = true }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text("Página", color = Color.White, fontSize = 11.sp) }
                                        DropdownMenu(expanded = showWidgetPageMenu, onDismissRequest = { showWidgetPageMenu = false }) {
                                            homePages.indices.forEach { destination -> DropdownMenuItem(
                                                text = { Text("Página ${destination + 1}") },
                                                onClick = { launcherActivity?.moveWidgetToPage(widgetId, destination, homePages.size, (if (homePages.getOrNull(destination).orEmpty().isEmpty() && destination == 0) defaultHomeApps.size else homePages.getOrNull(destination).orEmpty().count { it != "-" }) + 8 + if (destination == 0) 8 else 0); showWidgetPageMenu = false }
                                            ) }
                                        }
                                    }
                                    TextButton(enabled = launcherActivity?.canResizeWidget(widgetId) == true, onClick = { launcherActivity?.resizeWidget(widgetId, -90, pageAppCellCount) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text("−", color = Color.White) }
                                    TextButton(enabled = launcherActivity?.canResizeWidgetWidth(widgetId) == true,
                                        onClick = { launcherActivity?.resizeWidgetWidth(widgetId, -1, pageAppCellCount) },
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) { Text("↔−", color = Color.White, fontSize = 10.sp) }
                                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE5E8EF))
                                        .testTag("remove-widget-$widgetId").clickable { launcherActivity?.removeWidget(widgetId) }, contentAlignment = Alignment.Center) {
                                        Text("×", color = Color(0xFF343943), fontSize = 17.sp, lineHeight = 17.sp)
                                    }
                                    TextButton(enabled = launcherActivity?.canResizeWidgetWidth(widgetId) == true,
                                        onClick = { launcherActivity?.resizeWidgetWidth(widgetId, 1, pageAppCellCount) },
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) { Text("↔+", color = Color.White, fontSize = 10.sp) }
                                    TextButton(enabled = launcherActivity?.canResizeWidget(widgetId) == true, onClick = { launcherActivity?.resizeWidget(widgetId, 90, pageAppCellCount) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text("＋", color = Color.White) }
                                    TextButton(onClick = { launcherActivity?.reorderWidget(widgetId, 1) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text("↓", color = Color.White) }
                                }
                            }
                        }
                    } else if (quoteItemCount > 0 && itemIndex == 1 + pageWidgetIds.size + pageCapacity) {
                        Box(Modifier.fillMaxWidth().height(quoteHeight), contentAlignment = Alignment.Center) {
                            QuoteCard(quote, photo, onClick = { editCard = true },
                                height = minOf(quoteHeight, 150.dp), modifier = Modifier.fillMaxWidth().testTag("home-quote-card"))
                            if (editingHome) Surface(
                                color = Color(0xFFE5E8EF), shape = RoundedCornerShape(50),
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(25.dp)
                                    .testTag("remove-home-quote").clickable {
                                        saveQuote(ctx, ""); quote = ""
                                    }
                            ) { Box(contentAlignment = Alignment.Center) { Text("×", color = Color(0xFF343943), fontSize = 17.sp, lineHeight = 17.sp) } }
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
                        onAppClick = { if (!editingHome) app?.let { runCatching { ctx.startActivity(it.intent) } } },
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
                        cellBounds = homeCellBounds,
                        dockCellBounds = dockCellBounds,
                        pageIndicatorBounds = pageIndicatorBounds,
                        onCellBoundsChanged = { cellIndex, bounds -> homeCellBounds[cellIndex] = bounds },
                        onDockDrop = { payload, targetIndex -> dropAppOnDock(targetIndex, payload) },
                        onPageDrop = { payload, targetIndex -> dropAppOnHomePage(targetIndex, payload) },
                        onDragPreview = { item, position -> dragPreview = if (item != null && position != null) item to position else null },
                        isDragged = dragPreview?.first?.pkg == app?.pkg,
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
            }

            if (homePages.size > 1) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    homePages.indices.forEach { page ->
                        HomePageIndicator(
                            page = page,
                            selected = currentPage == page,
                            onSelect = { selectHomePage(page) },
                            onDrop = { payload -> dropAppOnHomePage(page, payload) },
                            onBoundsChanged = { target, bounds -> pageIndicatorBounds[target] = bounds }
                        )
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
                        DockCell(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            app = dockApp,
                            index = dockIndex,
                            editing = editingHome,
                            onBoundsChanged = { target, bounds -> dockCellBounds[target] = bounds },
                            onRemove = {
                                val updated = dockPackages.toMutableList().apply { while (size < 4) add("-"); this[dockIndex] = "-" }
                                dockPackages = updated
                                ctx.getSharedPreferences("nexo_home", 0).edit().putString("dock_packages", updated.joinToString("|")).apply()
                                dock = updated.mapNotNull { pkg -> apps.find { it.pkg == pkg } }
                            },
                            onDrop = { payload -> dropAppOnDock(dockIndex, payload) }
                        )
                    }
                }
            }

                        Spacer(Modifier.height(10.dp))
        }
        if (drawer) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                Drawer(apps) { drawer = false }
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
                widgetIdsByPage = launcherActivity?.hostedWidgetIds?.groupBy { id -> launcherActivity.hostedWidgetPages[id] ?: 0 }.orEmpty(),
                onDeletePage = { requestDeleteHomePage(it) },
                onRemoveApp = { pageIndex, packageName -> removeAppFromHomePage(pageIndex, packageName) },
                onSelectPage = { page -> selectHomePage(page) },
                onClose = { editingHome = false },
                onWidget = {
                    val quoteArea = if (currentPage == 0 && quote.isNotBlank()) 8 else 0
                    val count = (if (homePages.getOrNull(currentPage).orEmpty().isEmpty() && currentPage == 0) defaultHomeApps.size else homePages.getOrNull(currentPage).orEmpty().count { it != "-" }) + 8 + quoteArea
                    launcherActivity?.launchWidgetPicker(currentPage, count)
                },
                onWallpaper = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
                        .onFailure { Toast.makeText(ctx, "No se pudo abrir la selección de fondo", Toast.LENGTH_SHORT).show() }
                },
                onAddPage = {
                    val expanded = (homePages + listOf(emptyList())).toMutableList()
                    val newPage = expanded.lastIndex
                    ctx.getSharedPreferences("nexo_home", 0).edit()
                        .putString("pages", expanded.joinToString("~") { it.joinToString("|") })
                        .putInt("current_page", newPage).apply()
                    homePages = expanded
                    selectHomePage(newPage)
                },
                onFolder = { showNewFolder = true },
                onIcons = { showIconThemeDialog = true },
                onManage = { showPageManager = true },
                onSettings = { showSettingsDialog = true },
                onQuote = { editCard = true },
                onEffect = { wallpaperDim = it },
                showTrashTarget = editingHome || editingWidgetDragId >= 0 || dragPreview != null,
                onTrashBoundsChanged = { trashTargetBounds = it }
            )
        }
        if (!editingHome && !drawer && dragPreview != null) Surface(
            color = Color(0xD9212226), shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
                .onGloballyPositioned { trashTargetBounds = it.boundsInRoot() }
                .testTag("home-trash-target")
        ) {
            Row(Modifier.padding(horizontal = 17.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("▤", color = Color.White, fontSize = 16.sp)
                Text("Soltá para quitar", color = Color.White, fontSize = 12.sp)
            }
        }
        dragPreview?.let { (item, position) ->
            val density = LocalDensity.current.density
            Image(
                bitmap = item.icon,
                contentDescription = "Moviendo ${item.label}",
                modifier = Modifier
                    .offset { IntOffset((position.x - 30.dp.value * density).toInt(), (position.y - 30.dp.value * density).toInt()) }
                    .size(60.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .graphicsLayer { alpha = 0.88f; shadowElevation = 14.dp.toPx() }
                    .zIndex(30f)
                    .testTag("dragging-app-preview"),
                contentScale = ContentScale.Crop
            )
        }
    }
    val availableWidgetProviders = launcherActivity?.availableWidgetProviders.orEmpty()
    if (availableWidgetProviders.isNotEmpty()) AlertDialog(
        onDismissRequest = { launcherActivity?.cancelWidgetPicker() },
        title = { Text("Elegí un widget") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(availableWidgetProviders, key = { it.provider.flattenToString() }) { provider ->
                    WidgetProviderPreviewCard(provider) {
                        launcherActivity?.selectWidgetProvider(provider)
                    }
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
    if (showSettingsDialog) NexoSettingsDialog(
        onDismiss = { showSettingsDialog = false },
        onDefaultHome = { launcherActivity?.requestDefaultHomeRole() },
        onIcons = { showSettingsDialog = false; showIconThemeDialog = true },
        onBackup = { showSettingsDialog = false; showBackupDialog = true },
        onUpdates = { showSettingsDialog = false; showUpdateDialog = true }
    )
    if (showUpdateDialog) UpdateDialog(onDismiss = { showUpdateDialog = false })
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
            text = { Column { OutlinedTextField(t, { t = it.take(80) }, label = { Text("Frase (vacía para quitar)") }, modifier = Modifier.fillMaxWidth()); TextButton({ picker.launch(arrayOf("image/*")) }) { Text("Elegir foto de la galería") } } },
            confirmButton = { TextButton({ val v = t.trim(); saveQuote(ctx, v); quote = v; editCard = false }) { Text("Guardar") } },
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
            ?.sumOf { id -> widgetRowsForHeight(launcherActivity.widgetHeightDp(id), launcherActivity.homeCellHeightDp.floatValue) * 4 } ?: 0
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
    pagePendingDelete?.let { index ->
        val appCount = homePages.getOrNull(index)?.let { page ->
            if (index == 0 && page.isEmpty()) defaultHomeApps.size else page.count { it.isNotBlank() && it != "-" }
        } ?: 0
        val widgetCount = launcherActivity?.hostedWidgetPages?.values?.count { it == index } ?: 0
        val hasContent = appCount > 0 || widgetCount > 0
        AlertDialog(
            onDismissRequest = { pagePendingDelete = null },
            title = { Text("¿Borrar página ${index + 1}?") },
            text = {
                Text(if (hasContent) "Esta página contiene $appCount aplicación(es) y $widgetCount widget(s). Se quitarán junto con la página."
                else "La página está vacía. ¿Querés eliminarla?")
            },
            confirmButton = { TextButton(modifier = Modifier.testTag("confirm-delete-home-page"), onClick = {
                pagePendingDelete = null
                deleteHomePage(index)
            }) { Text("Borrar página") } },
            dismissButton = { TextButton(modifier = Modifier.testTag("cancel-delete-home-page"), onClick = { pagePendingDelete = null }) { Text("Cancelar") } }
        )
    }
    if (showPageManager) PageManagerDialog(homePages, currentPage, launcherActivity?.hostedWidgetPages?.values?.toSet().orEmpty(),
        onDismiss = { showPageManager = false },
        onSelect = { selectHomePage(it); showPageManager = false },
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
        onDelete = { index -> showPageManager = false; requestDeleteHomePage(index) })
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
                    TextButton(enabled = pages.size > 1, onClick = { onDelete(index) }, modifier = Modifier.testTag("page-manager-delete-$index")) { Text("×") }
                }
            }
            Text("Al borrar una página también se quitan sus apps y widgets.", style = MaterialTheme.typography.bodySmall)
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
    onWeatherClick: () -> Unit,
    onSettings: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val clockFontSize = (maxHeight.value * 0.32f).coerceIn(34f, 58f).sp
        Column(Modifier.fillMaxSize().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color.White,
                fontSize = clockFontSize,
                maxLines = 1,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(1f).testTag("home-clock").combinedClickable(onClick = {}, onLongClick = onLongPress)
            )
            Box(
                Modifier.padding(end = 2.dp).size(42.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f)).testTag("open-nexo-settings")
                    .semantics { contentDescription = "Ajustes de NEXO" }
                    .clickable(onClick = onSettings), contentAlignment = Alignment.Center
            ) { Text("⚙", color = Color(0xFFD6AE70), fontSize = 20.sp) }
        }
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
    testTag: String? = null,
    longPressEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val dragView = LocalView.current
    val density = LocalDensity.current.density
    val iconModifier = modifier
        .width(size + 12.dp)
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
        .then(if (longPressEnabled) Modifier.combinedClickable(
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
        ) else Modifier)
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
    widgetIdsByPage: Map<Int, List<Int>>,
    onDeletePage: (Int) -> Unit,
    onRemoveApp: (Int, String) -> Unit,
    onSelectPage: (Int) -> Unit,
    onClose: () -> Unit,
    onWidget: () -> Unit,
    onWallpaper: () -> Unit,
    onAddPage: () -> Unit,
    onFolder: () -> Unit,
    onIcons: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
    onQuote: () -> Unit,
    onEffect: (Float) -> Unit,
    showTrashTarget: Boolean,
    onTrashBoundsChanged: (ComposeRect) -> Unit
) {
    var showConfigure by remember { mutableStateOf(false) }
    var showEffects by remember { mutableStateOf(false) }
    var overlayEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { overlayEntered = true }
    val editMotion by animateFloatAsState(if (overlayEntered) 1f else 0.94f, tween(150), label = "poco-edit-entry")
    val context = LocalContext.current
    var dim by remember(context) { mutableFloatStateOf(context.getSharedPreferences("nexo", 0).getFloat("wallpaper_dim", 0.04f).coerceIn(0f, 0.30f)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = editMotion
        scaleX = 0.98f + 0.02f * editMotion
        scaleY = 0.98f + 0.02f * editMotion
    }.testTag("home-edit-overlay")) {
        // Keep the live wallpaper, icons, widgets and dock visible; only add a light edit tint.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.025f)))
        if (showTrashTarget) Surface(
            color = Color(0xD9212226), shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
                .onGloballyPositioned { onTrashBoundsChanged(it.boundsInRoot()) }
                .testTag("home-trash-target")
        ) {
            Row(Modifier.padding(horizontal = 17.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("▤", color = Color.White, fontSize = 16.sp)
                Text("Soltá para quitar", color = Color.White, fontSize = 12.sp)
            }
        }
        Surface(
            color = Color(0xFF2788F5),
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 22.dp)
                .height(44.dp).testTag("finish-home-edit").clickable(onClick = onClose)
        ) {
            Box(Modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Text("Listo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 25.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pages.size > 1) pages.indices.forEach { page ->
                    Box(Modifier.size(if (page == currentPage) 9.dp else 7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (page == currentPage) Color.White else Color.White.copy(alpha = 0.55f))
                        .testTag("edit-select-page-$page")
                        .clickable { onSelectPage(page) })
                }
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.40f))
                    .testTag("add-home-page").clickable(onClick = onAddPage), contentAlignment = Alignment.Center) {
                    Text("+", color = Color.White, fontSize = 19.sp, lineHeight = 19.sp)
                }
                if (pages.size > 1) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE5E8EF))
                        .testTag("edit-delete-page").clickable { onDeletePage(currentPage) }, contentAlignment = Alignment.Center) {
                        Text("−", color = Color(0xFF343943), fontSize = 18.sp, lineHeight = 18.sp)
                    }
                }
            }
            Surface(color = Color(0xD9212226), shape = RoundedCornerShape(30.dp), shadowElevation = 14.dp,
                modifier = Modifier.fillMaxWidth().height(86.dp).testTag("poco-edit-toolbar")) {
                Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    EditToolbarAction("▧", "Fondo", "edit-wallpaper", onWallpaper)
                    EditToolbarAction("◌", "Efectos", "edit-effects") { showEffects = true }
                    EditToolbarAction("▦", "Widgets", "edit-widgets", onWidget)
                    EditToolbarAction("⚙", "Configurar", "edit-configure") { showConfigure = true }
                }
            }
        }
    }
    if (showEffects) AlertDialog(
        onDismissRequest = { showEffects = false },
        title = { Text("Efectos del fondo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Original" to 0f, "Suave" to 0.10f, "Oscuro" to 0.22f).forEach { (label, value) ->
                    TextButton(onClick = {
                        dim = value
                        context.getSharedPreferences("nexo", 0).edit().putFloat("wallpaper_dim", value).apply()
                        onEffect(value)
                        showEffects = false
                    }, modifier = Modifier.fillMaxWidth().testTag("wallpaper-effect-${label.lowercase()}")) {
                        Text(if (kotlin.math.abs(dim - value) < 0.01f) "✓  $label" else label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showEffects = false }) { Text("Cerrar") } }
    )
    if (showConfigure) ConfigureHomeDialog(
        onDismiss = { showConfigure = false },
        onManage = { showConfigure = false; onManage() },
        onFolder = { showConfigure = false; onFolder() },
        onIcons = { showConfigure = false; onIcons() },
        onQuote = { showConfigure = false; onQuote() },
        onSettings = { showConfigure = false; onSettings() }
    )
}

@Composable
private fun ConfigureHomeDialog(
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onFolder: () -> Unit,
    onIcons: () -> Unit,
    onQuote: () -> Unit,
    onSettings: () -> Unit
) {
    val actions = listOf(
        Triple("▤", "Páginas", "configure-home-pages") to onManage,
        Triple("▣", "Carpeta", "configure-home-folder") to onFolder,
        Triple("◈", "Iconos", "configure-home-icons") to onIcons,
        Triple("✦", "Frase e imagen", "configure-home-quote") to onQuote,
        Triple("⚙", "Ajustes de NEXO", "configure-home-settings") to onSettings
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 380.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xF51C1915),
            contentColor = Cream,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD6AE70).copy(alpha = 0.34f)),
            shadowElevation = 24.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
                Text("Configurar inicio", color = Cream, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("Personalizá tu espacio", color = Cream.copy(alpha = 0.62f), fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                val actionRows = actions.chunked(2)
                actionRows.forEachIndexed { rowIndex, rowActions ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowActions.forEach { (item, action) ->
                            ConfigureHomeActionButton(item.first, item.second, item.third, action, Modifier.weight(1f))
                        }
                        if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                    }
                    if (rowIndex < actionRows.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ConfigureHomeActionButton(glyph: String, title: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(104.dp).testTag(tag).clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = Color(0xFF29231C),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD6AE70).copy(alpha = 0.20f))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(shape = RoundedCornerShape(50), color = Color(0xFFD6AE70).copy(alpha = 0.13f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(glyph, color = Color(0xFFD6AE70), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(title, color = Cream, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun EditToolbarAction(glyph: String, title: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).fillMaxHeight().testTag(tag).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(glyph, color = Color.White, fontSize = 24.sp, lineHeight = 26.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color.White, fontSize = 11.sp, maxLines = 1)
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
    widgetIds: List<Int>,
    canDelete: Boolean,
    onDeletePage: () -> Unit,
    onRemoveApp: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = if (selected) Color(0xFFD6AE70) else Color.White.copy(alpha = 0.17f)
    val context = LocalContext.current
    val widgetPreviews = remember(widgetIds) {
        widgetIds.mapNotNull { id ->
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(context.packageManager).toString() }
                .getOrDefault(info.provider.className.substringAfterLast('.'))
            val preview = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
                info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
                    ?.let { drawable ->
                        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 480
                        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 180
                        val bitmap = android.graphics.Bitmap.createBitmap(width.coerceAtMost(720), height.coerceAtMost(360), android.graphics.Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(bitmap).also { canvas ->
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                        }
                        bitmap.asImageBitmap()
                    }
            }.getOrNull() else null
            label to preview
        }
    }
    Box(modifier.testTag("page-preview-$index")) {
        Surface(
            color = Color(0xFF292722), shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, outline),
            modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "Página ${index + 1}${if (selected) ", seleccionada" else ""} · ${packages.count { it.isNotBlank() && it != "-" }} apps · ${widgetIds.size} widgets"
            }
        ) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF39352F), Color(0xFF201F1D))))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            Text("Pantalla ${index + 1}", color = Cream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("$timeText   ·   $dateText", color = Cream.copy(alpha = 0.7f), fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(27.dp).clickable(onClick = onClick)) {
                Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(weatherText, color = Cream, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(7.dp))
            widgetPreviews.forEachIndexed { widgetIndex, (label, preview) ->
                Surface(
                    color = Color.White.copy(alpha = 0.10f), shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.fillMaxWidth().height(34.dp).testTag("page-preview-$index-widget-$widgetIndex").clickable(onClick = onClick)
                ) {
                    if (preview != null) {
                        Image(preview, contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Row(Modifier.fillMaxSize().padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("▦", color = Cream, fontSize = 11.sp)
                            Text(label, color = Cream, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                    app != null -> Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                        Image(
                                            app.icon, app.label,
                                            Modifier.size(23.dp).clip(RoundedCornerShape(7.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Box(
                                            Modifier.align(Alignment.TopEnd).size(24.dp)
                                                .testTag("remove-home-app-page-$index-${app.pkg}")
                                                .semantics { contentDescription = "Quitar ${app.label} de la página ${index + 1}" }
                                                .clickable { onRemoveApp(app.pkg) },
                                            contentAlignment = Alignment.TopEnd
                                        ) {
                                            Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(50), modifier = Modifier.size(15.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text("−", color = Color.White, fontSize = 11.sp, lineHeight = 11.sp)
                                                }
                                            }
                                        }
                                    }
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
            Surface(color = Color.White.copy(alpha = 0.13f), shape = RoundedCornerShape(11.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(22.dp).clickable(onClick = onClick)) {
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
        if (canDelete) {
            IconButton(
                onClick = onDeletePage,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).zIndex(2f)
                    .testTag("delete-home-page-$index")
                    .semantics { contentDescription = "Borrar página ${index + 1}" }
            ) {
                Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(50), modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("×", color = Color.White, fontSize = 18.sp, lineHeight = 18.sp) }
                }
            }
        }
    }
}

@Composable
private fun HomeEditActionCard(
    glyph: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    compact: Boolean = false,
    primary: Boolean = false,
    testTag: String? = null
) {
    Surface(
        color = if (primary) Color(0xFF3A3022) else Color(0xFF26231F),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (primary) 1.5.dp else 1.dp,
            if (primary) Color(0xFFD6AE70).copy(alpha = 0.58f) else Color.White.copy(alpha = 0.12f)
        ),
        shadowElevation = if (primary) 5.dp else 2.dp,
        modifier = modifier.height(if (compact) 74.dp else 84.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)).clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                .background(Color(0xFFD6AE70).copy(alpha = if (primary) 0.22f else 0.12f)), contentAlignment = Alignment.Center) {
                Text(glyph, color = Color(0xFFD6AE70), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Text(subtitle, color = Cream.copy(alpha = 0.70f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, softWrap = true)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomePageIndicator(
    page: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onDrop: (String) -> Unit,
    onBoundsChanged: (Int, ComposeRect) -> Unit
) {
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
        .onGloballyPositioned { coordinates -> onBoundsChanged(page, coordinates.boundsInRoot()) }
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
    modifier: Modifier, app: AppItem?, index: Int, editing: Boolean,
    onBoundsChanged: (Int, ComposeRect) -> Unit,
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
    Box(modifier.onGloballyPositioned { coordinates -> onBoundsChanged(index, coordinates.boundsInRoot()) }.testTag("dock-cell-$index").dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription
            ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true }, target = target
    ), contentAlignment = Alignment.Center) {
        if (app != null) AppIcon(app, 52.dp, Color.White, showLabel = false,
            dragPayload = "dock:${app.pkg}", testTag = "dock-app-${app.pkg}")
        if (editing && app != null) Surface(color = Color(0xFFE5E8EF), shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clickable { onRemove() }) {
            Box(contentAlignment = Alignment.Center) { Text("×", color = Color(0xFF343943), fontSize = 15.sp, lineHeight = 15.sp) }
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
    onAppClick: () -> Unit = {},
    cellBounds: Map<Int, ComposeRect>,
    dockCellBounds: Map<Int, ComposeRect>,
    pageIndicatorBounds: Map<Int, ComposeRect>,
    onCellBoundsChanged: (Int, ComposeRect) -> Unit,
    onDockDrop: (String, Int) -> Unit,
    onPageDrop: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onDragPreview: (AppItem?, ComposeOffset?) -> Unit = { _, _ -> },
    isDragged: Boolean = false,
    trashBounds: ComposeRect? = null,
    onTrashDrop: (String) -> Unit = {},
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

    val dragPayload = app?.let { "home:${it.pkg}" } ?: folderName?.let { "folder:$it" }
    var layoutCoordinates by remember(index) { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(cellHeight)
            .onGloballyPositioned { coordinates ->
                layoutCoordinates = coordinates
                onCellBoundsChanged(index, coordinates.boundsInRoot())
            }
            .then(if (dragPayload != null) Modifier.pointerInput(index, dragPayload) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        onLongPress()
                        var rootPosition = layoutCoordinates?.localToRoot(longPress.position) ?: longPress.position
                        if (app != null) onDragPreview(app, rootPosition)
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                rootPosition = layoutCoordinates?.localToRoot(change.position) ?: change.position
                                if (app != null) onDragPreview(app, rootPosition)
                            }
                        } finally {
                            if (app != null) onDragPreview(null, null)
                        }
                        val homeDestination = cellBounds.entries.firstOrNull { entry -> entry.value.contains(rootPosition) }?.key
                        val dockDestination = dockCellBounds.entries.firstOrNull { entry -> entry.value.contains(rootPosition) }?.key
                        val pageDestination = pageIndicatorBounds.entries.firstOrNull { entry -> entry.value.contains(rootPosition) }?.key
                        if (trashBounds?.contains(rootPosition) == true) onTrashDrop(dragPayload)
                        else if (homeDestination != null) onDrop(dragPayload, homeDestination)
                        else if (dockDestination != null && app != null) onDockDrop(dragPayload, dockDestination)
                        else if (pageDestination != null) onPageDrop(dragPayload, pageDestination)
                    } else {
                        val end = currentEvent.changes.firstOrNull { it.id == down.id } ?: down
                        val elapsed = end.uptimeMillis - down.uptimeMillis
                        val delta = end.position - down.position
                        val distanceSquared = delta.x * delta.x + delta.y * delta.y
                        val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop
                        val tapTimeout = minOf(viewConfiguration.longPressTimeoutMillis, 300L)
                        if (!end.pressed && !end.isConsumed && elapsed < tapTimeout && distanceSquared <= touchSlopSquared) {
                            if (app != null) onAppClick() else onFolderClick()
                        }
                    }
                }
            } else if (app == null && folderName == null) Modifier.pointerInput(index) {
                detectTapGestures(onLongPress = { onLongPress() })
            } else Modifier)
            .testTag("home-cell-$index")
            .clipToBounds()
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
                longPressEnabled = false,
                modifier = Modifier.graphicsLayer { alpha = if (isDragged) 0.28f else 1f }
            )
            if (editingHome) {
                Surface(color = Color(0xFFE5E8EF), shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).testTag("home-remove-app-$index-${app.pkg}").clickable { onRemove(app.pkg) }) {
                    Box(contentAlignment = Alignment.Center) { Text("×", color = Color(0xFF343943), fontSize = 16.sp, lineHeight = 16.sp) }
                }
            }
        } else if (folderName != null) {
            FolderIcon(folderName, folderApps, iconSize, onFolderClick)
            if (editingHome) {
                Surface(color = Color(0xFFE5E8EF), shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).testTag("home-remove-folder-$index-$folderName").clickable { onRemove("folder:$folderName") }) {
                    Box(contentAlignment = Alignment.Center) { Text("×", color = Color(0xFF343943), fontSize = 16.sp, lineHeight = 16.sp) }
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
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
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
private fun WidgetProviderPreviewCard(provider: AppWidgetProviderInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(provider) {
        runCatching { provider.loadLabel(context.packageManager).toString() }
            .getOrDefault(provider.provider.className.substringAfterLast('.'))
    }
    val densityDpi = context.resources.displayMetrics.densityDpi
    val preview = remember(provider, densityDpi) {
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
            provider.loadPreviewImage(context, densityDpi)?.let { drawable ->
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 480
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 240
                drawable.toBitmap(width.coerceAtMost(720), height.coerceAtMost(480)).asImageBitmap()
            }
        }.getOrNull() else null
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("widget-provider-${provider.provider.packageName}")
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.width(116.dp).height(104.dp)
            ) {
                if (preview != null) {
                    Image(preview, contentDescription = "Vista previa de $label", modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                } else {
                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▦", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text("Vista previa no disponible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(provider.provider.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${provider.minWidth} × ${provider.minHeight} dp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                Text("Tocá para agregar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun Drawer(apps: List<AppItem>, close: () -> Unit) {
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
      val categories = remember(apps, q, playCategories, manualCategories) {
        val filtered = apps.filter { it.label.contains(q.trim(), ignoreCase = true) }
        if (q.isNotBlank()) {
            if (filtered.isEmpty()) emptyList() else listOf("Resultados" to filtered.sortedBy { it.label.lowercase(Locale.ROOT) })
        } else listOf("Redes Sociales", "Multimedia", "Juegos", "Google", "Mapas y viajes", "Finanzas", "Compras", "Comida", "Productividad", "Comunicación", "Noticias y lectura", "Herramientas", "Otras").mapNotNull { name ->
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
    Column(
        Modifier.fillMaxSize().background(Color(0xFF171411)).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
            .testTag("app-drawer-surface")
            .pointerInput(drawerListState) {
                var verticalDistance = 0f
                var startedAtTop = false
                var startedAtBottom = false
                detectVerticalDragGestures(
                    onDragStart = {
                        verticalDistance = 0f
                        startedAtTop = drawerListState.firstVisibleItemIndex == 0 && drawerListState.firstVisibleItemScrollOffset == 0
                        val layout = drawerListState.layoutInfo
                        startedAtBottom = layout.totalItemsCount > 0 && layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
                    },
                    onVerticalDrag = { change, amount ->
                        verticalDistance += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (verticalDistance > viewConfiguration.touchSlop * 2f && startedAtTop) close()
                        else if (verticalDistance < -viewConfiguration.touchSlop * 2f && startedAtBottom) close()
                    },
                    onDragCancel = { verticalDistance = 0f }
                )
            }
    ) {

    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Aplicaciones", color = Cream, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("${apps.size} disponibles", color = Cream.copy(alpha = 0.64f), fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            q,
            { q = it },
            singleLine = true,
            placeholder = { Text("Buscar aplicaciones") },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions.Default
        )
        TextButton(
            onClick = { showOrganizer = true },
            modifier = Modifier.testTag("drawer-organize-apps"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) { Text("Organizar", color = Color(0xFFD6AE70), fontWeight = FontWeight.SemiBold) }
    }
    Spacer(Modifier.height(5.dp))
        LazyColumn(state = drawerListState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.testTag("app-drawer-list")) {
            if (q.isNotBlank() && categories.isEmpty()) {
                item(key = "empty-search") {
                    Text("No se encontraron aplicaciones", color = Cream.copy(alpha = 0.72f), modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center)
                }
            }
            categories.forEach { (title, members) ->
                item(key = "category-$title") { DrawerCategoryTitle(title) }
                items(members.chunked(4), key = { row -> "$title-${row.joinToString { it.pkg }}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
                        repeat(4) { column ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                row.getOrNull(column)?.let { app ->
                                    AppIcon(
                                        app, 56.dp, Color.White, onLaunch = close,
                                        onLongPress = close,
                                        dragPayload = app.pkg,
                                        testTag = "drawer-app-${app.pkg}"
                                    )
                                }
                            }
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
