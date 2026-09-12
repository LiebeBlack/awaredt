package com.locator.agent.sync

import com.locator.agent.data.LocationEntity
import org.json.JSONObject

/**
 * Fix listo para enviar. Separa el dominio de la persistencia.
 */
data class PreciseFix(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Double?,
    val speed: Double?,
    val altitude: Double?,
    val bearing: Double?,
    val batteryPct: Int?,
    val charging: Boolean?,
    val source: String,
    val provider: String,
    val cellWifi: String?,
    val recordedAt: Long
)

fun LocationEntity.toFix(): PreciseFix = PreciseFix(
    id = id, lat = lat, lon = lon, accuracy = accuracy, speed = speed,
    altitude = altitude, bearing = bearing, batteryPct = batteryPct,
    charging = charging, source = source, provider = provider,
    cellWifi = cellWifi, recordedAt = recordedAt
)

fun PreciseFix.toJson(): JSONObject = JSONObject().apply {
    put("lat", lat)
    put("lon", lon)
    accuracy?.let { put("accuracy", it) }
    speed?.let { put("speed", it) }
    altitude?.let { put("altitude", it) }
    bearing?.let { put("bearing", it) }
    batteryPct?.let { put("battery_pct", it) }
    charging?.let { put("charging", it) }
    put("source", source)
    put("provider", provider)
    if (!cellWifi.isNullOrBlank()) put("cell", JSONObject(cellWifi))
    put("recorded_at", formatIso(recordedAt))
}

/** epoch millis -> ISO-8601 UTC (sin dependencias de java.time para API 26+) */
fun formatIso(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochMillis
    }
    val y = cal.get(java.util.Calendar.YEAR)
    val mo = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val mi = cal.get(java.util.Calendar.MINUTE)
    val s = cal.get(java.util.Calendar.SECOND)
    val ms = cal.get(java.util.Calendar.MILLISECOND)
    return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(y, mo, d, h, mi, s, ms)
}
