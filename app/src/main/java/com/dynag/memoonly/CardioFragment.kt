package com.dynag.memoonly

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dynag.memoonly.databinding.FragmentCardioBinding
import java.util.Locale

/**
 * MemoOnly — workout tab.
 *
 * Reads live heart rate from any Bluetooth LE source that exposes the standard
 * Heart Rate service (0x180D): chest straps (Polar H10, Garmin, Wahoo…) and
 * wearables that broadcast optical HR over the standard Bluetooth Heart Rate
 * Profile (Fitbit Charge 6, Google Fitbit Air, Google Pixel Watch 2+).
 *
 * Distance, speed and elapsed time come from the phone's GPS, since a
 * heart-rate source carries no motion data. The combined workout metrics are
 * mirrored onto the MemoMind smart glasses HUD over Bluetooth LE by the
 * foreground [CardioService], through the paired **cardio glasses plugin**
 * (channel 0x4344, drawn natively by the `.gmp`).
 *
 * The fragment only drives the on-screen UI and the sensor scan; GPS, the
 * forever-running HUD stream and the glasses link belong to the service.
 */
class CardioFragment : Fragment(),
    HeartRateManager.Listener {

    private var binding: FragmentCardioBinding? = null

    private lateinit var bpmText: TextView
    private lateinit var distText: TextView
    private lateinit var speedText: TextView
    private lateinit var timeText: TextView
    private lateinit var avgText: TextView
    private lateinit var zoneText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnSensor: Button
    private lateinit var btnGlasses: Button
    private lateinit var btnTrack: Button
    private lateinit var btnCopyLog: Button
    private lateinit var logHeader: View
    private lateinit var logToggle: TextView
    private lateinit var logPanel: View
    private lateinit var speedRow: View
    private lateinit var distanceBlock: View
    private lateinit var speedBlock: View
    private lateinit var averageBlock: View
    private lateinit var sportGroup: RadioGroup

    private lateinit var heartRate: HeartRateManager

    private var lastBpm: Int = 0
    private var lastDistance = 0.0
    private var lastSpeedKmh = 0.0
    private var lastAverageKmh = 0.0
    private var lastElapsedMs = 0L
    private var sensorReady = false
    /** True while the foreground [CardioService] owns location + glasses. */
    private var jobRunning = false

    /**
     * Generic sport ("Autre sport"): no GPS pace, the glasses only get BPM and
     * the elapsed time, exactly like the plugin's speed-less mode.
     */
    private var noSpeed = false

    /** True while a saved-sensor reconnect is in flight, so a failure can fall
     *  back to a fresh scan once instead of looping. */
    private var reconnectingSaved = false

    /** Bands the glasses use for their zone display (Karvonen). */
    private val restingBpm = 60
    private val maxBpm = 190

    /** True when the sensor last reported poor skin contact. */
    private var lastContactBad = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            onPermissionsGranted()
        } else {
            toast(getString(R.string.msg_permissions_required))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentCardioBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bpmText = view.findViewById(R.id.bpmText)
        distText = view.findViewById(R.id.distText)
        speedText = view.findViewById(R.id.speedText)
        timeText = view.findViewById(R.id.timeText)
        avgText = view.findViewById(R.id.avgText)
        zoneText = view.findViewById(R.id.zoneText)
        statusText = view.findViewById(R.id.statusText)
        btnSensor = view.findViewById(R.id.btnSensor)
        btnGlasses = view.findViewById(R.id.btnGlasses)
        btnTrack = view.findViewById(R.id.btnTrack)
        btnCopyLog = view.findViewById(R.id.btnCopyLog)
        logHeader = view.findViewById(R.id.logHeader)
        logToggle = view.findViewById(R.id.logToggle)
        logPanel = view.findViewById(R.id.logPanel)
        speedRow = view.findViewById(R.id.speedRow)
        distanceBlock = view.findViewById(R.id.distanceBlock)
        speedBlock = view.findViewById(R.id.speedBlock)
        averageBlock = view.findViewById(R.id.averageBlock)
        sportGroup = view.findViewById(R.id.sportGroup)

        sportGroup.check(R.id.sportRun)
        sportGroup.setOnCheckedChangeListener { _, checkedId ->
            noSpeed = checkedId == R.id.sportOther
            applySportVisibility()
        }
        applySportVisibility()

        logHeader.setOnClickListener { toggleLog() }
        logToggle.setOnClickListener { toggleLog() }

        heartRate = HeartRateManager(requireContext(), this)

        btnSensor.setOnClickListener {
            ensurePermissions {
                if (heartRate.isConnected) {
                    heartRate.stop()
                    appendStatus(getString(R.string.msg_sensor_released))
                } else {
                    // Reconnect to the remembered sensor first; only scan when
                    // none is saved. Long-press to forget and re-discover.
                    val saved = heartRate.savedSensor
                    if (heartRate.connectSaved()) {
                        reconnectingSaved = true
                        appendStatus(
                            getString(
                                R.string.reconnecting_sensor,
                                saved?.name?.ifBlank { saved.address } ?: "capteur"
                            )
                        )
                    } else {
                        heartRate.scanForSensors()
                    }
                }
            }
        }
        btnSensor.setOnLongClickListener {
            ensurePermissions {
                heartRate.stop()
                heartRate.forgetSaved()
                appendStatus(getString(R.string.msg_forgot_sensor))
                heartRate.scanForSensors()
            }
            true
        }
        btnGlasses.setOnClickListener {
            ensurePermissions {
                // Connection itself lives in the foreground service; this
                // button re-launches it (and reports if nothing is tracking).
                if (jobRunning) {
                    CardioService.start(requireContext())
                } else {
                    toast(getString(R.string.msg_start_track_first))
                }
            }
        }
        btnTrack.setOnClickListener {
            ensurePermissions {
                if (jobRunning) stopJob() else startJob()
            }
        }
        btnCopyLog.setOnClickListener {
            val clipboard = requireActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MemoOnly log", statusText.text))
            appendStatus(getString(R.string.msg_log_copied))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun toggleLog() {
        val b = binding ?: return
        val show = b.logPanel.visibility != View.VISIBLE
        b.logPanel.visibility = if (show) View.VISIBLE else View.GONE
        b.logToggle.setText(if (show) R.string.log_hide else R.string.log_show)
    }

    private fun requiredPermissions(): Array<String> {
        // A generic sport never reads the GPS, so the location permission is
        // only asked for the sports that compute pace.
        val location = if (noSpeed) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                *location
            )
        } else {
            location
        }
    }

    private fun ensurePermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private var pendingAction: (() -> Unit)? = null

    private fun onPermissionsGranted() {
        pendingAction?.invoke()
        pendingAction = null
    }

    // ---- foreground tracking job ---------------------------------------

    /** Generic sports drop the GPS metrics: only heart rate and time remain. */
    private fun applySportVisibility() {
        val hide = if (noSpeed) View.GONE else View.VISIBLE
        distanceBlock.visibility = hide
        speedBlock.visibility = hide
        averageBlock.visibility = hide
        speedRow.visibility = hide
        if (noSpeed) {
            distText.text = getString(R.string.distance_placeholder)
            speedText.text = getString(R.string.speed_placeholder)
            avgText.text = getString(R.string.average_placeholder)
        }
    }

    private fun startJob() {
        if (!checkBatteryExemption()) return
        CardioService.start(requireContext(), noSpeed)
        jobRunning = true
        btnTrack.text = getString(R.string.btn_track_stop)
        appendStatus(
            getString(
                if (noSpeed) R.string.msg_track_started_other else R.string.msg_track_started
            )
        )
    }

    private fun stopJob() {
        CardioService.stop(requireContext())
        jobRunning = false
        btnTrack.text = getString(R.string.btn_track)
        lastElapsedMs = 0L
        lastDistance = 0.0
        lastSpeedKmh = 0.0
        lastAverageKmh = 0.0
        distText.text = getString(R.string.distance_placeholder)
        speedText.text = getString(R.string.speed_placeholder)
        timeText.text = getString(R.string.time_placeholder)
        avgText.text = getString(R.string.average_placeholder)
        appendStatus(getString(R.string.msg_track_stopped))
    }

    /**
     * Screen-off is the whole point of the foreground service: without a
     * battery exemption many OEM firmwares still cut GPS in Doze, so ask once
     * and explain instead of silently freezing the HUD.
     */
    private fun checkBatteryExemption(): Boolean {
        val ctx = requireContext()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return true
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
            )
        }
        appendStatus(getString(R.string.msg_battery_hint))
        return true
    }

    // ---- HeartRateManager.Listener ------------------------------------

    override fun onSensorStatus(message: String) {
        activity?.runOnUiThread { appendStatus(message) }
    }

    override fun onSensorsDiscovered(sensors: List<BleSensor>) {
        activity?.runOnUiThread {
            if (sensors.isEmpty()) {
                appendStatus(getString(R.string.msg_no_device_found))
                return@runOnUiThread
            }
            if (sensors.size == 1) {
                heartRate.connect(sensors[0])
                return@runOnUiThread
            }
            val labels = sensors.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_choose_sensor)
                .setItems(labels) { _, which -> heartRate.connect(sensors[which]) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    override fun onSensorReady() {
        activity?.runOnUiThread {
            reconnectingSaved = false
            sensorReady = true
            appendStatus(getString(R.string.msg_sensor_ready))
            // The HUD state is owned by the tracking service; only reset the
            // local value so the next beat re-arms the UI.
            lastBpm = 0
        }
    }

    override fun onSensorDisconnected() {
        activity?.runOnUiThread {
            sensorReady = false
            if (reconnectingSaved) {
                reconnectingSaved = false
                appendStatus(getString(R.string.saved_sensor_missing))
                heartRate.forgetSaved()
                ensurePermissions { heartRate.scanForSensors() }
                return@runOnUiThread
            }
            appendStatus(getString(R.string.msg_sensor_disconnected))
        }
    }

    override fun onSensorError(message: String) {
        activity?.runOnUiThread {
            sensorReady = false
            if (reconnectingSaved) {
                // The remembered sensor could not be reached (moved, paired
                // elsewhere, powered off). Forget it and fall back to a scan.
                reconnectingSaved = false
                appendStatus(getString(R.string.saved_sensor_unreachable))
                heartRate.forgetSaved()
                ensurePermissions { heartRate.scanForSensors() }
                return@runOnUiThread
            }
            appendStatus(message)
            toast(message)
        }
    }

    override fun onHeartRate(bpm: Int, contactDetected: Boolean?) {
        // The HUD stream is sent by the tracking service; the activity only
        // mirrors the value on screen.
        val firstBeat = lastBpm == 0
        lastBpm = bpm
        lastContactBad = contactDetected == false
        activity?.runOnUiThread {
            bpmText.text = bpm.toString()
            updateZone(bpm)
            if (firstBeat) appendStatus(getString(R.string.msg_first_beat))
        }
    }

    // -------------------------------------------------------------------

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f m", meters)
        }

    private fun formatSpeed(kmh: Double): String =
        String.format(Locale.getDefault(), "%.1f km/h", kmh)

    /** Karvonen zone label, mirroring the glasses (see GmFrame.cardioZone). */
    private fun updateZone(bpm: Int) {
        if (bpm <= 0) {
            zoneText.text = getString(R.string.zone_caption)
            return
        }
        when (GmFrame.cardioZone(bpm, restingBpm, maxBpm)) {
            1 -> setZone("Repos", R.color.text_secondary)
            2 -> setZone("Modérée", R.color.ok)
            3 -> setZone("Effort", R.color.warn)
            4 -> setZone("Maximum", R.color.bad)
            else -> setZone("Zone inconnue", R.color.text_secondary)
        }
    }

    private fun setZone(label: String, colorRes: Int) {
        zoneText.text = label
        val ctx = context ?: return
        zoneText.setTextColor(ContextCompat.getColor(ctx, colorRes))
    }

    private fun appendStatus(line: String) {
        statusText.append(line + "\n")
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // location + glasses belong to the foreground service; it keeps the
        // workout (and the HUD) alive while the fragment is gone. Between
        // tabs only the sensor scan is released, and the service itself
        // reconnects to the saved strap when a workout starts.
        heartRate.stop()
    }
}
