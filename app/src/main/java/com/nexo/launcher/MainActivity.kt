package com.nexo.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private lateinit var widgetHost: AppWidgetHost
    val hostedWidgetIds = mutableStateListOf<Int>()
    val hostedWidgetPages = mutableStateMapOf<Int, Int>()
    val hostedWidgetHeights = mutableStateMapOf<Int, Int>()
    val homeCellHeightDp = androidx.compose.runtime.mutableFloatStateOf(72f)
    val homeCellHeightMeasured = androidx.compose.runtime.mutableStateOf(false)
    val availableWidgetProviders = mutableStateListOf<AppWidgetProviderInfo>()
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetPage: Int = 0
    private var pendingWidgetAppCells: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            pendingWidgetId = state.getInt(STATE_PENDING_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            pendingWidgetPage = state.getInt(STATE_PENDING_WIDGET_PAGE, 0)
            pendingWidgetAppCells = state.getInt(STATE_PENDING_WIDGET_APP_CELLS, 0)
        }
        enableEdgeToEdge()
        applyWallpaperSurface(intent)
        widgetHost = AppWidgetHost(this, HOST_ID)
        val widgetPrefs = getSharedPreferences("nexo_widgets", MODE_PRIVATE)
        val storedWidgetIds = widgetPrefs.getString("ids", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
        val storedWidgetPages = widgetPrefs.getString("pages", "").orEmpty().split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) null else parts[0].toIntOrNull()?.let { id -> parts[1].toIntOrNull()?.let { id to it } }
        }.toMap()
        val manager = AppWidgetManager.getInstance(this)
        storedWidgetIds.forEach { id ->
            if (manager.getAppWidgetInfo(id) == null) {
                widgetHost.deleteAppWidgetId(id)
                widgetPrefs.edit().remove("height_$id").apply()
            } else {
                hostedWidgetIds.add(id)
                hostedWidgetPages[id] = storedWidgetPages[id] ?: 0
                widgetPrefs.getInt("height_$id", -1).takeIf { it > 0 }?.let { hostedWidgetHeights[id] = it }
            }
        }
        widgetPrefs.edit().putString("ids", hostedWidgetIds.joinToString(","))
            .putString("pages", hostedWidgetPages.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
        setContent { MaterialTheme { Home() } }
    }

    /** Android paints the live/current system wallpaper behind a real Home activity. */
    private fun applyWallpaperSurface(source: Intent?) {
        val isHomeLaunch = source?.action == Intent.ACTION_MAIN &&
            source.categories?.contains(Intent.CATEGORY_HOME) == true
        if (isHomeLaunch) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            // An app-icon launch must be opaque: otherwise the old launcher's icons show through.
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(18, 17, 15)))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWallpaperSurface(intent)
    }

    override fun onStart() {
        super.onStart()
        if (::widgetHost.isInitialized) widgetHost.startListening()
    }

    override fun onStop() {
        if (::widgetHost.isInitialized) widgetHost.stopListening()
        super.onStop()
    }

    private fun lockAdminComponent() = ComponentName(this, NexoDeviceAdminReceiver::class.java)

    fun isLockAdminActive(): Boolean =
        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(lockAdminComponent())

    fun requestLockAdminActivation() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, lockAdminComponent())
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "NEXO solicita permiso de administrador solo para bloquear la pantalla cuando uses el doble toque. Podés cancelarlo o revocarlo desde Ajustes.")
        startActivity(intent)
    }

    fun lockDeviceNow() {
        if (!isLockAdminActive()) return
        runCatching { (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow() }
    }

    fun requestDefaultHomeRole() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                runCatching { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE) }
                    .onFailure { openHomeSettings() }
                return
            }
        }
        openHomeSettings()
    }

    private fun openHomeSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { Toast.makeText(this, "No se pudieron abrir las opciones de inicio de Android", Toast.LENGTH_LONG).show() }
    }

    fun launchWidgetPicker(page: Int, appCells: Int) {
        val usedRows = hostedWidgetIds.filter { hostedWidgetPages[it] == page }
            .sumOf { id -> widgetRowsForHeight(widgetHeightDp(id), homeCellHeightDp.floatValue) }
        if (usedRows * 4 + appCells + 4 > 28) {
            Toast.makeText(this, "Esta página ya no tiene cuatro celdas libres para un widget", Toast.LENGTH_SHORT).show()
            return
        }
        pendingWidgetPage = page
        pendingWidgetAppCells = appCells
        val manager = AppWidgetManager.getInstance(this)
        availableWidgetProviders.clear()
        availableWidgetProviders.addAll(
            runCatching { manager.installedProviders }
                .getOrDefault(emptyList())
                .sortedBy { provider ->
                    runCatching { provider.loadLabel(packageManager).toString() }
                        .getOrDefault(provider.provider.className)
                        .lowercase(java.util.Locale.ROOT)
                }
        )
        if (availableWidgetProviders.isEmpty()) {
            Toast.makeText(this, "Android no informó widgets disponibles", Toast.LENGTH_LONG).show()
        }
    }

    fun cancelWidgetPicker() {
        availableWidgetProviders.clear()
    }

    fun selectWidgetProvider(info: AppWidgetProviderInfo) {
        if (info !in availableWidgetProviders) return
        availableWidgetProviders.clear()
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val manager = AppWidgetManager.getInstance(this)
        val allowed = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (allowed) {
            launchConfiguration(id)
            return
        }
        val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        runCatching { startActivityForResult(bind, REQUEST_BIND) }.onFailure {
            widgetHost.deleteAppWidgetId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            Toast.makeText(this, "Android no pudo abrir la autorización de este widget", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            ?: pendingWidgetId
        if (requestCode !in setOf(REQUEST_BIND, REQUEST_CONFIGURE)) return
        if (resultCode != Activity.RESULT_OK || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(pendingWidgetId)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            if (requestCode == REQUEST_CONFIGURE && resultCode != Activity.RESULT_OK) {
                Toast.makeText(this, "No se agregó el widget porque se canceló su configuración", Toast.LENGTH_SHORT).show()
            }
            return
        }
        when (requestCode) {
            REQUEST_BIND -> launchConfiguration(id)
            REQUEST_CONFIGURE -> persistWidget(id)
        }
    }

    private fun launchConfiguration(id: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        val configure = info?.configure
        if (configure == null) persistWidget(id)
        else runCatching {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), REQUEST_CONFIGURE)
        }.onFailure {
            widgetHost.deleteAppWidgetId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            Toast.makeText(this, "No se pudo abrir la configuración. El widget no se agregó", Toast.LENGTH_LONG).show()
        }
    }

    private fun persistWidget(id: Int) {
        val page = pendingWidgetPage
        val widgetInfo = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (widgetInfo == null) {
            widgetHost.deleteAppWidgetId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            Toast.makeText(this, "Android ya no encuentra ese widget; elegí otro", Toast.LENGTH_LONG).show()
            return
        }
        val addedRows = widgetRowsForHeight(widgetInfo.minHeight.coerceAtLeast(96), homeCellHeightDp.floatValue)
        val existingRows = hostedWidgetIds.filter { hostedWidgetPages[it] == page }
            .sumOf { widgetHeightDp(it).let { height -> widgetRowsForHeight(height, homeCellHeightDp.floatValue) } }
        if ((existingRows + addedRows) * 4 + pendingWidgetAppCells > 28) {
            Toast.makeText(this, "El widget no entra en las celdas libres de esta página", Toast.LENGTH_LONG).show()
            widgetHost.deleteAppWidgetId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return
        }
        if (id !in hostedWidgetIds) hostedWidgetIds.add(id)
        hostedWidgetPages[id] = pendingWidgetPage
        persistWidgetState()
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    fun moveWidgetToPage(id: Int, targetPage: Int, totalPages: Int, appCells: Int) {
        if (targetPage !in 0 until totalPages || id !in hostedWidgetIds) return
        val targetRows = hostedWidgetIds.filter { hostedWidgetPages[it] == targetPage && it != id }
            .sumOf { widgetRowsForHeight(widgetHeightDp(it), homeCellHeightDp.floatValue) }
        val neededRows = widgetRowsForHeight(widgetHeightDp(id), homeCellHeightDp.floatValue)
        if ((hostedWidgetPages[id] ?: 0) != targetPage && (targetRows + neededRows) * 4 + appCells > 28) {
            Toast.makeText(this, "No hay espacio libre para ese widget en la página", Toast.LENGTH_SHORT).show()
            return
        }
        hostedWidgetPages[id] = targetPage
        persistWidgetState()
    }

    fun reorderWidget(id: Int, direction: Int) {
        val current = hostedWidgetIds.indexOf(id)
        if (current < 0) return
        val page = hostedWidgetPages[id] ?: 0
        val candidates = hostedWidgetIds.withIndex().filter { hostedWidgetPages[it.value] == page }.map { it.index }
        val inPage = candidates.indexOf(current)
        val targetPageIndex = inPage + direction
        if (targetPageIndex !in candidates.indices) return
        val target = candidates[targetPageIndex]
        val moving = hostedWidgetIds.removeAt(current)
        hostedWidgetIds.add(target.coerceAtMost(hostedWidgetIds.size), moving)
        persistWidgetState()
    }

    fun moveWidgetPages(from: Int, to: Int) {
        hostedWidgetPages.keys.toList().forEach { id ->
            val page = hostedWidgetPages[id] ?: return@forEach
            hostedWidgetPages[id] = when {
                page == from -> to
                from < to && page in (from + 1)..to -> page - 1
                from > to && page in to until from -> page + 1
                else -> page
            }
        }
        persistWidgetState()
    }

    fun deleteWidgetPage(page: Int) {
        hostedWidgetPages.keys.toList().forEach { id ->
            val old = hostedWidgetPages[id] ?: return@forEach
            if (old > page) hostedWidgetPages[id] = old - 1
        }
        persistWidgetState()
    }

    private fun persistWidgetState() {
        val editor = getSharedPreferences("nexo_widgets", MODE_PRIVATE).edit()
            .putString("ids", hostedWidgetIds.joinToString(","))
            .putString("pages", hostedWidgetPages.entries.joinToString(",") { "${it.key}:${it.value}" })
        hostedWidgetHeights.forEach { (id, height) -> editor.putInt("height_$id", height) }
        editor.apply()
    }

    fun removeWidget(id: Int) {
        hostedWidgetIds.remove(id)
        hostedWidgetPages.remove(id)
        hostedWidgetHeights.remove(id)
        getSharedPreferences("nexo_widgets", MODE_PRIVATE).edit().remove("height_$id").apply()
        widgetHost.deleteAppWidgetId(id)
        persistWidgetState()
    }

    fun widgetHeightDp(id: Int): Int = hostedWidgetHeights[id]
        ?: AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.minHeight?.coerceAtLeast(96) ?: 132

    /** Restores metadata only for widget instances that still exist; never fabricates widget IDs. */
    fun restoreWidgetPlacements(specs: JSONArray, pageCount: Int): Triple<Int, Int, Int> {
        val manager = AppWidgetManager.getInstance(this)
        val used = mutableSetOf<Int>()
        val restoredOrder = mutableListOf<Int>()
        var restored = 0
        var needsReAdd = 0
        for (index in 0 until specs.length()) {
            val spec = specs.optJSONObject(index) ?: continue
            val provider = spec.optString("provider")
            val pkg = spec.optString("package")
            val match = hostedWidgetIds.firstOrNull { id ->
                id !in used && manager.getAppWidgetInfo(id)?.let { info ->
                    info.provider.flattenToString() == provider || (provider.isBlank() && info.provider.packageName == pkg)
                } == true
            }
            if (match == null) {
                needsReAdd++
                continue
            }
            used += match
            restoredOrder += match
            hostedWidgetPages[match] = spec.optInt("page", 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val info = manager.getAppWidgetInfo(match)
            if (info != null && spec.has("heightDp")) {
                val minimum = info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight.coerceAtLeast(96)
                val maximum = info.maxResizeHeight.takeIf { it > 0 } ?: 700
                val height = spec.optInt("heightDp", minimum).coerceIn(minimum, maxOf(minimum, maximum))
                hostedWidgetHeights[match] = height
                val allocatedHeight = widgetSpanHeightDp(
                    widgetRowsForHeight(height, homeCellHeightDp.floatValue),
                    homeCellHeightDp.floatValue
                ).toInt()
                manager.updateAppWidgetOptions(match, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, allocatedHeight)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, allocatedHeight)
                })
            }
            restored++
        }
        val removedIds = hostedWidgetIds.filterNot { it in used }.toList()
        removedIds.forEach { id ->
            hostedWidgetPages.remove(id)
            hostedWidgetHeights.remove(id)
            widgetHost.deleteAppWidgetId(id)
            getSharedPreferences("nexo_widgets", MODE_PRIVATE).edit().remove("height_$id").apply()
        }
        hostedWidgetIds.clear()
        hostedWidgetIds.addAll(restoredOrder)
        persistWidgetState()
        return Triple(restored, needsReAdd, removedIds.size)
    }

    fun canResizeWidget(id: Int): Boolean {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return false
        return info.minResizeHeight > 0 || info.maxResizeHeight > 0
    }

    fun resizeWidget(id: Int, deltaDp: Int, appCells: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        if (!canResizeWidget(id)) return
        val page = hostedWidgetPages[id] ?: 0
        val pageRows = hostedWidgetIds.filter { hostedWidgetPages[it] == page && it != id }
            .sumOf { other -> widgetRowsForHeight(widgetHeightDp(other), homeCellHeightDp.floatValue) }
        val minHeight = info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight.coerceAtLeast(90)
        if (pageRows * 4 + appCells + widgetRowsForHeight(minHeight, homeCellHeightDp.floatValue) * 4 > 28) return
        val pageMaxRows = ((28 - appCells) / 4 - pageRows).coerceAtLeast(1)
        val pageMaxHeight = widgetSpanHeightDp(pageMaxRows, homeCellHeightDp.floatValue).toInt()
        val declaredMaxHeight = info.maxResizeHeight.takeIf { it > 0 } ?: 700
        val maxHeight = minOf(declaredMaxHeight, pageMaxHeight).coerceAtLeast(minHeight)
        val newHeight = (widgetHeightDp(id) + deltaDp).coerceIn(minHeight, maxHeight)
        if (pageRows * 4 + appCells + widgetRowsForHeight(newHeight, homeCellHeightDp.floatValue) * 4 > 28) {
            Toast.makeText(this, "No hay suficientes celdas libres para agrandar el widget", Toast.LENGTH_SHORT).show()
            return
        }
        hostedWidgetHeights[id] = newHeight
        getSharedPreferences("nexo_widgets", MODE_PRIVATE).edit().putInt("height_$id", newHeight).apply()
        val allocatedHeight = widgetSpanHeightDp(
            widgetRowsForHeight(newHeight, homeCellHeightDp.floatValue),
            homeCellHeightDp.floatValue
        ).toInt()
        AppWidgetManager.getInstance(this).updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, allocatedHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, allocatedHeight)
        })
    }

    fun createWidgetHostView(id: Int): AppWidgetHostView? = runCatching {
        val manager = AppWidgetManager.getInstance(this)
        val info = manager.getAppWidgetInfo(id) ?: return null
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 48
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            val requestedHeight = widgetHeightDp(id)
            val allocatedHeight = widgetSpanHeightDp(
                widgetRowsForHeight(requestedHeight, homeCellHeightDp.floatValue),
                homeCellHeightDp.floatValue
            ).toInt()
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, allocatedHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, allocatedHeight)
        })
        widgetHost.createView(this, id, info).apply { setAppWidget(id, info) }
    }.getOrNull()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PENDING_WIDGET_ID, pendingWidgetId)
        outState.putInt(STATE_PENDING_WIDGET_PAGE, pendingWidgetPage)
        outState.putInt(STATE_PENDING_WIDGET_APP_CELLS, pendingWidgetAppCells)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val HOST_ID = 0x4E45584F
        private const val REQUEST_BIND = 4102
        private const val REQUEST_CONFIGURE = 4103
        private const val REQUEST_HOME_ROLE = 4104
        private const val STATE_PENDING_WIDGET_ID = "nexo.pendingWidgetId"
        private const val STATE_PENDING_WIDGET_PAGE = "nexo.pendingWidgetPage"
        private const val STATE_PENDING_WIDGET_APP_CELLS = "nexo.pendingWidgetAppCells"
    }
}
