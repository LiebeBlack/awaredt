package com.locator.agent.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Una app con nombre legible y paquete. */
data class AppEntry(val label: String, val pkg: String)

/**
 * Lista de aplicaciones INSTALADAS (solo sus nombres), para el control parental.
 *
 *
 * Que incluye: las apps que el usuario ve y abre — juegos, redes sociales y
 * tambien las preinstaladas con icono (YouTube, Maps, Chrome), porque son
 * justamente las que interesan. El filtro real es tener icono de lanzador: los
 * paquetes de sistema sin lanzador (servicios, librerias) no aparecen.
 * Que excluye: este mismo agente.
 *
 * Que NO incluye, a proposito: tiempos de uso, horas de apertura, número de
 * veces que se abre o cualquier patrón temporal. Eso no es "qué tiene
 * instalado", es un perfil de su día, y queda fuera.
 *
 * Como consigue verlas sin el permiso restringido: con un bloque <queries> en
 * el manifiesto pidiendo el intent LAUNCHER. Android 11+ da visibilidad de las
 * apps que coinciden con esa consulta, asi que NO hace falta
 * QUERY_ALL_PACKAGES (el permiso que Google Play restringe a antivirus y
 * gestores de archivos). Es la via compatible con la tienda y con la ley.
 */
object InstalledApps {

    private const val TAG = "InstalledApps"
    private const val MAX_APPS = 300
    private const val MAX_LABEL = 60

    /** Foto de la lista: total encontrado (antes del tope) y las apps enviadas. */
    data class AppList(val total: Int, val apps: List<AppEntry>)

    fun snapshot(context: Context): AppList = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        val all = resolved.asSequence()
            .mapNotNull { info -> toEntry(pm, context.packageName, info) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .toList()

        AppList(total = all.size, apps = all.take(MAX_APPS))
    }.onFailure { Log.w(TAG, "No se pudo listar apps", it) }
        .getOrDefault(AppList(0, emptyList()))

    private fun toEntry(pm: PackageManager, ownPackage: String, info: ResolveInfo): AppEntry? {
        val pkg = info.activityInfo?.packageName ?: return null
        if (pkg == ownPackage) return null

        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null

        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
        return AppEntry(label.take(MAX_LABEL), pkg)
    }

    /** JSON compacto: [{n: "Free Fire", p: "com.dts.freefireth"}, ...] */
    fun toJson(apps: List<AppEntry>): JSONArray = JSONArray().apply {
        apps.forEach { app ->
            put(JSONObject().apply {
                put("n", app.label)
                put("p", app.pkg)
            })
        }
    }
}
