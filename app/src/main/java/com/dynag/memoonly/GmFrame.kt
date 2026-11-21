package com.dynag.memoonly

/**
 * Encoder for the MemoMind GM (Glass Messaging) packet framing, plus the
 * application-message envelope used to feed the paired glasses plugins
 * (cardio and music).
 *
 * Reference: GlassSDK/docs/PROTOCOL.md (GM packet framing, Plugin application
 * service 0x0F) and GlassSDK/docs/HUD_PROTOCOL.md (application-message envelope).
 *
 * A logical GM packet:
 *   byte 0        frame head: 0xFA for the first frame, 0x01.. for continuations
 *   bytes 1..3    total logical packet length, unsigned big-endian (24-bit)
 *   byte 4        event ID
 *   byte 5        service ID
 *   byte 6        command ID
 *   bytes 7..n    TLVs / continuation payload
 *   last 2 bytes  additive checksum of all preceding frame bytes, mod 65536 (BE)
 *
 * TLV layout: one type byte, three-byte big-endian value length, then the value.
 *
 * The downlink for the glasses is:
 *   service 0x0F, command 0x28
 *   TLV1 INT16 (0x07) = application channel
 *   TLV2 BYTES (0x01) = application payload
 *
 * The same envelope carries both paired plugin protocols:
 *   cardio (GlassSDK/examples/cardio/cardio.c, PhoneSDK/examples/cardio/protocol.js)
 *     CH_CARDIO_DOWN 0x4344  phone -> glasses, CH_CARDIO_UP 0x4355 glasses -> phone
 *   music (GlassSDK/examples/music/music.c)
 *     TRACK 0x4D55  phone -> glasses, metadata / progress
 *     COVER 0x4D54  phone -> glasses, one LZ4-compressed GRAY_4 cover block
 *     EVENT 0x4D56  glasses -> phone, button requests (play/pause)
 *
 * Example wire vectors are validated against GlassSDK/docs/WIRE_EXAMPLES.md.
 */
object GmFrame {

    const val FRAME_HEAD_FIRST: Int = 0xFA
    const val SERVICE_PLUGIN: Int = 0x0F
    const val COMMAND_DELIVER: Int = 0x28
    const val COMMAND_EVENT: Int = 0x29

    // TLV types
    const val TLV_BYTES: Int = 0x01
    const val TLV_STRING: Int = 0x02
    const val TLV_INT32: Int = 0x03
    const val TLV_JSON: Int = 0x05
    const val TLV_STATUS: Int = 0x06
    const val TLV_INT16: Int = 0x07
    const val TLV_INT8: Int = 0x08

    // Paired cardio plugin channels (GlassSDK/examples/cardio/cardio.c and
    // PhoneSDK/examples/cardio/protocol.js). 'CD' phone -> glasses, 'CU' back.
    const val CH_CARDIO_DOWN: Int = 0x4344
    const val CH_CARDIO_UP: Int = 0x4355

    /** TRACKING flag: the sport has no pace, so the HUD only shows the timer. */
    const val TRACKING_NO_SPEED: Int = 0x02

    // ---------------------------------------------------------------------
    // Music plugin channels and constants (GlassSDK/examples/music/music.c)
    // ---------------------------------------------------------------------

    const val CH_TRACK: Int = 0x4D55
    const val CH_COVER: Int = 0x4D54
    const val CH_EVENT: Int = 0x4D56

    // Unified MemOnly glasses plugin: picks which screen is drawn. The glasses
    // cannot switch their foreground app over BLE, so the single plugin owns
    // both screens and the phone tells it which one to show.
    const val CH_SCREEN: Int = 0x4D57
    const val SCREEN_CARDIO: Int = 0
    const val SCREEN_MUSIC: Int = 1

    /** Temporary diagnostics channel of the 1.0.4 debug glasses plugin. */
    const val CH_DEBUG: Int = 0x4D58
    const val DBG_CARDIO_RX: Int = 0x10
    const val DBG_TRACK_PROGRESS: Int = 0x11
    const val DBG_TRACK_META: Int = 0x12
    const val DBG_PROGRESS_DRAW: Int = 0x13
    const val DBG_SCREEN: Int = 0x20
    const val DBG_SCREEN_RX: Int = 0x30
    const val DBG_CARDIO_MSG: Int = 0x58

