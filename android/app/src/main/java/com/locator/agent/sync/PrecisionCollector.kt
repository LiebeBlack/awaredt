package com.locator.agent.sync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Precision+: captura metadatos de celdas y puntos WiFi visibles.
 * Se guarda junto a cada fix (columna cell_wifi) para auditoria.
 * Nunca lanza: cualquier fallo devuelve null y el fix sigue su curso.
 */
class PrecisionCollector(private val context: Context) {

    @SuppressLint("MissingPermission") // el llamador verifica ACCESS_FINE_LOCATION
    fun collect(): String? = runCatching {
        JSONObject()
            .put("cells", collectCells())
            .put("wifi", collectWifi())
            .toString()
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun collectCells(): JSONArray {
        val arr = JSONArray()
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return arr
        val all: List<CellInfo> = tm.allCellInfo.orEmpty().take(12)
        for (cell in all) {
            val o = JSONObject()
            runCatching {
                when (cell) {
                    is CellInfoGsm -> {
                        o.put("rat", "GSM")
                        o.put("cid", cell.cellIdentity.cid.toLong())
                        o.put("dbm", dbmOrNull(cell.cellSignalStrength.dbm))
                    }
                    is CellInfoCdma -> {
                        o.put("rat", "CDMA")
                        o.put("dbm", dbmOrNull(cell.cellSignalStrength.dbm))
                    }
                    is CellInfoLte -> {
                        o.put("rat", "LTE")
                        o.put("ci", cell.cellIdentity.ci.toLong())
                        o.put("pci", cell.cellIdentity.pci)
                        o.put("dbm", dbmOrNull(cell.cellSignalStrength.dbm))
                    }
                    is CellInfoWcdma -> {
                        o.put("rat", "WCDMA")
                        o.put("cid", cell.cellIdentity.cid.toLong())
                        o.put("dbm", dbmOrNull(cell.cellSignalStrength.dbm))
                    }
                    is CellInfoNr -> {
                        o.put("rat", "NR")
                        val identity = cell.cellIdentity as? CellIdentityNr
                        identity?.let {
                            val nci = runCatching { it.nci }.getOrNull()
                            nci?.let { v -> o.put("nci", v) }
                        }
                        o.put("dbm", dbmOrNull(runCatching { cell.cellSignalStrength.dbm }.getOrDefault(Int.MAX_VALUE)))
                    }
                    else -> o.put("rat", "UNKNOWN")
                }
                o.put("registered", cell.isRegistered)
            }
            arr.put(o)
        }
        return arr
    }

    @SuppressLint("MissingPermission")
    private fun collectWifi(): JSONArray {
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: los resultados de escaneo requieren
            // NEARBY_WIFI_DEVICES; sin el, se devuelve lista vacia.
            return arr
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return arr
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty().take(10)
        for (ap in results) {
            arr.put(JSONObject().apply {
                put("bssid", ap.BSSID)
                put("rssi", ap.level)
            })
        }
        return arr
    }

    private fun dbmOrNull(dbm: Int): Any? =
        if (dbm == Int.MAX_VALUE || dbm == -1) null else dbm
}
