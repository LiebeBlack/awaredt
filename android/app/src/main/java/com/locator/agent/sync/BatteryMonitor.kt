package com.locator.agent.sync

import android.content.Context
import android.os.BatteryManager

data class BatteryInfo(val pct: Int?, val charging: Boolean?)

class BatteryMonitor(private val context: Context) {

    fun read(): BatteryInfo {
        val bm = context.getSystemService(BatteryManager::class.java)
            ?: return BatteryInfo(null, null)
        // Porcentaje directo del servicio de bateria (0-100; MIN_VALUE si no disponible)
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val pct = cap.takeIf { it in 1..100 }
        // isCharging cubre AC/USB/inalambrico (API 23+, minSdk 26)
        val charging = BatteryManager.isCharging(context)
        return BatteryInfo(pct, charging)
    }

    /** Umbral de ahorro: bajo 20% y sin cargador se ralentiza el reporte. */
    fun isLowPower(info: BatteryInfo = read()): Boolean =
        (info.pct ?: 100) <= 20 && info.charging != true
}
