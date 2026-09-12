package com.locator.agent.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.locator.agent.R

/**
 * Receptor del administrador de dispositivos: da al agente la capacidad de
 * BLOQUEAR LA PANTALLA de forma remota (util cuando el telefono se pierde).
 *
 * Que NO hace (por diseno):
 *  - No usa `wipe-data` ni `reset-password`: un borrado remoto es irreversible
 *    y un fallo de credenciales del panel no puede permitirselo.
 *  - No sirve para ocultar la app: el icono sigue en el lanzador y la
 *    notificacion permanente sigue visible.
 *
 * Efecto secundario que SI es util contra robo: con el admin activo, Android
 * exige DESACTIVARLO antes de poder desinstalar la app, y esa desactivacion
 * pide confirmacion en Ajustes. Es el mecanismo oficial del sistema, no un
 * truco de persistencia.
 */
class AgentDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Administrador de dispositivos activado por el usuario")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Administrador de dispositivos desactivado: la app ya se puede desinstalar")
    }

    // OJO: la firma del framework es `@Nullable CharSequence`; devolver un tipo
    // no anulable aqui NO compila.
    @Suppress("DEPRECATION")
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? =
        context.getString(R.string.admin_disable_warning)

    companion object { private const val TAG = "AgentDeviceAdmin" }
}

/** Acceso al DevicePolicyManager con manejo defensivo (nunca lanza al llamante). */
object DeviceAdmin {

    fun component(context: Context): ComponentName =
        ComponentName(context.applicationContext, AgentDeviceAdminReceiver::class.java)

    private fun manager(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    fun isActive(context: Context): Boolean =
        try {
            manager(context)?.isAdminActive(component(context)) == true
        } catch (t: Throwable) {
            Log.w("DeviceAdmin", "No se pudo consultar el admin", t)
            false
        }

    /** Intent que abre el dialogo oficial de Android para activar el admin. */
    fun requestIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.admin_enable_explanation)
            )

    fun deactivate(context: Context) {
        runCatching { manager(context)?.removeActiveAdmin(component(context)) }
            .onFailure { Log.w("DeviceAdmin", "No se pudo desactivar el admin", it) }
    }

    /**
     * Bloquea la pantalla ahora. Devuelve false si el admin no esta activo o si
     * el sistema lo rechaza: el comando remoto se reporta como fallido en vez
     * de fallar en silencio.
     */
    fun lockNow(context: Context): Boolean {
        val dpm = manager(context) ?: return false
        if (!isActive(context)) return false
        return try {
            dpm.lockNow()
            true
        } catch (t: Throwable) {
            Log.w("DeviceAdmin", "lockNow rechazado", t)
            false
        }
    }
}
