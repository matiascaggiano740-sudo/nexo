package com.nexo.launcher

import android.content.Context
import android.appwidget.AppWidgetManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versioned, portable subset of NEXO's user-owned launcher layout and settings. */
object LauncherBackup {
    private const val VERSION = 1

    data class Restored(
        val pages: List<List<String>>,
        val dock: List<String>,
        val folders: List<Pair<String, List<String>>>,
        val widgetSpecs: JSONArray,
        val city: String,
        val quote: String
    )

    fun export(context: Context, activity: MainActivity?): String {
        val home = context.getSharedPreferences("nexo_home", Context.MODE_PRIVATE)
        val pagesRaw = home.getString("pages", "").orEmpty()
        val pages = if (pagesRaw.isBlank()) listOf(home.getString("packages", "").orEmpty())
            else pagesRaw.split("~")
        val widgets = JSONArray()
        activity?.hostedWidgetIds?.forEach { id ->
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return@forEach
            widgets.put(JSONObject()
                .put("provider", info.provider.flattenToString())
                .put("package", info.provider.packageName)
                .put("page", activity.hostedWidgetPages[id] ?: 0)
                .put("heightDp", activity.widgetHeightDp(id)))
        }
        val categories = context.getSharedPreferences("nexo_app_categories", Context.MODE_PRIVATE)
        val visual = context.getSharedPreferences("nexo", Context.MODE_PRIVATE)
        val catJson = JSONObject()
        categories.all.forEach { (key, value) -> if (value is String) catJson.put(key, value) }
        val folderRaw = home.getString("user_folders", "[]").orEmpty()
        val photoData = portablePhoto(context, visual.getString("photo", null))
            ?: visual.getString("photo_backup_b64", null)
        return JSONObject()
            .put("format", "com.nexo.launcher.backup")
            .put("version", VERSION)
            .put("home", JSONObject()
                .put("pages", JSONArray(pages))
                .put("dock", home.getString("dock_packages", "").orEmpty())
                .put("folders", JSONArray(folderRaw)))
            .put("settings", JSONObject()
                .put("city", visual.getString("city", ""))
                .put("quote", visual.getString("quote", DEFAULT_QUOTE))
                .put("photoJpegBase64", photoData ?: JSONObject.NULL)
                .put("manualCategories", catJson)
                .put("iconTheme", context.getSharedPreferences("nexo_icon_settings", Context.MODE_PRIVATE).getString("theme", "poco")))
            .put("widgets", widgets)
            .put("notes", JSONObject()
                .put("wallpaper", "System wallpaper is not included; Android manages it outside NEXO.")
                .put("widgets", "Widget provider, page and height are recorded. Android widget IDs/configuration cannot be transferred; widgets without a matching active instance must be added and configured again."))
            .toString(2)
    }

    @Throws(JSONException::class)
    fun parse(text: String): JSONObject {
        require(text.length <= 2_000_000) { "El archivo de respaldo es demasiado grande." }
        val root = JSONObject(text)
        require(root.optString("format") == "com.nexo.launcher.backup") { "Este archivo no es un respaldo de NEXO." }
        require(root.optInt("version", -1) == VERSION) { "La versión de este respaldo no es compatible." }
        val home = root.getJSONObject("home")
        val pages = home.getJSONArray("pages")
        require(pages.length() in 1..50) { "El respaldo no tiene páginas válidas." }
        for (index in 0 until pages.length()) {
            val page = pages.getString(index)
            require(page.split('|').size <= 28 && page.length <= 8192) { "Una página del respaldo no es válida." }
        }
        require(home.getJSONArray("folders").length() <= 200) { "El respaldo tiene demasiadas carpetas." }
        val photo = root.optJSONObject("settings")?.optString("photoJpegBase64", "") ?: ""
        require(photo.length <= 1_700_000) { "La imagen del respaldo supera el tamaño permitido." }
        return root
    }

    fun read(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri) ?: error("No se pudo leer el archivo")
        return stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 2_000_000) { "El archivo de respaldo es demasiado grande." }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    fun restore(context: Context, root: JSONObject): Restored {
        val homeData = root.getJSONObject("home")
        val pagesJson = homeData.getJSONArray("pages")
        val pages = (0 until pagesJson.length()).map { i ->
            val raw = pagesJson.getString(i)
            raw.split('|')
        }
        val savedDock = homeData.optString("dock", "")
        val values = if (savedDock.isBlank()) emptyList() else savedDock.split('|').map { it.ifBlank { "-" } }
        val dock = values.take(4) + List((4 - values.size).coerceAtLeast(0)) { "-" }
        val foldersJson = homeData.getJSONArray("folders")
        val folders = (0 until foldersJson.length()).mapNotNull { i ->
            val obj = foldersJson.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            val pkgs = obj.optJSONArray("packages") ?: JSONArray()
            val list = (0 until pkgs.length()).mapNotNull { j -> pkgs.optString(j).takeIf(String::isNotBlank) }
            if (name.isBlank() || list.isEmpty()) null else name to list
        }
        val pagesRaw = pages.joinToString("~") { it.joinToString("|") }
        val foldersRaw = JSONArray().also { arr -> folders.forEach { (name, pkgs) ->
            arr.put(JSONObject().put("name", name).put("packages", JSONArray(pkgs)))
        } }.toString()
        context.getSharedPreferences("nexo_home", Context.MODE_PRIVATE).edit()
            .putString("pages", pagesRaw)
            .putString("packages", pages.firstOrNull().orEmpty().joinToString("|"))
            .putString("dock_packages", dock.joinToString("|"))
            .putString("user_folders", foldersRaw).apply()

        val settings = root.optJSONObject("settings") ?: JSONObject()
        val city = settings.optString("city", "")
        val quote = settings.optString("quote", DEFAULT_QUOTE)
        val photoData = settings.optString("photoJpegBase64", "").takeIf { it.isNotBlank() && it != "null" }
        context.getSharedPreferences("nexo", Context.MODE_PRIVATE).edit()
            .putString("city", city)
            .putString("quote", quote)
            .putString("photo", "")
            .putString("photo_backup_b64", photoData).apply()
        val categories = context.getSharedPreferences("nexo_app_categories", Context.MODE_PRIVATE)
        val catEditor = categories.edit().clear()
        val categoryJson = settings.optJSONObject("manualCategories") ?: JSONObject()
        categoryJson.keys().forEach { key -> categoryJson.optString(key).takeIf(String::isNotBlank)?.let { catEditor.putString(key, it) } }
        catEditor.apply()
        val theme = settings.optString("iconTheme", "poco")
        context.getSharedPreferences("nexo_icon_settings", Context.MODE_PRIVATE).edit().putString("theme", theme).apply()

        return Restored(pages, dock, folders, root.optJSONArray("widgets") ?: JSONArray(), city, quote)
    }

    private fun portablePhoto(context: Context, uriText: String?): String? {
        if (uriText.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriText)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            val bytes = output.toByteArray()
            if (bytes.size > 1_200_000) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }
}
