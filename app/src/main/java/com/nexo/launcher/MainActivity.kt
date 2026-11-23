package com.nexo.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

class MainActivity : ComponentActivity() {
    private lateinit var widgetHost: AppWidgetHost
    val hostedWidgetIds = mutableStateListOf<Int>()
    val hostedWidgetPages = mutableStateMapOf<Int, Int>()
    val hostedWidgetHeights = mutableStateMapOf<Int, Int>()
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetPage: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetHost = AppWidgetHost(this, HOST_ID)
        val widgetPrefs = getSharedPreferences("nexo_widgets", MODE_PRIVATE)
        hostedWidgetIds.addAll(widgetPrefs.getString("ids", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() })
        widgetPrefs.getString("pages", "").orEmpty().split(",").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0].toIntOrNull()?.let { id -> parts[1].toIntOrNull()?.let { page -> hostedWidgetPages[id] = page } }
        }
        hostedWidgetIds.forEach { id ->
            if (id !in hostedWidgetPages) hostedWidgetPages[id] = 0
            widgetPrefs.getInt("height_$id", -1).takeIf { it > 0 }?.let { hostedWidgetHeights[id] = it }
        }
        widgetHost.startListening()
        setContent { MaterialTheme { Home() } }
    }

    fun launchWidgetPicker(page: Int) {
        val usedRows = hostedWidgetIds.filter { hostedWidgetPages[it] == page }
            .sumOf { id -> ((widgetHeightDp(id) + 99) / 100).coerceAtLeast(1) }
        if (usedRows >= 7) {
            Toast.makeText(this, "Esta página ya no tiene espacio para otro widget", Toast.LENGTH_SHORT).show()
            return
        }
        pendingWidgetPage = page
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        runCatching { startActivityForResult(intent, REQUEST_PICK) }.onFailure { widgetHost.deleteAppWidgetId(id) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            ?: pendingWidgetId
        if (requestCode !in setOf(REQUEST_PICK, REQUEST_BIND, REQUEST_CONFIGURE)) return
        if (resultCode != Activity.RESULT_OK || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(pendingWidgetId)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return
        }
        when (requestCode) {
            REQUEST_PICK -> {
                val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
                if (info == null) { widgetHost.deleteAppWidgetId(id); return }
                val allowed = AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id, info.provider)
                if (allowed) launchConfiguration(id)
                else {
                    val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    startActivityForResult(bind, REQUEST_BIND)
                }
            }
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
        }.onFailure { persistWidget(id) }
    }

    private fun persistWidget(id: Int) {
        val page = pendingWidgetPage
        val addedRows = ((AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.minHeight ?: 132) + 99) / 100
        val existingRows = hostedWidgetIds.filter { hostedWidgetPages[it] == page }
            .sumOf { widgetHeightDp(it).let { height -> (height + 99) / 100 } }
        if (existingRows + addedRows > 7) {
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
        ?: AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.minHeight?.coerceIn(96, 320) ?: 132

    fun resizeWidget(id: Int, deltaDp: Int) {
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        val newHeight = (widgetHeightDp(id) + deltaDp).coerceIn(info.minHeight.coerceAtLeast(90), info.maxResizeHeight.takeIf { it > 0 } ?: 360)
        hostedWidgetHeights[id] = newHeight
        getSharedPreferences("nexo_widgets", MODE_PRIVATE).edit().putInt("height_$id", newHeight).apply()
        AppWidgetManager.getInstance(this).updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, newHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, newHeight)
        })
    }

    fun createWidgetHostView(id: Int): AppWidgetHostView? = runCatching {
        val manager = AppWidgetManager.getInstance(this)
        val info = manager.getAppWidgetInfo(id) ?: return null
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 48
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, info.minHeight.coerceAtLeast(96))
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, info.minHeight.coerceAtLeast(96))
        })
        widgetHost.createView(this, id, info).apply { setAppWidget(id, info) }
    }.getOrNull()

    override fun onDestroy() {
        if (::widgetHost.isInitialized) widgetHost.stopListening()
        super.onDestroy()
    }

    companion object {
        private const val HOST_ID = 0x4E45584F
        private const val REQUEST_PICK = 4101
        private const val REQUEST_BIND = 4102
        private const val REQUEST_CONFIGURE = 4103
    }
}
