package com.locator.agent

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locator.agent.admin.DeviceAdmin
import com.locator.agent.data.AppDatabase
import com.locator.agent.data.SettingsRepository
import com.locator.agent.security.PinPrompt
import com.locator.agent.security.PinStore
import com.locator.agent.sync.AppVisibility
import com.locator.agent.sync.EventReporter
import com.locator.agent.sync.LocationService
import com.locator.agent.sync.ServiceStateHolder
import com.locator.agent.sync.SupabaseClient
import com.locator.agent.sync.SyncManager
import com.locator.agent.sync.SyncWorker
import com.locator.agent.sync.TamperCheck
import com.locator.agent.sync.WatchdogReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var pins: PinStore

    /** Aplica el tema elegido (sistema/claro/oscuro) ANTES de inflar la vista. */
    private fun applySavedTheme() {
        val mode = settings.themeMode
        if (mode == 1) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else if (mode == 2) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /** Marca el chip del tema activo para que se vea cual esta seleccionado. */
    private fun updateThemeButtons() {
        val mode = settings.themeMode
        val active = when (mode) {
            1 -> R.id.btnThemeLight
            2 -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }
        listOf(R.id.btnThemeSystem, R.id.btnThemeLight, R.id.btnThemeDark).forEach { id ->
            val b = findViewById<Button>(id)
            val base = getString(
                when (id) {
                    R.id.btnThemeSystem -> R.string.theme_system
                    R.id.btnThemeLight -> R.string.theme_light
                    else -> R.string.theme_dark
                }
            )
            b.text = if (id == active) "\u2713 $base" else base
            b.alpha = if (id == active) 1f else 0.6f
        }
    }

    private lateinit var statusText: TextView
    private lateinit var securityText: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnBattery: Button
    private lateinit var btnPin: Button
    private lateinit var btnAntiTheft: Button
    private lateinit var btnUnpair: Button
    private lateinit var btnSos: Button
    private lateinit var btnCheckin: Button
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etToken: EditText
    private lateinit var spInterval: Spinner
    private lateinit var cbPrecision: CheckBox
    private lateinit var cbAdaptive: CheckBox
    private lateinit var cbDiscreet: CheckBox
    private lateinit var cbRemote: CheckBox
    private lateinit var cbSmart: CheckBox
    private lateinit var cbShareApps: CheckBox

    private val intervalOptions = listOf(5, 10, 30, 60, 300)
    private var waitingForPermissions = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine) {
                requestBackgroundIfNeeded()
                if (waitingForPermissions) {
                    waitingForPermissions = false
                    startTracking()
                }
            } else {
                Toast.makeText(this, "Se necesita permiso de ubicación", Toast.LENGTH_LONG).show()
            }
        }

    /** Al volver del diálogo oficial de DeviceAdmin se refresca el estado. */
    private val adminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Se anota la INTENCION del dueño: si dentro de un rato aparece
            // desactivado, es manipulacion y el panel lo vera con hora y motivo.
            settings.antiTheftEnabled = DeviceAdmin.isActive(this)
            refreshSecurity()
            reportHealth()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.get(this)
        pins = PinStore.get(this)
        applySavedTheme()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        securityText = findViewById(R.id.securityText)
        btnToggle = findViewById(R.id.btnToggle)
        btnBattery = findViewById(R.id.btnBattery)
        btnPin = findViewById(R.id.btnPin)
        btnAntiTheft = findViewById(R.id.btnAntiTheft)
        btnUnpair = findViewById(R.id.btnUnpair)
        btnSos = findViewById(R.id.btnSos)
        btnCheckin = findViewById(R.id.btnCheckin)
        etUrl = findViewById(R.id.etSupabaseUrl)
        etKey = findViewById(R.id.etSupabaseKey)
        etDeviceId = findViewById(R.id.etDeviceId)
        etToken = findViewById(R.id.etDeviceToken)
        spInterval = findViewById(R.id.spInterval)
        cbPrecision = findViewById(R.id.cbPrecision)
        cbAdaptive = findViewById(R.id.cbAdaptive)
        cbDiscreet = findViewById(R.id.cbDiscreet)
        cbRemote = findViewById(R.id.cbRemote)
        cbSmart = findViewById(R.id.cbSmart)
        cbShareApps = findViewById(R.id.cbShareApps)

        spInterval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervalOptions.map { "$it s" }
        )

        loadSettings()
        refreshSecurity()

        btnToggle.setOnClickListener {
            if (LocationService.isRunning) requestStop() else onToggleClicked()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        btnBattery.setOnClickListener { requestBatteryExemption() }
        btnPin.setOnClickListener {
            PinPrompt.changeOrSet(this) { refreshSecurity() }
        }
        btnAntiTheft.setOnClickListener { toggleAntiTheft() }
        btnUnpair.setOnClickListener { confirmUnpair() }
        btnCheckin.setOnClickListener {
            EventReporter.sendCheckin(this)
            Toast.makeText(this, "Aviso enviado: llegué bien", Toast.LENGTH_SHORT).show()
        }
        btnSos.setOnClickListener { confirmSos() }

        // Apariencia: sistema / claro / oscuro, persistido en los ajustes
        findViewById<Button>(R.id.btnThemeSystem).setOnClickListener {
            settings.themeMode = 0
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            updateThemeButtons()
        }
        findViewById<Button>(R.id.btnThemeLight).setOnClickListener {
            settings.themeMode = 1
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            updateThemeButtons()
        }
        findViewById<Button>(R.id.btnThemeDark).setOnClickListener {
            settings.themeMode = 2
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            updateThemeButtons()
        }
        updateThemeButtons()

        observeState()
        handleIntent(intent)
        maybeAutoResume()
        maybeShowDisclosure()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Hay pantalla visible: es el unico momento en el que Android 14 permite
        // que el servicio en primer plano tenga el tipo `location`. Se promociona
        // ahora para que, tras un reinicio, recupere el GPS sin tocar nada mas.
        AppVisibility.set(true)
        if (LocationService.isRunning && settings.trackingEnabled) {
            LocationService.start(this)
        }
        // Abrir la app tambien es una comprobacion: si alguien ha tocado
        // permisos o el modo antirrobo, se sube ahora (sin esperar al worker).
        refreshSecurity()
        reportHealth()
    }

    override fun onStop() {
        AppVisibility.set(false)
        super.onStop()
    }

    private fun reportHealth() {
        if (!settings.pairingComplete) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TamperCheck.report(this@MainActivity) }
        }
    }

    /**
     * Accion "Detener" de la notificacion: llega como intent con
     * [LocationService.ACTION_STOP_REQUEST]. Si hay PIN, se pide antes de parar.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != LocationService.ACTION_STOP_REQUEST) return
        requestStop()
    }

    // ------------------------------------------------------------- seguridad

    private fun refreshSecurity() {
        val pin = if (pins.isSet) "PIN definido" else "sin PIN"
        val admin = if (DeviceAdmin.isActive(this)) "modo antirrobo activo" else "modo antirrobo inactivo"
        val remote = if (settings.remoteControl) "control remoto activo" else "control remoto apagado"
        val tamper = TamperCheck.summary(this)
        securityText.text = "$pin · $admin · $remote\n$tamper"
        btnAntiTheft.text =
            if (DeviceAdmin.isActive(this)) getString(R.string.btn_anti_theft_off)
            else getString(R.string.btn_anti_theft_on)
    }

    /**
     * Modo antirrobo: DeviceAdmin con la unica politica force-lock. Android
     * exige desactivarlo antes de desinstalar, y permite bloquear la pantalla
     * en remoto. Desactivarlo pide PIN si hay uno definido.
     */
    private fun toggleAntiTheft() {
        if (DeviceAdmin.isActive(this)) {
            PinPrompt.runGuarded(this, "Desactivar modo antirrobo") {
                DeviceAdmin.deactivate(this)
                settings.antiTheftEnabled = false // apagado a proposito: no es manipulacion
                Toast.makeText(this, "Modo antirrobo desactivado", Toast.LENGTH_SHORT).show()
                refreshSecurity()
                reportHealth()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Activar modo antirrobo")
            .setMessage(
                "Sirve para dos cosas:\n\n" +
                    "• Bloquear la pantalla desde el panel si pierdes el teléfono.\n" +
                    "• Que Android pida desactivar esta protección antes de poder " +
                    "desinstalar la app.\n\n" +
                    "Lo que NO cambia: el icono sigue en el lanzador, la notificación " +
                    "permanente sigue visible y no se habilita ningún borrado remoto."
            )
            .setPositiveButton("Abrir ajustes de Android") { _, _ ->
                adminLauncher.launch(DeviceAdmin.requestIntent(this))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Divulgación: se muestra una vez (y de nuevo si cambia de version) antes
     * de empezar a rastrear. No es un adorno legal: es el unico momento en el
     * que el usuario del telefono se entera de que va a reportar ubicacion.
     */
    private fun maybeShowDisclosure() {
        if (settings.consentAcceptedAt > 0 &&
            settings.consentVersion == SettingsRepository.CONSENT_VERSION
        ) return

        AlertDialog.Builder(this)
            .setTitle("Uso responsable")
            .setMessage(
                "Este agente reporta a tu panel la información de ESTE teléfono. " +
                    "Lo que se comparte, completo:\n\n" +
                    "• Ubicación, cada pocos segundos y también con la pantalla apagada.\n" +
                    "• Entradas y salidas de las zonas que se definan (casa, colegio…).\n" +
                    "• Batería, si está cargando y si el rastreo sigue activo.\n" +
                    "• Estado del dispositivo: bloqueo de pantalla, parche de seguridad, root, " +
                    "depuración USB, Play Protect y apps con accesibilidad, lectura de " +
                    "notificaciones o administración.\n" +
                    "• La lista de aplicaciones instaladas (solo nombres; sin uso ni horarios). " +
                    "Se puede desactivar con el interruptor \"Compartir la lista de apps\".\n\n" +
                    "Lo que NO se comparte nunca: uso o tiempos de apps, mensajes, contactos, " +
                    "fotos, archivos, micrófono, cámara ni capturas de pantalla.\n\n" +
                    "Todo es visible por diseño: icono en el lanzador, notificación permanente " +
                    "con botón Detener y este mismo resumen en la pantalla del teléfono. " +
                    "Instalarlo en un dispositivo ajeno o a escondidas es ilegal en la " +
                    "mayoría de países y este proyecto no lo soporta."
            )
            .setPositiveButton("Entiendo y acepto") { _, _ ->
                settings.consentAcceptedAt = System.currentTimeMillis()
                settings.consentVersion = SettingsRepository.CONSENT_VERSION
            }
            .setNegativeButton("Salir") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun consentOk(): Boolean =
        settings.consentAcceptedAt > 0 &&
                settings.consentVersion == SettingsRepository.CONSENT_VERSION

    /**
     * SOS: lo pulsa quien tiene el telefono. Se explica antes lo que hace, para
     * que nadie active sin querer 10 minutos de seguimiento detallado.
     */
    private fun confirmSos() {
        AlertDialog.Builder(this)
            .setTitle("🆘 Pedir ayuda")
            .setMessage(
                "Se enviará un aviso a tu panel con tu posición actual y se activarán " +
                    "10 minutos de seguimiento detallado (cada 2 s).\n\n" +
                    "Si el rastreo estaba parado, se iniciará: la notificación permanente " +
                    "volverá a aparecer. El aviso se queda registrado con su hora."
            )
            .setPositiveButton("Enviar SOS") { _, _ ->
                EventReporter.sendSos(this)
                Toast.makeText(this, "SOS enviado", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------- arranque

    private fun maybeAutoResume() {
        // Si el rastreo estaba activo (p. ej. el proceso murió), se relanza al abrir la app
        if (settings.trackingEnabled && settings.pairingComplete &&
            hasLocationPermission() && consentOk()
        ) {
            LocationService.start(this)
        }
    }

    private fun onToggleClicked() {
        if (!settings.pairingComplete) {
            Toast.makeText(this, "Completa el emparejamiento y guarda", Toast.LENGTH_LONG).show()
            return
        }
        if (!consentOk()) {
            maybeShowDisclosure()
            return
        }
        if (!hasLocationPermission()) {
            waitingForPermissions = true
            requestPermissions()
            return
        }
        startTracking()
    }

    private fun startTracking() {
        settings.trackingEnabled = true
        SyncWorker.schedule(this)
        WatchdogReceiver.schedule(this)
        LocationService.start(this)
        Toast.makeText(this, "Rastreo iniciado", Toast.LENGTH_SHORT).show()
    }

    /** Parada pedida por el usuario (botón o notificación): pasa por el PIN. */
    private fun requestStop() {
        PinPrompt.runGuarded(this, "Detener rastreo", "El rastreo se detendrá y se cerrará la notificación") {
            LocationService.stop(this)
            Toast.makeText(this, "Rastreo detenido", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------- permisos

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun requestBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("Ubicación en segundo plano")
            .setMessage("Para seguir reportando con la pantalla apagada, concede \"Permitir todo el tiempo\" en Ajustes.")
            .setPositiveButton("Abrir ajustes") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "La optimización ya está desactivada", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            )
        }
    }

    // ------------------------------------------------------------- settings

    private fun loadSettings() {
        etUrl.setText(settings.supabaseUrl)
        etKey.setText(settings.supabaseKey)
        etDeviceId.setText(settings.deviceId)
        etToken.setText(settings.deviceToken)
        spInterval.setSelection(intervalOptions.indexOf(settings.intervalSec).coerceAtLeast(0))
        cbPrecision.isChecked = settings.precisionPlus
        cbAdaptive.isChecked = settings.adaptiveBattery
        cbDiscreet.isChecked = settings.discreetNotif
        cbRemote.isChecked = settings.remoteControl
        cbSmart.isChecked = settings.smartTracking
        cbShareApps.isChecked = settings.shareAppList
    }

    /** Cambiar la configuracion mueve a donde se reporta: pide PIN si hay uno. */
    private fun saveSettings() {
        PinPrompt.runGuarded(this, "Guardar configuración") { doSave() }
    }

    private fun doSave() {
        settings.supabaseUrl = etUrl.text.toString()
        settings.supabaseKey = etKey.text.toString()
        settings.deviceId = etDeviceId.text.toString()
        settings.deviceToken = etToken.text.toString()
        settings.intervalSec = intervalOptions[spInterval.selectedItemPosition.coerceAtLeast(0)]
        settings.precisionPlus = cbPrecision.isChecked
        settings.adaptiveBattery = cbAdaptive.isChecked
        settings.discreetNotif = cbDiscreet.isChecked
        settings.remoteControl = cbRemote.isChecked
        settings.smartTracking = cbSmart.isChecked
        settings.shareAppList = cbShareApps.isChecked

        Toast.makeText(
            this,
            if (settings.pairingComplete) "Configuración guardada" else "Faltan datos de emparejamiento",
            Toast.LENGTH_SHORT
        ).show()

        if (LocationService.isRunning) {
            // El servicio relee intervalo/prioridad en onStartCommand
            startService(Intent(this, LocationService::class.java))
        }
        refreshSecurity()
    }

    // ------------------------------------------------------------- desvincular

    private fun confirmUnpair() {
        PinPrompt.runGuarded(this, "Desvincular dispositivo") {
            AlertDialog.Builder(this)
                .setTitle("¿Borrar también el historial del servidor?")
                .setMessage(
                    "Se detendrá el rastreo, se borrará el buffer local y el servidor " +
                        "invalidará este token: el dispositivo deja de poder enviar hasta " +
                        "que lo emparejes de nuevo desde el panel."
                )
                .setPositiveButton("Sí, borrar historial") { _, _ -> doUnpair(purge = true) }
                .setNegativeButton("No, conservar historial") { _, _ -> doUnpair(purge = false) }
                .show()
        }
    }

    private fun doUnpair(purge: Boolean) {
        LocationService.stop(this)
        settings.trackingEnabled = false
        Toast.makeText(this, "Desvinculando…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val serverOk = withContext(Dispatchers.IO) {
                runCatching { SupabaseClient(settings).revokeSelf(purge) }.isSuccess
            }
            withContext(Dispatchers.IO) {
                runCatching { AppDatabase.get(applicationContext).locationDao().clearAll() }
            }
            settings.clearPairing()
            etDeviceId.setText("")
            etToken.setText("")
            refreshSecurity()
            Toast.makeText(
                this@MainActivity,
                if (serverOk) "Dispositivo desvinculado: el token ya no es válido en el servidor"
                else "Desvinculado localmente. Sin red: rota el token desde admin.html",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------- estado

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceStateHolder.state.collect { st ->
                    btnToggle.text =
                        if (st.running) getString(R.string.btn_toggle_stop) else getString(R.string.btn_toggle_start)
                    statusText.text = if (!st.running) {
                        getString(R.string.status_stopped)
                    } else {
                        val lat = st.lastLat
                        val lon = st.lastLon
                        buildString {
                            append("Activo")
                            if (lat != null && lon != null) {
                                append(" · ").append("%.5f, %.5f".format(lat, lon))
                            }
                            st.lastAccuracy?.let { append(" · ±").append(Math.round(it)).append(" m") }
                            st.lastSource?.let { append(" · ").append(it) }
                            if (st.burst) append(" · ⚡ persecución")
                            else if (st.stationary) append(" · quieto (sin gasto de GPS)")
                            st.lastCommand?.let { append(" · último mando: ").append(it) }
                            if (st.pendingSends > 0) append(" · ").append(st.pendingSends).append(" sin enviar")
                        }
                    }
                }
            }
        }
    }
}
