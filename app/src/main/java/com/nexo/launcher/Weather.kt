package com.nexo.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class WeatherInfo(val city: String, val temp: Int, val label: String)

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

fun loadWeather(city: String): WeatherInfo? = runCatching {
    val geo = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=es"))
    val r = geo.getJSONArray("results").getJSONObject(0)
    val lat = r.getDouble("latitude")
    val lon = r.getDouble("longitude")
    val w = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"))
    val cur = w.getJSONObject("current")
    WeatherInfo(r.getString("name"), Math.round(cur.getDouble("temperature_2m")).toInt(), describe(cur.getInt("weather_code")))
}.getOrNull()

private fun describe(code: Int): String = when (code) {
    0 -> "Despejado"
    1, 2 -> "Parcialmente nublado"
    3 -> "Nublado"
    45, 48 -> "Niebla"
    in 51..57 -> "Llovizna"
    in 61..67 -> "Lluvia"
    in 71..77 -> "Nieve"
    in 80..82 -> "Chubascos"
    in 95..99 -> "Tormenta"
    else -> "Variable"
}
