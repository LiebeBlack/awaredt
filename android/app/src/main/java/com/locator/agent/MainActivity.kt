package com.locator.agent

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locator.agent.admin.DeviceAdmin
import com.locator.agent.data.AppDatabase
import com.locator.agent.data.SettingsRepository
import com.locator.agent.databinding.ActivityMainBinding
import com.locator.agent.security.PinPrompt
import com.locator.agent.security.PinStore
import com.locator.agent.sync.AppVisibility
import com.locator.agent.sync.EventReporter
import com.locator.agent.sync.LocationService
import com.locator.agent.sync.ServiceStateHolder
import com.locator.agent.sync.SupabaseClient
import com.locator.agent.sync.SyncWorker
import com.locator.agent.sync.TamperCheck
import com.locator.agent.sync.WatchdogReceiver
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var pins: PinStore
    private lateinit var b: ActivityMainBinding

    private val intervalOptions = listOf(5, 10, 30, 60, 300)
    private var waitingForPermissions = false

    /** Accesos directos a las casillas de Comportamiento y Seguridad. */
    private inner class Cb(val view: CheckBox, val save: (Boolean) -> Unit, val load: () -> Boolean)

    private val cbs: List<Cb> by lazy {
        listOf(
            Cb(b.cbPrecision, { settings.precisionPlus = it }, { settings.precisionPlus }),
            Cb(b.cbAdaptive, { settings.adaptiveBattery = it }, { settings.adaptiveBattery }),
            Cb(b.cbDiscreet, { settings.discreetNotif = it }, { settings.discreetNotif }),
            Cb(b.cbRemote, { settings.remoteControl = it }, { settings.remoteControl }),
            Cb(b.cbSmart, { settings.smartTracking = it }, { settings.smartTracking }),
            Cb(b.cbShareApps, { settings.shareAppList = it }, { settings.shareAppList })
        )
    }

    /** Aplica el tema elegido (sistema/claro/oscuro) ANTES de inflar la vista. */
    private fun applySavedTheme() {
        when (settings.themeMode) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /** Marca el chip del tema activo para que se vea cual esta seleccionado. */
    private fun updateThemeButtons() {
        val active = when (settings.themeMode) {
            1 -> R.id.btnThemeLight
            2 -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }
        listOf(R.id.btnThemeSystem, R.id.btnThemeLight, R.id.btnThemeDark).forEach { id ->
            val btn = findViewById<Button>(id)
            val base = getString(
                when (id) {
                    R.id.btnThemeSystem -> R.string.theme_system
                    R.id.btnThemeLight -> R.string.theme_light
                    else -> R.string.theme_dark
                }
            )
            btn.text = if (id == active) "\u2713 $base" else base
            btn.alpha = if (id == active) 1f else 0.6f
        }
    }

    /**
     * Resultado del diálogo oficial de permisos. El caso importante NO es el
     * "denegado" normal (se vuelve a pedir): es el SILENCIOSO — en Android 11+
     * tras dos negativas el sistema deja de mostrar el diálogo y el resultado
     * llega como falso sin abrir nada. Antes la app se quedaba muda ahí.
     * Ahora se distingue con shouldShowRequestPermissionRationale y se ofrece
     * el salto directo a Ajustes, donde "Permitir todo el tiempo" SIEMPRE
     * funciona aunque el diálogo ya no aparezca nunca.
     */
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
                return@registerForActivityResult
            }
            val perm = Manifest.permission.ACCESS_FINE_LOCATION
            val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            if (rationale) {
                // Denegación normal: Android volverá a mostrar el diálogo.
                Toast.makeText(this, getString(R.string.perm_denied_toast), Toast.LENGTH_LONG).show()
            } else if (grants.containsKey(perm)) {
                // Silencioso: el sistema ya no abrirá el diálogo por más veces
                // que se pulse. Sin Ajustes no hay camino posible.
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_blocked_title))
                    .setMessage(getString(R.string.perm_blocked_msg))
                    .setPositiveButton(getString(R.string.perm_blocked_open)) { _, _ ->
                        openAppSettings()
                    }
                    .setNegativeButton(getString(R.string.later), null)
                    .show()
            }
            // Sin clave para el permiso: el usuario cerró el diálogo con "no
            // volver a preguntar" — mismo caso que la denegación silenciosa.
        }

    /** Ajustes de la app: desde aquí "Ubicación -> Permitir todo el tiempo" siempre funciona. */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.perm_settings_fail), Toast.LENGTH_LONG).show()
        }
    }

    /** Al volver del diálogo oficial de DeviceAdmin se refresca el estado. */
    private val adminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Se anota la INTENCION del dueño: si dentro de un rato aparece
            // desactivado, es manipulacion y el panel lo vera con hora y motivo.
            settings.antiTheftEnabled = DeviceAdmin.isActive(this)
            refreshSecurity()
            refreshSetup()
            reportHealth()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.get(this)
        pins = PinStore.get(this)
        applySavedTheme()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Barra superior con el menu (tema / puesta a punto) en el desborde.
        setSupportActionBar(b.toolbar)

        b.spInterval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervalOptions.map { "$it s" }
        )

        loadSettings()
        refreshAll()

        b.btnToggle.setOnClickListener {
            if (LocationService.isRunning) requestStop() else onToggleClicked()
        }

        // Pestana visible tras un recreate (cambio de tema): se restaura mas
        // abajo, justo despues de registrar el listener de navegacion.
        b.btnSave.setOnClickListener { saveSettings() }
        b.btnPaste.setOnClickListener {
            // Cambiar a donde reporta este telefono es un cambio de emparejamiento:
            // mismo candado que Guardar (si hay PIN, lo pide; si no, pasa directo).
            PinPrompt.runGuarded(this, "Pegar credenciales del emparejamiento") { pasteCredentials() }
        }
        b.btnBattery.setOnClickListener { requestBatteryExemption() }
        b.btnPin.setOnClickListener {
            PinPrompt.changeOrSet(this) { refreshSecurity(); refreshSetup() }
        }
        b.btnAntiTheft.setOnClickListener { toggleAntiTheft() }
        b.btnUnpair.setOnClickListener { confirmUnpair() }
        b.btnCheckin.setOnClickListener {
            EventReporter.sendCheckin(this)
            Toast.makeText(this, "Aviso enviado: llegué bien", Toast.LENGTH_SHORT).show()
        }
        b.btnSos.setOnClickListener { confirmSos() }

        // Apariencia: sistema / claro / oscuro, persistido en los ajustes.
        // saveEnabled=false: al recrear la Activity tras cambiar el tema no se
        // vuelve a crear un segundo callback y no se duplica el refresco.
        b.btnThemeSystem.setOnClickListener {
            settings.themeMode = 0
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            updateThemeButtons()
        }
        b.btnThemeLight.setOnClickListener {
            settings.themeMode = 1
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            updateThemeButtons()
        }
        b.btnThemeDark.setOnClickListener {
            settings.themeMode = 2
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            updateThemeButtons()
        }
        updateThemeButtons()

        // Navegacion por pestanas: una ventana por seccion, sin scroll infinito.
        b.nav.addOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        // Pestana visible tras un recreate (cambio de tema): la que eligio el
        // usuario. Home ya es visible por XML, asi que solo se restauran otras.
        val restored = savedInstanceState?.getInt(STATE_TAB, R.id.tabHome) ?: R.id.tabHome
        if (restored != R.id.tabHome) b.nav.selectedItemId = restored

        observeState()
        handleIntent(intent)
        maybeAutoResume()
        maybeShowDisclosure()
    }

    companion object {
        private const val STATE_TAB = "active_tab"
    }

    /** Pestana visible; se restaura sola tras el recreate() del cambio de tema. */
    private fun showPage(tabId: Int) {
        b.pageHome.visibility = if (tabId == R.id.tabHome) View.VISIBLE else View.GONE
        b.pagePair.visibility = if (tabId == R.id.tabPair) View.VISIBLE else View.GONE
        b.pageTrack.visibility = if (tabId == R.id.tabTrack) View.VISIBLE else View.GONE
        b.pageSecurity.visibility = if (tabId == R.id.tabSecurity) View.VISIBLE else View.GONE
        b.pageSettings.visibility = if (tabId == R.id.tabSettings) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, b.nav.selectedItemId)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val tab = when (item.itemId) {
            R.id.menuThemes -> R.id.tabSettings
            R.id.menuSetup -> R.id.tabTrack
            else -> return super.onOptionsItemSelected(item)
        }
        showPage(tab) // selectedItemId no re-dispara si ya estaba seleccionada
        b.nav.selectedItemId = tab
        return true
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
        reportHealth()
        // Volver de Ajustes es un punto de re-evaluacion: si el usuario acaba de
        // conceder "Permitir todo el tiempo" (o notificaciones), se arranca solo.
        if (settings.trackingEnabled && settings.pairingComplete &&
            consentOk() && hasLocationPermission() && !LocationService.isRunning
        ) {
            LocationService.start(this)
        }
        refreshAll()
    }

    /** Recalcula en una sola pasada lo que depende de permisos y ajustes. */
    private fun refreshAll() {
        refreshSecurity()
        refreshPairing()
        refreshSetup()
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
        b.securityText.text = "$pin · $admin · $remote\n$tamper"
        b.btnAntiTheft.text =
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
                refreshSetup()
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

    // ------------------------------------------------------- emparejamiento

    /**
     * Emparejamiento sin tecleo: el panel muestra UUID+token al emparejar
     * (o el usuario copia los 4 datos). Aqui se pegan y se clasifican solos:
     *  - URL https://...                                  -> URL de Supabase
     *  - JWT (empieza por "eyJ")                          -> clave anon
     *  - UUID canonico                                    -> UUID del dispositivo
     *  - cualquier otra linea no vacia                    -> token
     * Si la URL del portapapeles es distinta de la guardada, se pide
     * confirmacion: asi no se pisan credenciales de otro proyecto sin querer.
     */
    private fun pasteCredentials() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
            return
        }

        var url: String? = null
        var key: String? = null
        var uuid: String? = null
        val tokenParts = ArrayList<String>()

        val uuidRegex = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
        for (line0 in raw.split('\n', '\r')) {
            val line = line0.trim().trim('"', ',', ';')
            if (line.isEmpty() || line.startsWith("//")) continue
            // Admite "clave: valor" y pares JSON ("supabaseUrl": "..."), pero
            // prueba primero la linea completa: "https://..." contiene ':' y
            // un recorte por dos puntos romperia la URL (https:// -> //...).
            val value = if (line.startsWith("https://") || line.startsWith("eyJ") ||
                uuidRegex.matches(line)
            ) {
                line.trim('"', ' ', '\t').trimEnd(',')
            } else {
                line.substringAfter(':', missingDelimiterValue = line)
                    .trim()
                    .trim('"', ' ', '\t')
                    .trimEnd(',')
            }
            when {
                value.startsWith("https://") && !value.contains(' ') -> url = value
                value.startsWith("eyJ") -> key = value
                uuidRegex.matches(value) -> uuid = value.lowercase(Locale.ROOT)
                else -> tokenParts.add(value)
            }
        }

        val urlFinal = url ?: settings.supabaseUrl
        val keyFinal = key ?: settings.supabaseKey
        if (urlFinal.isBlank() || keyFinal.isBlank()) {
            Toast.makeText(this, "No encontré URL y clave anon en el portapapeles", Toast.LENGTH_LONG).show()
            return
        }
        val tokenFinal = tokenParts.joinToString("") { it.trim() }
            .ifBlank { settings.deviceToken }
        val uuidFinal = uuid ?: settings.deviceId

        fun apply() {
            b.etSupabaseUrl.setText(urlFinal)
            b.etSupabaseKey.setText(keyFinal)
            b.etDeviceId.setText(uuidFinal)
            b.etDeviceToken.setText(tokenFinal)
            settings.supabaseUrl = urlFinal
            settings.supabaseKey = keyFinal
            settings.deviceId = uuidFinal
            settings.deviceToken = tokenFinal
            refreshPairing()
            Toast.makeText(this, "Credenciales pegadas: pulsa Guardar", Toast.LENGTH_LONG).show()
        }

        if (url != null && settings.supabaseUrl.isNotBlank() &&
            url != settings.supabaseUrl && settings.deviceId.isNotBlank()
        ) {
            // La URL traida es de OTRO proyecto y ya hay un dispositivo configurado:
            // confirmar antes de pisar (evita emparejar con credenciales ajenas).
            AlertDialog.Builder(this)
                .setTitle("¿Cambiar de proyecto?")
                .setMessage(
                    "La URL del portapapeles es distinta de la guardada:\n\n" +
                        "Guardada: ${settings.supabaseUrl}\n" +
                        "Pegada: $url\n\n" +
                        "Si continúas, el dispositivo se reconfigura al nuevo proyecto " +
                        "(tendrás su UUID y token para volver a emparejar)."
                )
                .setPositiveButton("Usar la pegada") { _, _ -> apply() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            apply()
        }
    }

    /** Estado del emparejamiento, visible sin adivinar: verde listo, ambar falta. */
    private fun refreshPairing() {
        if (settings.pairingComplete) {
            b.pairStatus.text = getString(R.string.pair_status_ok)
            b.pairStatus.setBackgroundResource(R.drawable.bg_status_ok)
            b.pairStatus.setTextColor(resources.getColor(R.color.status_ok_text, theme))
        } else {
            val missing = ArrayList<String>()
            if (!settings.supabaseUrl.startsWith("https://")) missing.add(getString(R.string.missing_url))
            if (settings.supabaseKey.isBlank()) missing.add(getString(R.string.missing_key))
            if (settings.deviceId.isBlank()) missing.add(getString(R.string.missing_uuid))
            if (settings.deviceToken.isBlank()) missing.add(getString(R.string.missing_token))
            b.pairStatus.text = getString(R.string.pair_status_missing, missing.joinToString(", "))
            b.pairStatus.setBackgroundResource(R.drawable.bg_status_warn)
            b.pairStatus.setTextColor(resources.getColor(R.color.accent_amber, theme))
        }
    }

    // ------------------------------------------------- puesta a punto (setup)

    /** Requisitos con su estado actual; el orden define la prioridad de arreglo. */
    private data class SetupItem(val label: String, val ok: Boolean)

    private fun setupItems(): List<SetupItem> {
        val items = ArrayList<SetupItem>()
        items.add(SetupItem(getString(R.string.setup_location), hasLocationPermission()))
        if (Build.VERSION.SDK_INT >= 29) {
            items.add(
                SetupItem(
                    getString(R.string.setup_background),
                    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                )
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            items.add(
                SetupItem(
                    getString(R.string.setup_notifications),
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )
            )
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        items.add(SetupItem(getString(R.string.setup_battery), pm.isIgnoringBatteryOptimizations(packageName)))
        items.add(SetupItem(getString(R.string.setup_pin), pins.isSet))
        items.add(SetupItem(getString(R.string.setup_antitheft), DeviceAdmin.isActive(this)))
        return items
    }

    /** Lista visible de la puesta a punto: cada requisito con su ✓ o ✗. */
    private fun refreshSetup() {
        val items = setupItems()
        val text = items.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.label }
        b.setupStatus.text = text
        b.setupStatus.setTextColor(
            resources.getColor(
                if (items.all { it.ok }) R.color.status_ok_text else R.color.text_primary,
                theme
            )
        )
    }

    /** Corrige el primer requisito pendiente, con la misma UI que el resto de la app. */
    private fun fixFirstPendingSetup() {
        val items = setupItems()
        val labels = listOf(
            getString(R.string.setup_location),
            getString(R.string.setup_background),
            getString(R.string.setup_notifications),
            getString(R.string.setup_battery),
            getString(R.string.setup_pin),
            getString(R.string.setup_antitheft)
        )
        val first = items.firstOrNull { !it.ok }?.label
        when (labels.indexOf(first)) {
            0 -> { // permiso de ubicacion
                waitingForPermissions = false
                requestPermissions()
            }
            1 -> requestBackgroundIfNeeded()
            2 -> if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions()
            }
            3 -> requestBatteryExemption()
            4 -> PinPrompt.changeOrSet(this) { refreshSecurity(); refreshSetup() }
            5 -> toggleAntiTheft()
        }
        if (items.all { it.ok }) {
            Toast.makeText(this, "Todo listo: ya puedes iniciar el rastreo", Toast.LENGTH_SHORT).show()
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
        try {
            permissionLauncher.launch(wanted.toTypedArray())
        } catch (_: Exception) {
            // Algunas capas del fabricante revientan al abrir el diálogo;
            // Ajustes SIEMPRE existe y desde ahí el permiso se concede igual.
            openAppSettings()
        }
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
        b.etSupabaseUrl.setText(settings.supabaseUrl)
        b.etSupabaseKey.setText(settings.supabaseKey)
        b.etDeviceId.setText(settings.deviceId)
        b.etDeviceToken.setText(settings.deviceToken)
        b.spInterval.setSelection(intervalOptions.indexOf(settings.intervalSec).coerceAtLeast(0))
        cbs.forEach { it.view.isChecked = it.load() }
    }

    /** Cambiar la configuracion mueve a donde se reporta: pide PIN si hay uno. */
    private fun saveSettings() {
        PinPrompt.runGuarded(this, "Guardar configuración") { doSave() }
    }

    private fun doSave() {
        settings.supabaseUrl = b.etSupabaseUrl.text.toString()
        settings.supabaseKey = b.etSupabaseKey.text.toString()
        settings.deviceId = b.etDeviceId.text.toString()
        settings.deviceToken = b.etDeviceToken.text.toString()
        settings.intervalSec = intervalOptions[b.spInterval.selectedItemPosition.coerceAtLeast(0)]
        cbs.forEach { it.save(it.view.isChecked) }

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
        refreshPairing()
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
            b.etDeviceId.setText("")
            b.etDeviceToken.setText("")
            refreshSecurity()
            refreshPairing()
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
                    b.btnToggle.text =
                        if (st.running) getString(R.string.btn_toggle_stop) else getString(R.string.btn_toggle_start)
                    b.statusText.text = if (!st.running) {
                        getString(R.string.status_stopped)
                    } else {
                        val lat = st.lastLat
                        val lon = st.lastLon
                        buildString {
                            append("Activo")
                            if (lat != null && lon != null) {
                                append(" · ").append(String.format(Locale.US, "%.5f, %.5f", lat, lon))
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
