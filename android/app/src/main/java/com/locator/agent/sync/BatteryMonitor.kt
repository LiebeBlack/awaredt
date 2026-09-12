package com.locator.agent.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryInfo(val pct: Int?, val charging: Boolean?)

class BatteryMonitor(private val context: Context) {

    fun read(): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return BatteryInfo(null, null)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(pct, charging)
    }

    /** Umbral de ahorro: bajo 20% y sin cargador se ralentiza el reporte. */
    fun isLowPower(info: BatteryInfo = read()): Boolean =
        (info.pct ?: 100) <= 20 && info.charging != true
}
