package com.dynag.memoonly

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dynag.memoonly.databinding.FragmentMusicBinding
import java.util.Locale

/**
 * MemoOnly — music tab.
 *
 * Reads the title, artist, cover art and playback position of whatever the
 * phone is playing, through the system MediaSession, and mirrors them onto the
 * MemoMind smart glasses HUD. The glasses side is a native plugin
 * (`GlassSDK/examples/music/music.c`) that draws the artwork, the title, the
 * artist and the progress bar itself.
 *
 * The fragment only drives the on-screen UI; the background mirror belongs to
 * the foreground [MusicMirrorService], which keeps streaming while the screen
 * is off.
 */
class MusicFragment : Fragment() {

    private var binding: FragmentMusicBinding? = null

    private lateinit var coverImage: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnAccess: Button
    private lateinit var btnGlasses: Button
    private lateinit var btnMirror: Button
    private lateinit var btnCopyLog: Button
    private lateinit var logHeader: View
    private lateinit var logToggle: TextView
    private lateinit var logPanel: View

    private lateinit var nowPlaying: NowPlaying
    private val handler = Handler(Looper.getMainLooper())

    /** True while the foreground [MusicMirrorService] is mirroring. */
    private var mirrorRunning = false
    private var lastFingerprint = ""
    private var lastCoverFingerprint = ""

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
        val b = FragmentMusicBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        coverImage = view.findViewById(R.id.coverImage)
        titleText = view.findViewById(R.id.titleText)
        artistText = view.findViewById(R.id.artistText)
        timeText = view.findViewById(R.id.timeText)
        statusText = view.findViewById(R.id.statusText)
        btnAccess = view.findViewById(R.id.btnAccess)
        btnGlasses = view.findViewById(R.id.btnGlasses)
        btnMirror = view.findViewById(R.id.btnMirror)
        btnCopyLog = view.findViewById(R.id.btnCopyLog)
        logHeader = view.findViewById(R.id.logHeader)
        logToggle = view.findViewById(R.id.logToggle)
        logPanel = view.findViewById(R.id.logPanel)

        nowPlaying = NowPlaying(requireContext())

        logHeader.setOnClickListener { toggleLog() }
        logToggle.setOnClickListener { toggleLog() }

        btnAccess.setOnClickListener {
            if (NowPlayingListener.isGranted(requireContext())) {
                appendStatus(getString(R.string.btn_access_ok))
            } else {
                NowPlayingListener.openSettings(requireContext())
            }
        }

        btnGlasses.setOnClickListener {
            ensurePermissions {
                if (mirrorRunning) {
                    MusicMirrorService.start(requireContext())
                } else {
                    toast(getString(R.string.msg_start_mirror_first))
                }
            }
        }

        btnMirror.setOnClickListener {
            ensurePermissions {
                if (mirrorRunning) stopMirror() else startMirror()
            }
        }

        btnCopyLog.setOnClickListener {
            val clipboard = requireActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MemoOnly", statusText.text))
            appendStatus(getString(R.string.msg_log_copied))
        }

        updateAccessButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    override fun onResume() {
        super.onResume()
        updateAccessButton()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---- mirror lifecycle ----------------------------------------------

    private fun startMirror() {
        if (!NowPlayingListener.isGranted(requireContext())) {
            toast(getString(R.string.msg_access_required))
            return
        }
        if (nowPlaying.snapshot() == null) {
            toast(getString(R.string.msg_no_music))
            return
        }
        MusicMirrorService.start(requireContext())
        mirrorRunning = true
        btnMirror.setText(R.string.btn_mirror_stop)
        appendStatus(getString(R.string.msg_mirror_started))
    }

    private fun stopMirror() {
        MusicMirrorService.stop(requireContext())
        mirrorRunning = false
        btnMirror.setText(R.string.btn_mirror)
        appendStatus(getString(R.string.msg_mirror_stopped))
    }

    private fun updateAccessButton() {
        btnAccess.setText(
            if (NowPlayingListener.isGranted(requireContext())) {
                R.string.btn_access_ok
            } else {
                R.string.btn_access
            }
        )
    }

    // ---- polling loop --------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun refreshNowPlaying() {
        val track = nowPlaying.snapshot()
        if (track == null) {
            titleText.setText(R.string.title_placeholder)
            artistText.setText(R.string.artist_placeholder)
            timeText.setText(R.string.time_placeholder)
            if (lastFingerprint.isNotEmpty()) {
                lastFingerprint = ""
                lastCoverFingerprint = ""
                coverImage.setImageDrawable(null)
            }
            return
        }
        titleText.text = track.title
        artistText.text = track.artist.ifBlank { getString(R.string.artist_placeholder) }
        timeText.text = formatTime(track.positionMs, track.durationMs)

        val fingerprint = "${track.title}|${track.artist}"
        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint
        }
        if (fingerprint != lastCoverFingerprint) {
            lastCoverFingerprint = fingerprint
            loadCover()
        }
    }

    private fun loadCover() {
        val cover = nowPlaying.coverNibbles(COVER_PREVIEW_EDGE)
        if (cover == null) {
            coverImage.setImageDrawable(null)
            return
        }
        val (shrunk, _) = cover
        coverImage.setImageBitmap(shrunk)
    }

    // ---- permissions ---------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
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

    // ---- helpers -------------------------------------------------------

    private fun toggleLog() {
        val b = binding ?: return
        val show = b.logPanel.visibility != View.VISIBLE
        b.logPanel.visibility = if (show) View.VISIBLE else View.GONE
        b.logToggle.setText(if (show) R.string.log_hide else R.string.log_show)
    }

    private fun appendStatus(line: String) {
        statusText.append(line + "\n")
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(positionMs: Long, durationMs: Long): String {
        val pos = positionMs.coerceAtLeast(0L) / 1000
        val total = durationMs.coerceAtLeast(0L) / 1000
        return String.format(
            Locale.getDefault(),
            "%d:%02d / %d:%02d",
            pos / 60, pos % 60, total / 60, total % 60
        )
    }

    companion object {
        private const val COVER_PREVIEW_EDGE = 480
    }
}