    /** Screen-selection payload: `[version, screen]`. */
    fun screenSelect(screen: Int): ByteArray =
        byteArrayOf(PROTOCOL_VERSION.toByte(), (screen and 0xFF).toByte())

    const val PROTOCOL_VERSION: Int = 1

    const val TRACK_CMD_IDLE: Int = 0
    const val TRACK_CMD_META: Int = 1
    const val TRACK_CMD_PROGRESS: Int = 2

    const val EVENT_TOGGLE: Int = 1

    /** Max cover edge the glasses plugin accepts. */
    const val COVER_MAX = 240

    // ---------------------------------------------------------------------
    // Framing
    // ---------------------------------------------------------------------

    /**
     * Encode a single TLV (type + 24-bit big-endian length + value).
     */
    fun tlv(type: Int, value: ByteArray): ByteArray {
        val out = ByteArray(4 + value.size)
        out[0] = (type and 0xFF).toByte()
        out[1] = ((value.size ushr 16) and 0xFF).toByte()
        out[2] = ((value.size ushr 8) and 0xFF).toByte()
        out[3] = (value.size and 0xFF).toByte()
        System.arraycopy(value, 0, out, 4, value.size)
        return out
    }

    fun tlvInt16(type: Int, value: Int): ByteArray {
        return tlv(type, byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()))
    }

    fun tlvInt8(type: Int, value: Int): ByteArray {
        return tlv(type, byteArrayOf((value and 0xFF).toByte()))
    }

    fun tlvInt32(type: Int, value: Long): ByteArray {
        return tlv(type, byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        ))
    }

    /**
     * Build a complete, single physical GM frame from an event/service/command
     * and a body (already-encoded TLV stream).
     *
     * The returned frame includes the 0xFA head and the 2-byte additive checksum.
     * Callers must keep each physical frame within the negotiated BLE write limit.
     * This helper does not fragment; use [fragment] when the logical packet is
     * larger than the write limit.
     */
    fun singleFrame(eventId: Int, service: Int, command: Int, body: ByteArray): ByteArray {
        val logicalLen = 9 + body.size
        val frame = ByteArray(logicalLen)
        frame[0] = FRAME_HEAD_FIRST.toByte()
        frame[1] = ((logicalLen ushr 16) and 0xFF).toByte()
        frame[2] = ((logicalLen ushr 8) and 0xFF).toByte()
        frame[3] = (logicalLen and 0xFF).toByte()
        frame[4] = (eventId and 0xFF).toByte()
        frame[5] = (service and 0xFF).toByte()
        frame[6] = (command and 0xFF).toByte()
        System.arraycopy(body, 0, frame, 7, body.size)
        putChecksum(frame)
        return frame
    }

    /**
     * Fragment a logical body into one or more physical GM frames so that each
     * physical frame stays within [maxFrameBytes] (default 512, the SDK ceiling).
     * Each physical frame repeats the logical length, event, service and command
     * and carries its own checksum, per the SDK framing rules.
     *
     * For BLE, keep one complete physical frame per characteristic write and
     * callers should pace continuation frames in order (0x01, 0x02, ...).
     */
    fun fragment(
        eventId: Int,
        service: Int,
        command: Int,
        body: ByteArray,
        maxFrameBytes: Int = 512
    ): List<ByteArray> {
        require(maxFrameBytes > 9) { "maxFrameBytes must leave room for header+checksum" }
        val logicalLen = 9 + body.size
        val bodyPerFrame = maxFrameBytes - 9
        val frames = ArrayList<ByteArray>()
        var offset = 0
        var head = FRAME_HEAD_FIRST
        var first = true
        while (first || offset < body.size) {
            val remaining = body.size - offset
            val take = if (remaining > bodyPerFrame) bodyPerFrame else remaining
            val frame = ByteArray(7 + take + 2)
            frame[0] = (head and 0xFF).toByte()
            frame[1] = ((logicalLen ushr 16) and 0xFF).toByte()
            frame[2] = ((logicalLen ushr 8) and 0xFF).toByte()
            frame[3] = (logicalLen and 0xFF).toByte()
            frame[4] = (eventId and 0xFF).toByte()
            frame[5] = (service and 0xFF).toByte()
            frame[6] = (command and 0xFF).toByte()
            System.arraycopy(body, offset, frame, 7, take)
            putChecksum(frame)
            frames.add(frame)
            offset += take
            first = false
            head = if (head == FRAME_HEAD_FIRST) 0x01 else (head + 1)
        }
        return frames
    }

    private fun putChecksum(frame: ByteArray) {
        var sum = 0
        for (i in 0 until frame.size - 2) {
            sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFFFF
        }
        frame[frame.size - 2] = ((sum ushr 8) and 0xFF).toByte()
        frame[frame.size - 1] = (sum and 0xFF).toByte()
    }

    // ---------------------------------------------------------------------
    // Paired plugin delivery (service 0x0F, command 0x28)
    // ---------------------------------------------------------------------

    /** Deliver an application payload to the running glasses plugin on [channel]. */
    fun pluginMessage(
        eventId: Int,
        channel: Int,
        payload: ByteArray,
        maxFrameBytes: Int = 512
    ): List<ByteArray> {
        val body = tlvInt16(TLV_INT16, channel) + tlv(TLV_BYTES, payload)
        return fragment(eventId, SERVICE_PLUGIN, COMMAND_DELIVER, body, maxFrameBytes)
    }

    private fun putU32(dst: ByteArray, at: Int, v: Int) {
        dst[at] = ((v ushr 24) and 0xFF).toByte()
        dst[at + 1] = ((v ushr 16) and 0xFF).toByte()
        dst[at + 2] = ((v ushr 8) and 0xFF).toByte()
        dst[at + 3] = (v and 0xFF).toByte()
    }

    // ---------------------------------------------------------------------
    // Cardio protocol payloads (matching cardio.c)
    // ---------------------------------------------------------------------

    /**
     * Cardio protocol version 1, command HEART_RATE (1).
     *
     * Payload: `version, 1, bpm_lo, bpm_hi, zone, (contactBad ? 1 : 0), trend`.
     * The last byte is ignored by the glasses; they run their own one-second
     * trend sampler.
     */
    fun cardioHeartRate(bpm: Int, zone: Int, contactBad: Boolean, trend: Int): ByteArray {
        val value = bpm.coerceIn(0, 65535)
        return byteArrayOf(
            1,
            1,
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (zone and 0xFF).toByte(),
            (if (contactBad) 1 else 0).toByte(),
            (trend.coerceIn(0, 255) and 0xFF).toByte()
        )
    }

    /** Payload: `version, 2 STATUS, state` (state 0 idle ... 4 error). */
    fun cardioStatus(state: Int): ByteArray =
        byteArrayOf(1, 2, (state and 0xFF).toByte())

    /** Payload: `version, 3 CONFIG, resting, maximal`. */
    fun cardioConfig(resting: Int, maximal: Int): ByteArray = byteArrayOf(
        1,
        3,
        (resting.coerceIn(0, 255) and 0xFF).toByte(),
        (maximal.coerceIn(0, 255) and 0xFF).toByte()
    )

    /**
     * Cardio protocol version 1, command TRACKING (4).
     *
     * Payload: `version, 4, flags, time_s (4 LE), speed_0.1km/h (2 LE),
     * distance_m (4 LE)`.
     * `flags` bit 0 = tracking active. When clear the remaining fields are
     * still sent (zeros) so the glasses hide the metrics line.
     * `flags` bit 1 = sport without speed: the plugin shows only the timer.
     */
    fun cardioTracking(
        active: Boolean,
        elapsedSec: Long,
        speedKmh: Double,
        distanceMeters: Double,
        noSpeed: Boolean = false
    ): ByteArray {
        val flags = (if (active) 1 else 0) or (if (noSpeed) TRACKING_NO_SPEED else 0)
        val sec = elapsedSec.coerceIn(0L, 0xFFFFFFFFL)
        val dkmh = (speedKmh * 10.0).toLong().coerceIn(0L, 65535L)
        val dist = distanceMeters.toLong().coerceIn(0L, 0xFFFFFFFFL)
        return byteArrayOf(
            1,
            4,
            flags.toByte(),
            (sec and 0xFF).toByte(),
            ((sec ushr 8) and 0xFF).toByte(),
            ((sec ushr 16) and 0xFF).toByte(),
            ((sec ushr 24) and 0xFF).toByte(),
            (dkmh and 0xFF).toByte(),
            ((dkmh ushr 8) and 0xFF).toByte(),
            (dist and 0xFF).toByte(),
            ((dist ushr 8) and 0xFF).toByte(),
            ((dist ushr 16) and 0xFF).toByte(),
            ((dist ushr 24) and 0xFF).toByte()
        )
    }

    /**
     * Karvonen-style band used by the glasses (see `compute_zone()` in
     * `cardio.c`): 0 none, 1 rest, 2 moderate, 3 effort, 4 maximum.
     */
    fun cardioZone(bpm: Int, resting: Int, maximal: Int): Int {
        val reserve = maximal - resting
        if (reserve < 20) return 0
        val low = resting + 5
        if (bpm <= low) return 1
        if (bpm < resting + reserve * 50 / 100) return 2
        if (bpm < resting + reserve * 70 / 100) return 3
        return 4
    }

    // ---------------------------------------------------------------------
    // Music protocol payloads (big-endian, matching music.c)
    // ---------------------------------------------------------------------

    /** TRACK IDLE: tell the glasses nothing is playing. */
    fun trackIdle(): ByteArray = byteArrayOf(PROTOCOL_VERSION.toByte(), TRACK_CMD_IDLE.toByte())

    /**
     * TRACK META:
     *   [ver][cmd][trackId:u32][durationMs:u32][positionMs:u32][playing:u8]
     *     [titleLen:u8][artistLen:u8][title utf8][artist utf8]
     * Lengths are byte counts after UTF-8 encoding; the glasses clamp them.
     */
    fun trackMeta(
        trackId: Int,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        title: String,
        artist: String
    ): ByteArray {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val artistBytes = artist.toByteArray(Charsets.UTF_8)
        val tLen = minOf(titleBytes.size, 255)
        val aLen = minOf(artistBytes.size, 255)
        val out = ByteArray(18 + tLen + aLen)
        out[0] = PROTOCOL_VERSION.toByte()
        out[1] = TRACK_CMD_META.toByte()
        putU32(out, 2, trackId)
        putU32(out, 6, durationMs.coerceIn(0L, 0xFFFFFFFFL).toInt())
        putU32(out, 10, positionMs.coerceIn(0L, 0xFFFFFFFFL).toInt())
        out[14] = if (playing) 1 else 0
        out[15] = tLen.toByte()
        System.arraycopy(titleBytes, 0, out, 16, tLen)
        out[17 + tLen] = aLen.toByte()
        System.arraycopy(artistBytes, 0, out, 18 + tLen, aLen)
        return out
    }

    /** TRACK PROGRESS: [ver][cmd][trackId:u32][positionMs:u32][playing:u8]. */
    fun trackProgress(trackId: Int, positionMs: Long, playing: Boolean): ByteArray {
        val out = ByteArray(11)
        out[0] = PROTOCOL_VERSION.toByte()
        out[1] = TRACK_CMD_PROGRESS.toByte()
        putU32(out, 2, trackId)
        putU32(out, 6, positionMs.coerceIn(0L, 0xFFFFFFFFL).toInt())
        out[10] = if (playing) 1 else 0
        return out
    }

    /**
     * COVER payload: [ver][w:u16][h:u16][decoded:u32] followed by a single raw
     * LZ4 block of GRAY_4 nibbles (row-major, high nibble = even x). The
     * decoded byte count is ((w+1)/2)*h.
     */
    fun coverPayload(width: Int, height: Int, lz4Block: ByteArray): ByteArray {
        val stride = (width + 1) / 2
        val decoded = stride * height
        val out = ByteArray(9 + lz4Block.size)
        out[0] = PROTOCOL_VERSION.toByte()
        out[1] = ((width ushr 8) and 0xFF).toByte()
        out[2] = (width and 0xFF).toByte()
        out[3] = ((height ushr 8) and 0xFF).toByte()
        out[4] = (height and 0xFF).toByte()
        putU32(out, 5, decoded)
        System.arraycopy(lz4Block, 0, out, 9, lz4Block.size)
        return out
    }
}
