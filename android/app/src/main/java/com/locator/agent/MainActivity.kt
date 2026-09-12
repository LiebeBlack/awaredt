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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locator.agent.data.SettingsRepository
import com.locator.agent.sync.LocationService
import com.locator.agent.sync.ServiceStateHolder
import com.locator.agent.sync.SyncWorker
import com.locator.agent.sync.WatchdogReceiver
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository

    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnBattery: Button
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etToken: EditText
    private lateinit var spInterval: Spinner
    private lateinit var cbPrecision: CheckBox
    private lateinit var cbAdaptive: CheckBox
    private lateinit var cbDiscreet: CheckBox

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.get(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnToggle = findViewById(R.id.btnToggle)
        btnBattery = findViewById(R.id.btnBattery)
        etUrl = findViewById(R.id.etSupabaseUrl)
        etKey = findViewById(R.id.etSupabaseKey)
        etDeviceId = findViewById(R.id.etDeviceId)
        etToken = findViewById(R.id.etDeviceToken)
        spInterval = findViewById(R.id.spInterval)
        cbPrecision = findViewById(R.id.cbPrecision)
        cbAdaptive = findViewById(R.id.cbAdaptive)
        cbDiscreet = findViewById(R.id.cbDiscreet)

        spInterval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervalOptions.map { "$it s" }
        )

        loadSettings()

        btnToggle.setOnClickListener {
            if (LocationService.isRunning) stopTracking() else onToggleClicked()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        observeState()
        maybeAutoResume()
    }

    // ------------------------------------------------------------- arranque

    private fun maybeAutoResume() {
        // Si el rastreo estaba activo (p. ej. el proceso murió), se relanza al abrir la app
        if (settings.trackingEnabled && settings.pairingComplete && hasLocationPermission()) {
            LocationService.start(this)
        }
    }

    private fun onToggleClicked() {
        if (!settings.pairingComplete) {
            Toast.makeText(this, "Completa el emparejamiento y guarda", Toast.LENGTH_LONG).show()
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

    private fun stopTracking() {
        // El servicio apaga updates, cancela la bandera y cierra la notificación
        startService(Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
        Toast.makeText(this, "Rastreo detenido", Toast.LENGTH_SHORT).show()
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
    }

    private fun saveSettings() {
        settings.supabaseUrl = etUrl.text.toString()
        settings.supabaseKey = etKey.text.toString()
        settings.deviceId = etDeviceId.text.toString()
        settings.deviceToken = etToken.text.toString()
        settings.intervalSec = intervalOptions[spInterval.selectedItemPosition.coerceAtLeast(0)]
        settings.precisionPlus = cbPrecision.isChecked
        settings.adaptiveBattery = cbAdaptive.isChecked
        settings.discreetNotif = cbDiscreet.isChecked

        Toast.makeText(
            this,
            if (settings.pairingComplete) "Configuración guardada" else "Faltan datos de emparejamiento",
            Toast.LENGTH_SHORT
        ).show()

        if (LocationService.isRunning) {
            // El servicio relee el intervalo en onStartCommand
            startService(Intent(this, LocationService::class.java))
        }
    }

    // ------------------------------------------------------------- estado

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceStateHolder.state.collect { st ->
                    btnToggle.text = if (st.running) "Detener rastreo" else "Iniciar rastreo"
                    statusText.text = if (!st.running) {
                        "Detenido"
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
                            if (st.pendingSends > 0) append(" · ").append(st.pendingSends).append(" sin enviar")
                        }
                    }
                }
            }
        }
    }
}
