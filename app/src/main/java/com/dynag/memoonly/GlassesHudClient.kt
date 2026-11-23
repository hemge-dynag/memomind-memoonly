package com.dynag.memoonly

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connects to the MemoMind glasses over Bluetooth LE and feeds the paired
 * glasses plugins over the GM plugin service (0x0F / 0x28), in one shared
 * connection for both apps of MemOnly:
 *
 *  - the **cardio plugin** protocol (channel 0x4344 down / 0x4355 up), which
 *    draws the big BPM, zone and trend itself and signals readiness with an
 *    `EVENT_READY` byte on the up channel;
 *  - the **music plugin** protocol (TRACK 0x4D55, COVER 0x4D54 down, EVENT
 *    0x4D56 up), which draws the artwork, title, artist and progress bar and
 *    reports button presses back as a play/pause toggle request.
 *
 * Transport: GM command service. The command downlink characteristic is
 * 00002021-...  (Write / Write Without Response) and the response/event uplink
 * is 00002022-...  (Notify). We never hardcode attribute handles: the service
 * is discovered and its characteristics are resolved by UUID.
 *
 * Frames are produced by [GmFrame] and written one complete physical GM frame
 * per characteristic write, paced within the negotiated ATT MTU.
 *
 * Reference: GlassSDK/docs/PROTOCOL.md and GlassSDK/docs/BLUETOOTH_DEVELOPER_GUIDE.md.
 */
class GlassesHudClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onGlassesStatus(message: String)
        fun onGlassesReady()
        fun onGlassesDisconnected()
        /** The paired cardio plugin on the glasses announced EVENT_READY. */
        fun onCardioReady()

        /** The paired music plugin on the glasses answered a delivery. */
        fun onMusicReady()

        /** The glasses button asked for a play/pause toggle. */
        fun onToggleRequest()

        /**
         * The glasses could not be identified automatically. [devices] is a
         * list of (address, name) pairs — the phone's bonded devices first,
         * then whatever the scan saw — for the user to pick the glasses once;
         * the choice is remembered for next time.
         */
        fun onGlassesChooser(devices: List<Pair<String, String>>)
    }

    private val tag = "GlassesHudClient"

    companion object {
        // cardio plugin status (see protocol.js STATE_*)
        private const val STATE_IDLE = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_READING = 2
        private const val STATE_LOST = 3
        private const val STATE_ERROR = 4

        // Minimum spacing between two BPM payloads towards the glasses.
        private const val CARDIO_PUSH_INTERVAL_MS = 250L
        private const val TRACK_PUSH_INTERVAL_MS = 250L
        // Give up when nothing answers a link attempt, and try the next device.
        private const val SCAN_TIMEOUT_MS = 12000L
        // Legacy alias the firmware may advertise instead of the Bluetooth-base
        // service (see BLUETOOTH_DEVELOPER_GUIDE.md, `BlueConfigDefault`).
        private const val LEGACY_GM_SERVICE = "fb349b5f-8000-0080-0010-002020000000"
        // Bluetooth-base GM service; its characteristics are 0x2021/0x2022, but
        // advertisements list the *service* UUID, so match 0x2020 here.
        private const val GM_SERVICE_16 = "00002020"
        // Persisted address of the glasses the user picked once, and whether
        // that pick is already confirmed. Both services of MemOnly need the
        // address; only a confirmed pick may be reused without asking.
        private const val PREFS = "memo_cardio"
        private const val PREF_GLASSES_ADDR = "glasses_addr"
        private const val PREF_GLASSES_CONFIRMED = "glasses_confirmed"

        // Classic SPP command transport, the one the official App prefers.
        // The glasses never advertise the BLE GM service on every firmware,
        // and a phone that paired them over Classic can only reach them here.
        private const val UUID_GM_SPP = "00007033-0000-1000-8000-00805f9b34fb"
        private const val UUID_SPP = "00001101-0000-1000-8000-00805f9b34fb"
        // SPP frames may use the firmware ceiling of 512 bytes.
        private const val SPP_MAX_FRAME_BYTES = 512
        private const val SPP_CONNECT_TIMEOUT_MS = 12000
        // How long we wait for a running glasses plugin to announce readiness.
        private const val PLUGIN_WAIT_MS = 6000L
        // Extra screen-selection frames after the first plugin announcement.
        private val SCREEN_RESEND_DELAYS_MS = longArrayOf(1500L, 3500L)

        /** The active client, set by whichever service is connected. */
        @Volatile
        private var active: GlassesHudClient? = null

        /**
         * Set when the user handed the glasses back to the official app. Until
         * a fresh link is explicitly requested, [startLink] stays quiet instead
         * of taking the single phone-facing link away from the official app
         * again. App-wide: one link, so a yield is shared by both services.
         */
        @Volatile
        private var yieldRequested = false

        /**
         * Sink for every journal line, whichever client produced it. The
         * activity installs it so the Config tab shows one continuous log for
         * both the workout and the music mirror.
         */
        @Volatile
        private var journalSink: ((String) -> Unit)? = null

        fun setJournalSink(sink: ((String) -> Unit)?) {
            journalSink = sink
        }

        /**
         * Listeners that need the link's lifecycle without owning a service:
         * the activity uses one to keep its "glasses connected" badge true.
         */
        private val observers = java.util.concurrent.CopyOnWriteArrayList<Listener>()

        fun registerStatusListener(listener: Listener) {
            if (!observers.contains(listener)) observers.add(listener)
        }

        fun unregisterStatusListener(listener: Listener) {
            observers.remove(listener)
        }

        /**
         * Last journal lines, kept in memory so a screen can show the recent
         * history the instant it opens (the persisted log may be empty when
         * the app was just reinstalled).
         */
        private const val RECENT_MAX = 200
        private val recent = ArrayDeque<String>()

        fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

        /** Route a line to both the owning listener and the shared journal. */
        internal fun journal(listener: Listener, message: String) {
            listener.onGlassesStatus(message)
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            synchronized(recent) {
                recent.addLast("$stamp $message")
                while (recent.size > RECENT_MAX) recent.removeFirst()
            }
            journalSink?.invoke(message)
            fanOut { it.onGlassesStatus(message) }
        }

        /** Tell the shell listeners (the activity badge) without waking a service. */
        private fun fanOut(event: (Listener) -> Unit) {
            observers.forEach { runCatching { event(it) } }
        }

        /**
         * Ask the glasses to show [screen] ([GmFrame.SCREEN_CARDIO] or
         * [GmFrame.SCREEN_MUSIC]) from the activity. Uses the connected client
         * whichever service owns it; remembered so it is re-sent when a plugin
         * becomes ready later.
         */
        fun selectScreen(screen: Int) {
            active?.sendScreen(screen)
        }

        /** True while any client holds a live glasses link. */
        fun isLinked(): Boolean = active?.isReady == true

        /**
         * True once the glasses were handed to the official app. While set,
         * nothing reconnects until an explicit [connect]/[connectToAddress].
         */
        fun isYielded(): Boolean = yieldRequested

        /** True once the glasses side actually answered (plugin ready). */
        fun isPluginLive(): Boolean = (active?.cardioReady == true) || (active?.musicReady == true)

        /** Address of the glasses already picked, or null. */
        fun savedAddress(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_GLASSES_ADDR, null)

        /** Glasses picked by the user (or confirmed) and remembered. */
        fun hasConfirmed(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val addr = prefs.getString(PREF_GLASSES_ADDR, null)
            return !addr.isNullOrBlank() && prefs.getBoolean(PREF_GLASSES_CONFIRMED, true)
        }

        /**
         * Hand the glasses back to the official app from anywhere: stop both
         * services (so nothing reconnects behind the user's back) and release
         * the link. The official app picks the glasses up again.
         */
        fun yieldToOfficial(context: Context) {
            // Mark every client as yielded *before* the services stop, so a
            // service being torn down cannot reopen the link on its way out.
            yieldRequested = true
            active?.releaseLink("Rendre les lunettes à l'app officielle demandé.")
            MusicMirrorService.yieldFromGlasses(context)
        }

        /**
         * Close the link on purpose from the shell (app closed, or the config
         * tab's disconnect button). Unlike [yieldToOfficial] this does not
         * hand the glasses to the official app: the user simply stops using
         * them here, and pressing "Connecter" takes the link back.
         */
        fun disconnectForGood(context: Context, reason: String) {
            yieldRequested = true
            active?.releaseLink(reason)
                ?: run {
                    journalSink?.invoke(reason)
                    observers.forEach { runCatching { it.onGlassesDisconnected() } }
                }
        }

        /** Forget the remembered glasses so the next connect asks again. */
        fun forget(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREF_GLASSES_ADDR).remove(PREF_GLASSES_CONFIRMED).apply()
        }

        /** Persist the glasses the user picked once, for both tabs. */
        fun remember(context: Context, address: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_GLASSES_ADDR, address)
                .putBoolean(PREF_GLASSES_CONFIRMED, true)
                .apply()
        }
    }

    /** Drop this client's link, e.g. before a fresh connect. */
    fun closeLink() {
        disconnect()
    }

    /**
     * Hand the glasses back to the official MemoMind app.
     *
     * The glasses expose a single phone-facing link, so while MemOnly holds it
     * the official app cannot reach them. Saying goodbye to the plugin and
     * closing our socket releases that link at once; the official app (still
     * started, or started next) then reconnects on its own. No retry is
     * scheduled: the user pressed this on purpose, and reconnecting on our side
     * would take the link back from the official app.
     */
    fun releaseLink(reason: String) {
        val wasConnected = ready || spp != null || gatt != null
        if (wasConnected) yieldRequested = true
        journal(listener, reason)
        writeQueue.clear()
        // Best-effort goodbye so the plugin clears its scene before the link
        // goes to the official app.
        if (wasConnected) sendGoodbye()
        disconnect()
        linkUp = false
        listener.onGlassesDisconnected()
        journal(listener, "Lunettes rendues à l'app officielle MemoMind.")
    }

    /** Clear the music line, then give the stack a moment to push the frame. */
    private fun sendGoodbye() {
        runCatching { sendIdle() }
        runCatching { Thread.sleep(120) }
    }

    /**
     * Open the link right away without waiting for a nearby scan, so the
     * Config tab gets an answer at once: a remembered address is used
     * directly, and the picker is offered the moment there is one, instead of
     * only after the scan ran out.
     */
    @SuppressLint("MissingPermission")
    fun startLink() {
        if (yieldRequested) {
            journal(listener,
                "Lunettes rendues à l'app officielle : reconnexion dès que l'App officielle est fermée."
            )
            return
        }
        val ad = adapter ?: run {
            journal(listener, "Aucun adaptateur Bluetooth.")
            return
        }
        if (!ad.isEnabled) {
            journal(listener, "Bluetooth désactivé.")
            return
        }
        val saved = prefs.getString(PREF_GLASSES_ADDR, null)
        if (!saved.isNullOrBlank() && prefs.getBoolean(PREF_GLASSES_CONFIRMED, true)) {
            connectToAddress(saved)
            return
        }
        val bonded = bondedDevices()
        if (bonded.isNotEmpty()) {
            journal(listener, "Choisissez vos lunettes parmi les appareils appairés.")
            listener.onGlassesChooser(bonded)
            return
        }
        connect()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Set once the running glasses plugin has announced EVENT_READY. */
    @Volatile
    var cardioReady: Boolean = false
        private set

    /** Set once the running glasses music plugin has answered. */
    @Volatile
    var musicReady: Boolean = false
        private set

    /** Set once the glasses answered at all (unified plugin): ready to steer. */
    @Volatile
    var pluginReady: Boolean = false
        private set

    /** Latest screen requested by the activity, re-sent once the plugin is up. */
    @Volatile
    private var pendingScreen: Int = -1

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cardioSentAt = 0L
    /** Set once any payload was written for the current plugin session. */
    private var cardioStreaming = false
    private var lastCardioBpm = 0
    private var cardioPending: ByteArray? = null
    /**
     * The workout tracking frame (command 4) is not a heart-rate reading: it
     * carries the timer, speed and distance. Sharing [cardioPending] let a
     * throttled BPM reading overwrite a pending tracking frame (and the other
     * way round) while the screen was off, which froze the timer and the
     * speed on the HUD. Tracking therefore gets its own slot, always sent
     * before a pending reading.
     */
    private var trackingPending: ByteArray? = null

    private var cardioFlushPosted = false

    private val cardioFlush = Runnable {
        cardioFlushPosted = false
        val tracking = trackingPending
        val reading = cardioPending
        trackingPending = null
        cardioPending = null
        if (tracking != null) sendCardioPayload(tracking, bypassThrottle = true)
        if (reading != null) sendCardioPayload(reading, bypassThrottle = true)
    }

    private var trackSentAt = 0L
    private var trackPending: ByteArray? = null

    private val trackFlush = Runnable {
        val payload = trackPending ?: return@Runnable
        trackPending = null
        sendTrackPayload(payload)
    }

    // Conventional Bluetooth-base UUIDs; the firmware may expose a legacy alias,
    // so we also resolve characteristics by the 16-bit shortcut if needed.
    // Command downlink / response characteristics. The containing service UUID
    // may differ between firmware revisions, so we scan every service for these
    // characteristic UUIDs instead of assuming a fixed handle or service.
    private val commandCharUuid: UUID = UUID.fromString("00002021-0000-1000-8000-00805f9b34fb")
    private val responseUuid: UUID = UUID.fromString("00002022-0000-1000-8000-00805f9b34fb")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    /** UUIDs as printed by the firmware, for the diagnostic log. */
    private fun uuidText(u: UUID): String = u.toString().lowercase()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var ready = false
    private var mtu = 23
    private var linking = false
    private var linkCandidate = -1
    /** Set once the current GATT reached STATE_CONNECTED. */
    private var linkUp = false
    private var framesSent = 0
    private var uplinksSeen = 0
    /** Live Classic SPP link, when the glasses are reached over RFCOMM. */
    private var spp: BluetoothSocket? = null
    private var sppOut: OutputStream? = null
    private var sppReader: Thread? = null
    private val sppWriteLock = Any()
    /** TRANSPORT_AUTO retry already used for the current pick. */
    private var autoTransportTried = false
    /** Address of the current link attempt, for retries. */
    private var pickAddress: String? = null
    private val candidates = ArrayList<Pair<android.bluetooth.BluetoothDevice, String>>()
    /** Devices that advertise the GM service; always tried before [candidates]. */
    private val gmCandidates = ArrayList<Pair<android.bluetooth.BluetoothDevice, String>>()
    private val advertisedServices = ArrayList<String>()

    @Volatile
    private var writeBusy = false
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val eventId = AtomicInteger(1)

    val isReady: Boolean
        get() = ready

    fun nextEventId(): Int {
        val id = eventId.getAndIncrement()
        if (id > 0xEF) eventId.set(1)
        return id
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        // An explicit request takes the link back from the official app.
        yieldRequested = false
        val ad = adapter
        if (ad == null) {
            journal(listener, "Aucun adaptateur Bluetooth.")
            return
        }
        if (!ad.isEnabled) {
            journal(listener, "Bluetooth désactivé.")
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            journal(listener, "Scanner BLE indisponible.")
            return
        }
        // A re-connect must never stack on a live link: the old GATT would keep
        // its notifications and its queued writes alive next to the new one.
        val wasScanning = scanning
        if (gatt != null || scanning) disconnect()

        // If the user already picked the glasses once, go straight to them.
        val saved = prefs.getString(PREF_GLASSES_ADDR, null)
        if (!saved.isNullOrBlank() && prefs.getBoolean(PREF_GLASSES_CONFIRMED, true)) {
            journal(listener, "Connexion aux lunettes enregistrées ($saved)…")
            connectToAddress(saved)
            return
        }

        // The glasses do not advertise the GM service in every firmware, so a
        // scan cannot identify them. Phones that already know the glasses
        // (paired in Settings) get the picker straight away instead.
        val bonded = bondedDevices()
        if (bonded.isNotEmpty()) {
            journal(listener, "Choisissez vos lunettes parmi les appareils appairés.")
            listener.onGlassesChooser(bonded)
            return
        }

        // One glasses pick serves the whole app: when the user already chose
        // them in the other tab (not confirmed yet), reuse that pick instead of
        // asking again.
        val picked = prefs.getString(PREF_GLASSES_ADDR, null)
        if (!picked.isNullOrBlank() && prefs.getBoolean(PREF_GLASSES_CONFIRMED, false)) {
            journal(listener, "Connexion aux lunettes enregistrées ($picked)…")
            connectToAddress(picked)
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // If a scan was just torn down, let the stack release it before starting
        // a new one, otherwise startScan answers SCAN_FAILED_ALREADY_STARTED.
        val start = Runnable {
            scanning = true
            journal(listener, "Recherche des lunettes MemoMind…")
            // Devices advertising the GM service are queued first; anything
            // else is only tried as a fallback, so a stray peripheral in range
            // cannot be picked instead of the glasses, yet glasses that omit
            // the service UUID from their advertisement are still reachable.
            candidates.clear()
            gmCandidates.clear()
            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (e: SecurityException) {
                scanning = false
                journal(listener, "Autorisation Bluetooth manquante pour le scan.")
                return@Runnable
            } catch (e: IllegalStateException) {
                scanning = false
                journal(listener, "Scan des lunettes occupé, réessayez.")
                return@Runnable
            }
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }
        if (wasScanning) {
            handler.postDelayed(start, 600)
        } else {
            start.run()
        }
    }

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        // No GM-advertising device showed up: instead of linking to a random
        // peripheral, ask the user to pick the glasses — paired devices first,
        // then anything the scan saw.
        val scanned = orderedCandidates().map { it.first.address to it.second }
        stopScan()
        linking = false
        if (scanned.isEmpty()) {
            val bonded = bondedDevices()
            if (bonded.isEmpty()) {
                journal(listener, "Aucune lunette MemoMind trouvée. Appairez-les dans les réglages, puis réessayez.")
                return@Runnable
            }
            journal(listener, "Aucune lunette détectée. Choisissez parmi les appareils appairés.")
            listener.onGlassesChooser(bonded)
            return@Runnable
        }
        journal(listener, "Lunettes non détectées automatiquement. Choisissez-les dans la liste.")
        listener.onGlassesChooser(mergeBonded(scanned))
    }

    /** Paired devices first, then [scanned], without duplicates. */
    private fun mergeBonded(
        scanned: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val bonded = bondedDevices()
        val seen = bonded.mapTo(HashSet()) { it.first }
        val extra = scanned.filter { seen.add(it.first) }
        return bonded + extra
    }

    /**
     * Devices already paired with the phone (Settings › Bluetooth). The
     * glasses never advertise the GM service, so this list is the most
     * reliable way to find them: the user picks once, the address is kept.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Pair<String, String>> {
        val ad = adapter ?: return emptyList()
        if (!ad.isEnabled) return emptyList()
        val bonded = runCatching { ad.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.map { d ->
            val name = runCatching { d.name }.getOrNull()
            val label = if (name.isNullOrBlank()) d.address else name
            d.address to label
        }
    }

    /** The picker was dismissed: allow a fresh Connect attempt. */
    fun cancelChooser() {
        linking = false
    }

    /**
     * Connect directly to a known device address (a remembered or user-picked
     * glasses), skipping the scan. The address is persisted on success.
     */
    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        // An explicit request takes the link back from the official app.
        yieldRequested = false
        val ad = adapter ?: run {
            journal(listener, "Aucun adaptateur Bluetooth.")
            return
        }
        if (gatt != null || scanning) disconnect()
        val device = runCatching { ad.getRemoteDevice(address) }.getOrNull() ?: run {
            journal(listener, "Lunettes enregistrées $address indisponibles ; nouvelle recherche.")
            prefs.edit().remove(PREF_GLASSES_ADDR).apply()
            connect()
            return
        }
        linking = true
        linkCandidate = 0
        autoTransportTried = false
        pickAddress = address
        val name = runCatching { device.name }.getOrNull() ?: address
        logDeviceTraits(device)
        journal(listener, "Connexion à $name…")
        // A device bonded as CLASSIC (or DUAL) is reached over the Classic SPP
        // command service; its BLE GATT server is not exposed, which is why a
        // LE link ended on "Link failed (status 147)".
        if (device.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE) {
            connectSpp(device, name)
            return
        }
        openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Open the Classic SPP command channel. The documented service is
     * 0x7033, with the standard 0x1101 SPP UUID as the App's fallback;
     * both are tried secure first, then insecure.
     */
    @SuppressLint("MissingPermission")
    private fun connectSpp(device: android.bluetooth.BluetoothDevice, name: String) {
        val ad = adapter
        if (ad == null) {
            journal(listener, "Aucun adaptateur Bluetooth.")
            return
        }
        runCatching { ad.cancelDiscovery() }
        journal(listener, "Ouverture de la liaison Classic avec $name…")
        Thread {
            val sockets = listOf(
                UUID.fromString(UUID_GM_SPP) to true,
                UUID.fromString(UUID_SPP) to true,
                UUID.fromString(UUID_GM_SPP) to false
            )
            var lastError = "no RFCOMM service"
            for ((uuid, secure) in sockets) {
                val socket = try {
                    if (secure) {
                        device.createRfcommSocketToServiceRecord(uuid)
                    } else {
                        device.createInsecureRfcommSocketToServiceRecord(uuid)
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    continue
                }
                try {
                    runCatching { ad.cancelDiscovery() }
                    socket.connect()
                    handler.post { onSppUp(device, socket) }
                    return@Thread
                } catch (e: Exception) {
                    lastError = "status ${runCatching { e.message }.getOrNull()} ($uuid)"
                    runCatching { socket.close() }
                }
            }
            handler.post {
                linking = false
                journal(listener, "Liaison Classic échouée : $lastError")
                journal(listener, "Essai en Bluetooth LE…")
                openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            }
        }.start()
    }

    /**
     * Neither paired plugin announces itself until it has received something,
     * so once the link is up we say plainly that no plugin answered instead of
     * leaving the HUD blank without explanation.
     */
    private fun armPluginWait() {
        handler.removeCallbacks(pluginWait)
        pluginWaitAt = 0L
        handler.postDelayed(pluginWait, PLUGIN_WAIT_MS)
    }

    private var pluginWaitAt = 0L
    private val pluginWait = Runnable {
        if (pluginReady || cardioReady || musicReady || !ready) return@Runnable
        journal(listener, 
            "Le plugin MemOnly des lunettes n'a pas répondu. " +
                "Trames envoyées : $framesSent, remontées reçues : $uplinksSeen."
        )
    }

    /** SPP is up: this is a confirmed glasses link, start the heartbeat. */
    @SuppressLint("MissingPermission")
    private fun onSppUp(device: android.bluetooth.BluetoothDevice, socket: BluetoothSocket) {
        if (gatt != null) {
            runCatching { gatt?.close() }
            gatt = null
        }
        spp = socket
        sppOut = runCatching { socket.outputStream }.getOrNull()
        if (sppOut == null) {
            journal(listener, "La liaison Classic n'a pas de flux de sortie.")
            runCatching { socket.close() }
            spp = null
            linking = false
            return
        }
        linking = false
        linkUp = true
        ready = true
        cardioReady = false
        musicReady = false
        pluginReady = false
        lastCardioBpm = 0
        cardioStreaming = false
        mtu = SPP_MAX_FRAME_BYTES
        val addr = runCatching { device.address }.getOrNull()
        if (!addr.isNullOrBlank()) {
            prefs.edit()
                .putString(PREF_GLASSES_ADDR, addr)
                .putBoolean(PREF_GLASSES_CONFIRMED, true)
                .apply()
        }
        journal(listener, "Lunettes connectées en Classic SPP.")
        active = this
        startSppReader(socket)
        armPluginWait()
        writeRaw(byteArrayOf(0xFA.toByte(), 0x00, 0x00, 0x09, 0x01, 0x01, 0x01, 0x01, 0x06))
        writeBusy = false
        listener.onGlassesReady()
        fanOut { it.onGlassesReady() }
    }

    /** Read the SPP byte stream and hand complete GM frames to the parser. */
    private fun startSppReader(socket: BluetoothSocket) {
        sppReader?.interrupt()
        val input: InputStream = runCatching { socket.inputStream }.getOrNull() ?: return
        val reader = Thread {
            val buffer = ByteArray(1024)
            var acc = ByteArray(0)
            try {
                while (spp === socket) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    acc += buffer.copyOf(n)
                    acc = drainStream(acc)
                }
            } catch (e: Exception) {
                Log.d(tag, "SPP reader ended: ${e.message}")
            }
            handler.post {
                if (spp === socket) onSppLost()
            }
        }
        reader.isDaemon = true
        sppReader = reader
        reader.start()
    }

    /** Extract every complete physical frame from [acc]. */
    private fun drainStream(acc: ByteArray): ByteArray {
        var offset = 0
        while (acc.size - offset >= 4) {
            val head = acc[offset].toInt() and 0xFF
            if (head != GmFrame.FRAME_HEAD_FIRST && head < 0x01) {
                offset++
                continue
            }
            val logical = ((acc[offset + 1].toInt() and 0xFF) shl 16) or
                ((acc[offset + 2].toInt() and 0xFF) shl 8) or
                (acc[offset + 3].toInt() and 0xFF)
            if (logical < 9 || logical > SPP_MAX_FRAME_BYTES) {
                Log.w(tag, "SPP: unsupported frame length $logical")
                offset++
                continue
            }
            if (acc.size - offset < logical) break
            val frame = acc.copyOfRange(offset, offset + logical)
            offset += logical
            handleUplink(frame)
        }
        return if (offset == 0) acc else acc.copyOfRange(offset, acc.size)
    }

    private fun onSppLost() {
        spp = null
        sppOut = null
        ready = false
        cardioReady = false
        musicReady = false
        cardioPending = null
        trackingPending = null
        trackPending = null
        handler.removeCallbacks(cardioFlush)
        cardioFlushPosted = false
        handler.removeCallbacks(trackFlush)
        // A saved address that fails later is kept: the glasses may simply be
        // out of range, and re-picking them on every start would be noise.
        journal(listener, "Liaison des lunettes perdue.")
        listener.onGlassesDisconnected()
        fanOut { it.onGlassesDisconnected() }
    }

    /** Close any previous handle, then start a fresh link to [device]. */
    @SuppressLint("MissingPermission")
    private fun openGatt(device: android.bluetooth.BluetoothDevice, transport: Int) {
        // Detach first: the closing handle may still deliver a DISCONNECTED
        // callback, which would otherwise cancel the link we start next.
        val previous = gatt
        gatt = null
        if (previous != null) {
            runCatching { previous.disconnect() }
            runCatching { previous.close() }
        }
        linkUp = false
        gatt = device.connectGatt(context, false, gattCallback, transport)
    }

    /**
     * The glasses are a bonded, often dual-mode device. Printing its type,
     * bond state and classic UUIDs turns a bare "did not answer" into a
     * diagnosable line in the journal.
     */
    @SuppressLint("MissingPermission")
    private fun logDeviceTraits(d: android.bluetooth.BluetoothDevice) {
        val type = when (d.type) {
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC (SPP)"
            android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL (SPP first)"
            else -> "UNKNOWN"
        }
        val sppPresent = runCatching {
            d.uuids?.any {
                val t = it.uuid.toString().lowercase()
                t == UUID_GM_SPP || t == UUID_SPP
            } ?: false
        }.getOrNull() ?: false
        val bond = when (d.bondState) {
            android.bluetooth.BluetoothDevice.BOND_BONDED -> "bonded"
            android.bluetooth.BluetoothDevice.BOND_BONDING -> "bonding"
            else -> "not bonded"
        }
        journal(listener, "Cible de liaison ${d.address} ($type, $bond).")
        if (sppPresent) journal(listener, "Le téléphone voit un service SPP pour cet appareil.")
        Log.d(tag, "target ${d.address} type=$type bond=$bond spp=$sppPresent")
    }

    /**
     * True when the advertisement carries the GM control service, either under
     * its Bluetooth-base UUID or under the legacy alias the firmware may use.
     * Any other peripheral in range is ignored instead of being linked to.
     */
    private fun advertisesGmService(result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids
        if (uuids.isNullOrEmpty()) return false
        for (parcel in uuids) {
            val u = parcel.uuid ?: continue
            val text = uuidText(u)
            if (text.startsWith(GM_SERVICE_16)) return true
            if (text == LEGACY_GM_SERVICE) return true
        }
        return false
    }

    /** All candidates, GM-advertising ones first. */
    private fun orderedCandidates(): List<Pair<android.bluetooth.BluetoothDevice, String>> =
        gmCandidates + candidates

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device = result.device
            val gm = advertisesGmService(result)
            val seen = gmCandidates.any { it.first.address == device.address } ||
                candidates.any { it.first.address == device.address }
            if (!seen) {
                val name = runCatching { device.name }.getOrNull() ?: device.address
                if (gm) {
                    gmCandidates.add(device to name)
                    journal(listener, "Lunettes possibles : $name (annonce GM)")
                } else {
                    candidates.add(device to name)
                    Log.d(tag, "candidate ${device.address} (no GM service, fallback)")
                }
            }
            if (linking) return
            // Only commit to a link attempt once a GM-advertising device shows
            // up; otherwise keep scanning so a stray peripheral is not chosen.
            if (!gm && gmCandidates.isEmpty()) return
            stopScan()
            tryCandidate(0)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            journal(listener, "Scan des lunettes échoué (code $errorCode).")
        }
    }

    /** Stop the scan but leave the link attempt alone. */
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /**
     * Connect to candidate [index] and remember the others: a device that was
     * in range but is not the glasses (or that refuses the link) must not stop
     * the search, which is what happened when only the first hit was tried.
     */
    @SuppressLint("MissingPermission")
    private fun tryCandidate(index: Int) {
        val list = orderedCandidates()
        if (index >= list.size) {
            linking = false
            // A stale saved address that no longer answers must be forgotten so
            // the next Connect scans afresh and offers the picker again.
            if (prefs.contains(PREF_GLASSES_ADDR) && candidates.isEmpty() && gmCandidates.isEmpty()) {
                prefs.edit().remove(PREF_GLASSES_ADDR).apply()
                journal(listener, "Les lunettes enregistrées n'ont pas répondu ; elles ont été oubliées. Appuyez sur Connecter pour relancer le scan.")
                return
            }
            journal(listener, "Aucune lunette MemoMind n'a répondu. Réessayez en les réveillant.")
            return
        }
        linking = true
        linkCandidate = index
        val (device, name) = list[index]
        journal(listener, "$name trouvées. Connexion…")
        pickAddress = device.address
        openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /** One-shot retry of the same pick with TRANSPORT_AUTO. */
    @SuppressLint("MissingPermission")
    private fun retryAuto() {
        val ad = adapter ?: return
        val addr = pickAddress ?: return
        val device = runCatching { ad.getRemoteDevice(addr) }.getOrNull() ?: return
        linking = true
        linkCandidate = 0
        journal(listener, "Nouvelle tentative via le transport système…")
        openGatt(device, android.bluetooth.BluetoothDevice.TRANSPORT_AUTO)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // A closed BluetoothGatt can still deliver this callback after we
            // already opened a new link. Acting on it cancelled fresh attempts
            // ("No MemoMind glasses answered" right after picking them).
            if (g !== gatt) {
                Log.d(tag, "ignoring stale callback status=$status state=$newState")
                runCatching { g.close() }
                return
            }
            Log.d(tag, "connection status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    // status 133 can still accompany a usable link; keep going.
                    journal(listener, "Lunettes connectées. Découverte des services…")
                    g.requestMtu(185)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    cardioReady = false
                    musicReady = false
                    if (active === this@GlassesHudClient) active = null
                    pluginReady = false
                    pendingScreen = -1
                    cardioPending = null
                    trackingPending = null
                    trackPending = null
                    handler.removeCallbacks(cardioFlush)
        cardioFlushPosted = false
                    handler.removeCallbacks(trackFlush)
                    g.close()
                    gatt = null
                    val failed = status != BluetoothGatt.GATT_SUCCESS && !linkUp
                    if (failed) journal(listener, "Liaison échouée (statut $status).")
                    if (linking) {
                        linking = false
                        // A dual-mode bonded device may refuse TRANSPORT_LE
                        // outright; one AUTO retry covers that firmware.
                        if (failed && !autoTransportTried && linkCandidate <= 0 &&
                            !pickAddress.isNullOrBlank()
                        ) {
                            autoTransportTried = true
                            retryAuto()
                            return
                        }
                        tryCandidate(linkCandidate + 1)
                        return
                    }
                    journal(listener, "Lunettes déconnectées.")
                    if (advertisedServices.isNotEmpty()) {
                        journal(listener, "Services: ${advertisedServices.joinToString(", ")}")
                    }
                    listener.onGlassesDisconnected()
                    fanOut { it.onGlassesDisconnected() }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            Log.d(tag, "MTU is now $mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) {
                Log.d(tag, "ignoring stale discovery (status $status)")
                return
            }
            var write: BluetoothGattCharacteristic? = null
            var notify: BluetoothGattCharacteristic? = null
            advertisedServices.clear()
            for (service in g.services) advertisedServices.add(uuidText(service.uuid))
            if (status != BluetoothGatt.GATT_SUCCESS) {
                journal(listener, "Découverte des services échouée (statut $status).")
            }
            for (service in g.services) {
                Log.d(tag, "service ${uuidText(service.uuid)}")
                for (c in service.characteristics) {
                    Log.d(tag, "  char ${uuidText(c.uuid)} props=${c.properties}")
                }
                val w = service.getCharacteristic(commandCharUuid)
                if (w != null) write = w
                val n = service.getCharacteristic(responseUuid)
                if (n != null) notify = n
            }
            if (write == null) {
                // Not the glasses (or an old firmware): report what the device
                // actually exposes, then try the next scanned candidate.
                val summary = g.services.joinToString(", ") { uuidText(it.uuid) }
                Log.w(tag, "0x2021 missing; services=[$summary]")
                journal(listener, "Caractéristique de contrôle GM 0x2021 introuvable.")
                if (summary.isNotBlank()) journal(listener, "Services: $summary")
                linking = false
                runCatching { g.disconnect() }
                if (gatt === g) gatt = null
                // Direct picks have no scan list to walk through; offer the
                // paired devices again instead of failing silently.
                if (orderedCandidates().isEmpty()) {
                    pickAddress = null
                    val bonded = bondedDevices()
                    if (bonded.isNotEmpty()) {
                        journal(listener, "Cet appareil n'est pas celui des lunettes. Choisissez-en un autre.")
                        listener.onGlassesChooser(bonded)
                        return
                    }
                }
                tryCandidate(linkCandidate + 1)
                return
            }
            if (notify == null) {
                journal(listener, "Caractéristique de remontée GM 0x2022 introuvable.")
            }
            linking = false
            candidates.clear()
            gmCandidates.clear()
            commandChar = write
            if (notify != null) {
                g.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(cccdUuid)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }
            // Heartbeat (see guide): nine-byte status query, service 01 command 01.
            val heartbeat = byteArrayOf(
                0xFA.toByte(), 0x00, 0x00, 0x09, 0x01, 0x01, 0x01, 0x01, 0x06
            )
            writeRaw(heartbeat)
            ready = true
            cardioReady = false
            musicReady = false
            pluginReady = false
            lastCardioBpm = 0
            cardioStreaming = false
            // Confirmed glasses: remember them so future connects skip the scan.
            val confirmedAddress = runCatching { g.device.address }.getOrNull()
            if (!confirmedAddress.isNullOrBlank()) {
                prefs.edit()
                    .putString(PREF_GLASSES_ADDR, confirmedAddress)
                    .putBoolean(PREF_GLASSES_CONFIRMED, true)
                    .apply()
            }
            armPluginWait()
            active = this@GlassesHudClient
            listener.onGlassesReady()
            fanOut { it.onGlassesReady() }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeBusy = false
            drainWriteQueue()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleUplink(value)
        }

        // Android < 13 entry point; routed to the same parser.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleUplink(value)
        }
    }

    /**
     * Decode a GM frame coming from the glasses. Only the cardio readiness
     * announcement matters here: command 0x29, channel 0x4355, payload
     * `[version, EVENT_READY]`.
     */
    private fun handleUplink(frame: ByteArray) {
        uplinksSeen++
        if (frame.size < 9) {
            Log.d(tag, "uplink too short: ${frame.size}")
            return
        }
        val head = frame[0].toInt() and 0xFF
        val service = frame[5].toInt() and 0xFF
        val command = frame[6].toInt() and 0xFF
        Log.d(tag, "uplink #$uplinksSeen head=$head svc=$service cmd=$command size=${frame.size}")
        // Show the first few unusual uplinks so the journal explains silence.
        if (uplinksSeen <= 8 && (head != GmFrame.FRAME_HEAD_FIRST || service != GmFrame.SERVICE_PLUGIN)) {
            journal(listener, "Remontée #$uplinksSeen : head=$head svc=$service cmd=$command")
        }
        if (head != GmFrame.FRAME_HEAD_FIRST) return
        if (service != GmFrame.SERVICE_PLUGIN) return
        if (command != 0x29) return

        // A continuation frame starts with 0x01..; only the first one carries
        // the TLVs we need (the cardio payloads are tiny, so one frame is enough).
        val end = frame.size - 2
        var i = 7
        var channel = -1
        var data: ByteArray? = null
        while (i + 4 <= end) {
            val type = frame[i].toInt() and 0xFF
            val len = ((frame[i + 1].toInt() and 0xFF) shl 16) or
                ((frame[i + 2].toInt() and 0xFF) shl 8) or
                (frame[i + 3].toInt() and 0xFF)
            i += 4
            if (i + len > end) return
            when (type) {
                GmFrame.TLV_INT16 -> if (len == 2) {
                    channel = ((frame[i].toInt() and 0xFF) shl 8) or
                        (frame[i + 1].toInt() and 0xFF)
                }
                GmFrame.TLV_BYTES -> data = frame.copyOfRange(i, i + len)
            }
            i += len
        }

        val payload = data ?: return
        val wasReady = pluginReady
        pluginReady = true
        if (channel == GmFrame.CH_CARDIO_UP && payload.size >= 2 && payload[1].toInt() == 1) {
            if (!cardioReady) {
                cardioReady = true
                lastCardioBpm = 0
                cardioStreaming = false
                journal(listener, "Plugin cardio des lunettes prêt.")
                listener.onCardioReady()
                fanOut { it.onCardioReady() }
            }
            resendScreenOnceReady(wasReady)
            return
        }
        if (channel == GmFrame.CH_EVENT && payload.size >= 2) {
            if ((payload[1].toInt() and 0xFF) == GmFrame.EVENT_TOGGLE) {
                listener.onToggleRequest()
            }
            if (!musicReady) {
                musicReady = true
                journal(listener, "Plugin musique des lunettes prêt.")
                listener.onMusicReady()
                fanOut { it.onMusicReady() }
            }
            resendScreenOnceReady(wasReady)
            return
        }
        if (channel == GmFrame.CH_DEBUG) {
            reportGlassesDebug(payload)
            return
        }
        // Anything else on the plugin channel helps identify the payload layout.
        if (uplinksSeen <= 12) {
            val hex = payload.take(12).joinToString("") { "%02x".format(it) }
            journal(listener, "Plugin event ch=0x%04x data=$hex".format(channel))
        }
    }

    /**
     * Re-announce the last requested screen when a plugin answers: once right
     * away, then a few extra times. The glasses may have started the plugin
     * before our link was up, so a single frame can be lost.
     */
    /** Surface an app-side event in the journal through the listener. */
    fun reportJournal(message: String) {
        journal(listener, message)
    }

    private fun resendScreenOnceReady(wasReady: Boolean) {
        if (wasReady) return
        val screen = pendingScreen
        if (screen < 0) return
        sendNow(GmFrame.CH_SCREEN, GmFrame.screenSelect(screen))
        for (delay in SCREEN_RESEND_DELAYS_MS) {
            handler.postDelayed({
                if (ready) sendNow(GmFrame.CH_SCREEN, GmFrame.screenSelect(screen))
            }, delay)
        }
    }

    /**
     * Turn the temporary 0x4D58 diagnostics of the 1.0.4 debug glasses plugin
     * into journal lines so the phone shows what the glasses actually received.
     */
    private fun reportGlassesDebug(payload: ByteArray) {
        if (payload.size < 2) return
        for (i in 1 until payload.size) {
            val code = payload[i].toInt() and 0xFF
            val text = when {
                code >= GmFrame.DBG_SCREEN -> "DBG glasses screen=${code - GmFrame.DBG_SCREEN}"
                code == GmFrame.DBG_SCREEN_RX -> "DBG glasses got screen frame"
                code == GmFrame.DBG_CARDIO_RX -> "DBG glasses got cardio frame"
                code == GmFrame.DBG_TRACK_PROGRESS -> "DBG glasses got track progress"
                code == GmFrame.DBG_TRACK_META -> "DBG glasses got track meta"
                code == GmFrame.DBG_PROGRESS_DRAW -> "DBG glasses drew progress"
                code == GmFrame.DBG_CARDIO_MSG -> {
                    val cmd = if (i + 1 < payload.size) payload[i + 1].toInt() and 0xFF else -1
                    "DBG glasses got cardio cmd=$cmd"
                }
                else -> "DBG glasses code=0x%02x".format(code)
            }
            journal(listener, text)
        }
    }

    // ---- Paired cardio plugin ------------------------------------------

    /** Push the resting / maximum bands the glasses use for their zones. */
    fun sendCardioConfig(restingBpm: Int, maxBpm: Int) {
        if (!cardioReady) return
        sendCardioPayload(GmFrame.cardioConfig(restingBpm, maxBpm), bypassThrottle = true)
    }

    /**
     * Stream one reading to the cardio glasses plugin. Throttled to one payload
     * every [CARDIO_PUSH_INTERVAL_MS]; the newest value always wins so the
     * display never lags behind.
     */
    fun showCardioReading(
        bpm: Int,
        restingBpm: Int,
        maxBpm: Int,
        contactBad: Boolean,
        trendBpm: Int
    ) {
        if (!cardioReady) return
        // The very first reading of a session goes out at once: the plugin
        // starts by drawing "--", and a throttled first frame used to arrive
        // late enough to look like a dead link.
        val first = !cardioStreaming
        cardioStreaming = true
        // A cardio reading only exists while the workout is on screen: keep the
        // glasses on that screen too. The plugin ignores cardio frames for the
        // screen itself, so a selection from the phone is what puts it there.
        if (pendingScreen != GmFrame.SCREEN_CARDIO) sendScreen(GmFrame.SCREEN_CARDIO)
        val zone = GmFrame.cardioZone(bpm, restingBpm, maxBpm)
        // The glasses ignore the trend byte; sending the last value keeps the
        // wire identical to PhoneSDK/examples/cardio/protocol.js.
        val trend = if (lastCardioBpm > 0) lastCardioBpm else bpm
        lastCardioBpm = bpm
        sendCardioPayload(GmFrame.cardioHeartRate(bpm, zone, contactBad, trend), first)
    }

    /** Report the sensor state (see protocol.js STATE_*). */
    fun sendCardioStatus(state: Int) {
        if (!cardioReady) return
        sendCardioPayload(GmFrame.cardioStatus(state))
    }

    /**
     * Report the tracking metrics to the plugin (command 4). With [active]
     * false the glasses drop the metrics line; the values are ignored then.
     */
    fun sendCardioTracking(
        active: Boolean,
        elapsedMillis: Long,
        speedKmh: Double,
        distanceMeters: Double,
        noSpeed: Boolean = false
    ) {
        if (!cardioReady) return
        sendCardioPayload(
            GmFrame.cardioTracking(active, elapsedMillis / 1000, speedKmh, distanceMeters, noSpeed),
            isTracking = true
        )
    }

    // ---- Paired music plugin -------------------------------------------

    /**
     * Push a track payload to the glasses over [GmFrame.CH_TRACK], throttled so
     * rapid progress updates never flood the link. Progress frames coalesce:
     * only the latest one is kept while a flush is pending.
     */
    @Synchronized
    fun sendTrackPayload(payload: ByteArray) {
        if (!ready || !musicReady) return
        val now = System.currentTimeMillis()
        val elapsed = now - trackSentAt
        if (elapsed < TRACK_PUSH_INTERVAL_MS && trackPending != null) {
            trackPending = payload
            return
        }
        if (elapsed < TRACK_PUSH_INTERVAL_MS) {
            trackPending = payload
            handler.removeCallbacks(trackFlush)
            handler.postDelayed(trackFlush, TRACK_PUSH_INTERVAL_MS - elapsed)
            return
        }
        trackPending = null
        trackSentAt = now
        sendNow(GmFrame.CH_TRACK, payload)
    }

    /** Send a TRACK payload immediately, bypassing the throttle. */
    @Synchronized
    fun sendTrackNow(payload: ByteArray) {
        if (!ready) return
        trackPending = null
        handler.removeCallbacks(trackFlush)
        trackSentAt = System.currentTimeMillis()
        sendNow(GmFrame.CH_TRACK, payload)
    }

    /** Send a COVER payload immediately, bypassing the track throttle. */
    fun sendCoverPayload(payload: ByteArray) {
        if (!ready) return
        sendNow(GmFrame.CH_COVER, payload)
    }

    /** Tell the glasses nothing is playing. */
    fun sendIdle() {
        sendTrackNow(GmFrame.trackIdle())
    }

    /**
     * Ask the unified glasses plugin to switch to [screen]
     * ([GmFrame.SCREEN_CARDIO] or [GmFrame.SCREEN_MUSIC]). Remembered so it is
     * re-sent as soon as the plugin reports ready.
     */
    fun sendScreen(screen: Int) {
        pendingScreen = screen
        if (!ready) return
        sendNow(GmFrame.CH_SCREEN, GmFrame.screenSelect(screen))
    }

    private fun sendNow(channel: Int, payload: ByteArray) {
        GmFrame.pluginMessage(
            eventId = nextEventId(),
            channel = channel,
            payload = payload,
            maxFrameBytes = maxFrameForMtu()
        ).forEach { enqueue(it) }
    }

    private fun sendCardioPayload(
        payload: ByteArray,
        bypassThrottle: Boolean = false,
        isTracking: Boolean = false
    ) {
        if (!cardioReady) return
        val now = android.os.SystemClock.elapsedRealtime()
        val since = now - cardioSentAt
        if (bypassThrottle) {
            handler.removeCallbacks(cardioFlush)
            cardioFlushPosted = false
            cardioSentAt = now
            if (isTracking) trackingPending = null else cardioPending = null
            GmFrame.pluginMessage(
                eventId = nextEventId(),
                channel = GmFrame.CH_CARDIO_DOWN,
                payload = payload,
                maxFrameBytes = maxFrameForMtu()
            ).forEach { enqueue(it) }
            return
        }
        if (since < CARDIO_PUSH_INTERVAL_MS) {
            // Keep only the newest payload of each kind and schedule a single
            // flush for the exact time the interval ends (a full-interval delay
            // while frames keep arriving would starve the display forever).
            if (isTracking) trackingPending = payload else cardioPending = payload
            if (!cardioFlushPosted) {
                cardioFlushPosted = true
                handler.postDelayed(cardioFlush, CARDIO_PUSH_INTERVAL_MS - since)
            }
            return
        }
        cardioSentAt = now
        if (isTracking) trackingPending = null else cardioPending = null
        GmFrame.pluginMessage(
            eventId = nextEventId(),
            channel = GmFrame.CH_CARDIO_DOWN,
            payload = payload,
            maxFrameBytes = maxFrameForMtu()
        ).forEach { enqueue(it) }
    }


    /**
     * Max physical GM frame size that fits one ATT write: MTU minus the 3-byte
     * ATT header. Falls back to 20 bytes (MTU 23) when the MTU was not
     * negotiated higher.
     */
    private fun maxFrameForMtu(): Int {
        if (sppOut != null) return SPP_MAX_FRAME_BYTES
        return (mtu - 3).coerceAtLeast(20)
    }

    private fun enqueue(frame: ByteArray) {
        writeQueue.add(frame)
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeBusy) return
        if (sppOut == null) {
            if (gatt == null || commandChar == null) return
        }
        val frame = writeQueue.poll() ?: return
        writeBusy = true
        writeRaw(frame)
    }

    @SuppressLint("MissingPermission")
    private fun writeRaw(frame: ByteArray) {
        val out = sppOut
        if (out != null) {
            // Classic SPP is a byte stream: one complete GM frame per write.
            try {
                synchronized(sppWriteLock) { out.write(frame); out.flush() }
            } catch (e: Exception) {
                Log.w(tag, "SPP write failed", e)
                onSppLost()
                return
            }
            framesSent++
            writeBusy = false
            drainWriteQueue()
            return
        }
        val g = gatt ?: return
        val ch = commandChar ?: return
        framesSent++
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = frame
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (scanning) {
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        ready = false
        cardioReady = false
        musicReady = false
        if (active === this) active = null
        pluginReady = false
        pendingScreen = -1
        cardioPending = null
        trackingPending = null
        trackPending = null
        linking = false
        linkCandidate = -1
        linkUp = false
        pickAddress = null
        autoTransportTried = false
        candidates.clear()
        gmCandidates.clear()
        advertisedServices.clear()
        handler.removeCallbacks(cardioFlush)
        cardioFlushPosted = false
        handler.removeCallbacks(pluginWait)
        handler.removeCallbacks(scanTimeout)
        writeQueue.clear()
        sppReader?.interrupt()
        sppReader = null
        sppOut?.let { runCatching { it.close() } }
        sppOut = null
        spp?.let {
            spp = null
            runCatching { it.close() }
        }
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing glasses GATT", e)
            }
        }
        gatt = null
        linkUp = false
    }
}
