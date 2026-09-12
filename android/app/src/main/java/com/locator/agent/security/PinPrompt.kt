package com.locator.agent.security

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Dialogos de PIN compartidos por la UI. Todo pasa por [PinStore]: aqui no se
 * guarda ni se compara nada por separado.
 */
object PinPrompt {

    /**
     * Ejecuta [onOk] sin pedir nada si no hay PIN definido; si hay PIN, lo pide.
     * Es el punto unico por el que pasan las acciones sensibles (detener,
     * cambiar ajustes, desactivar el modo antirrobo, desvincular).
     */
    fun runGuarded(activity: Activity, title: String, onOk: () -> Unit) {
        val store = PinStore.get(activity)
        if (!store.isSet) {
            onOk()
            return
        }
        ask(activity, title, "Introduce tu PIN para continuar", onOk)
    }

    /** Pide el PIN mostrando el motivo; si acierta, ejecuta [onOk]. */
    fun ask(activity: Activity, title: String, message: String, onOk: () -> Unit) {
        val store = PinStore.get(activity)
        val input = pinField(activity)

        val blocked = store.lockoutRemainingMs()
        if (blocked > 0) {
            AlertDialog.Builder(activity)
                .setTitle("PIN bloqueado")
                .setMessage("Demasiados intentos fallidos. Espera ${(blocked / 1000) + 1} s y vuelve a intentarlo.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Aceptar") { _, _ ->
                val pin = input.text.toString()
                when {
                    !PinStore.isValidFormat(pin) ->
                        toast(activity, "El PIN debe tener 4-8 dígitos")
                    store.verify(pin) -> onOk()
                    else -> {
                        val left = store.lockoutRemainingMs()
                        toast(
                            activity,
                            if (left > 0) "PIN incorrecto. Bloqueado ${(left / 1000) + 1} s"
                            else "PIN incorrecto (${store.failedAttempts} intentos fallidos)"
                        )
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Define un PIN nuevo (o lo cambia) pidiendo el actual si ya existe. */
    fun changeOrSet(activity: Activity, onDone: () -> Unit) {
        val store = PinStore.get(activity)
        if (!store.isSet) {
            create(activity, onDone)
            return
        }
        ask(activity, "Cambiar PIN", "Introduce el PIN actual") {
            create(activity, onDone)
        }
    }

    /** Crea un PIN nuevo con confirmacion (dos campos). */
    fun create(activity: Activity, onDone: () -> Unit) {
        val store = PinStore.get(activity)
        val first = pinField(activity)
        val second = pinField(activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(first)
            addView(second)
        }

        AlertDialog.Builder(activity)
            .setTitle("Definir PIN del propietario")
            .setMessage(
                "El PIN protege las acciones locales del agente: detener el rastreo, " +
                    "cambiar la configuración, desactivar el modo antirrobo y desvincular. " +
                    "No se guarda en claro y no se puede recuperar."
            )
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    !PinStore.isValidFormat(a) -> toast(activity, "El PIN debe tener 4-8 dígitos")
                    a != b -> toast(activity, "Los PIN no coinciden")
                    else -> {
                        store.setPin(a)
                        toast(activity, "PIN guardado")
                        onDone()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Quitar PIN") { _, _ ->
                if (!store.isSet) {
                    toast(activity, "No hay PIN definido")
                    return@setNeutralButton
                }
                ask(activity, "Quitar PIN", "Introduce el PIN actual para eliminarlo") {
                    store.clear()
                    toast(activity, "PIN eliminado: el agente queda sin protección local")
                    onDone()
                }
            }
            .show()
    }

    private fun pinField(activity: Activity): EditText =
        EditText(activity).apply {
            hint = "PIN (4-8 dígitos)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }

    private fun toast(activity: Activity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
