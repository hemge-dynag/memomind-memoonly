package com.dynag.memoonly

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dynag.memoonly.databinding.FragmentConfigBinding

/**
 * MemOnly — config tab.
 *
 * One glasses connection for the whole app: this is the only place the user
 * picks the glasses, and the pick is remembered so neither the cardio nor the
 * music tab asks again. It also keeps the journal on screen permanently: every
 * line from either glasses client lands here, so a silent HUD can be explained
 * from the phone alone.
 */
class ConfigFragment : Fragment() {

    private var binding: FragmentConfigBinding? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastLog = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            connect()
        } else {
            toast(getString(R.string.msg_permissions_required))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentConfigBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        b.btnConnect.setOnClickListener { ensurePermissions { onConnectClicked() } }
        b.btnForget.setOnClickListener {
            GlassesHudClient.forget(requireContext())
            UiState.log(requireContext(), getString(R.string.msg_forgot_glasses))
            refreshState()
        }
        b.checkAutoConnect.isChecked = autoConnectEnabled(requireContext())
        b.checkAutoConnect.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(PREF_AUTO_CONNECT, checked).apply()
        }
        b.btnClearLog.setOnClickListener {
            UiState.clearLog(requireContext())
            lastLog = ""
            b.statusText.text = ""
        }
        b.btnCopyLog.setOnClickListener {
            val clipboard = requireActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("MemOnly", UiState.logText(requireContext()))
            )
            toast(getString(R.string.msg_log_copied))
        }
        refreshLog()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshLog()
            refreshState()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun refreshLog() {
        val b = binding ?: return
        val text = UiState.logText(requireContext())
        if (text != lastLog) {
            lastLog = text
            b.statusText.text = text
            b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshState() {
        val b = binding ?: return
        val ctx = requireContext()
        val linked = GlassesHudClient.isLinked()
        val addr = GlassesHudClient.savedAddress(ctx)
        b.linkStateText.setText(
            if (linked) R.string.glasses_state_connected else R.string.glasses_state_idle
        )
        b.linkStateText.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (linked) R.color.ok else R.color.text_secondary
            )
        )
        b.linkDetailText.text = when {
            addr.isNullOrBlank() && linked -> getString(R.string.glasses_detail_linked)
            addr.isNullOrBlank() -> getString(R.string.glasses_detail_none)
            linked -> getString(R.string.glasses_detail_with_addr, addr)
            else -> getString(R.string.glasses_detail_saved, addr)
        }
    }

    private fun onConnectClicked() {
        val ctx = requireContext()
        val addr = GlassesHudClient.savedAddress(ctx)
        if (addr.isNullOrBlank()) {
            pickGlasses()
        } else {
            connect()
        }
    }

    private fun connect() {
        val ctx = requireContext()
        val addr = GlassesHudClient.savedAddress(ctx)
        UiState.log(ctx, getString(R.string.msg_glasses_connecting))
        if (!addr.isNullOrBlank()) {
            GlassesHudClient.remember(ctx, addr)
        }
        // One link serves the whole app: the lightweight music client opens it
        // without starting a mirror, and the cardio service joins the same
        // client when a workout starts.
        MusicMirrorService.connectOnly(ctx)
        handler.postDelayed({
            if (isAdded) GlassesHudClient.selectScreen(UiState.screen(requireContext()))
        }, 1200L)
    }

    /**
     * First pairing only. The glasses rarely advertise the GM service, so the
     * phone's bonded list is the reliable source; the scanned list is added
     * when the user wants to look further.
     */
    @SuppressLint("MissingPermission")
    private fun pickGlasses() {
        val ctx = requireContext()
        val bonded = pairedDevices(ctx)
        val labels = bonded.map { it.second }
        if (labels.isEmpty()) {
            toast(getString(R.string.msg_no_bonded_glasses))
            UiState.log(ctx, getString(R.string.msg_no_bonded_glasses))
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.dialog_choose_glasses)
            .setItems(labels.toTypedArray()) { _, which ->
                val (address, label) = bonded[which]
                GlassesHudClient.remember(ctx, address)
                UiState.log(ctx, getString(R.string.msg_glasses_chosen, label))
                refreshState()
                connect()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun pairedDevices(ctx: Context): List<Pair<String, String>> {
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.map { d ->
            val name = runCatching { d.name }.getOrNull()
            d.address to (if (name.isNullOrBlank()) d.address else name)
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
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

    private fun prefs() =
        requireContext().getSharedPreferences(UiState.PREFS, Context.MODE_PRIVATE)

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PREF_AUTO_CONNECT = "auto_connect"

        fun autoConnectEnabled(context: Context): Boolean =
            context.getSharedPreferences(UiState.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_CONNECT, true)
    }
}
