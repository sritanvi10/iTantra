package com.isro.itantra.transport

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Wire format for text sent between two iTantra phones. Text-only (never
 * raw audio) is what gives the 20-800x bandwidth win over transmitting
 * audio, per the problem statement's core premise.
 *
 * Frame = 4-byte big-endian length prefix + UTF-8 JSON payload, sent over
 * a Bluetooth RFCOMM socket (see BluetoothTransportManager).
 */
data class ItantraMessage(
    val type: MessageType,
    val text: String = "",
    val langCode: String = "en",
    val isAlert: Boolean = false,
    val sentAtEpochMs: Long = System.currentTimeMillis(),
    /** Client clock offset-free round-trip id, used to measure the "sentence said" -> "sentence started as audio on other phone" latency metric. */
    val sequenceId: Long = 0L
) {
    enum class MessageType { TEXT, PTT_START, PTT_END, HEARTBEAT, MODE_SYNC }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("text", text)
        put("lang", langCode)
        put("alert", isAlert)
        put("ts", sentAtEpochMs)
        put("seq", sequenceId)
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): ItantraMessage {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            return ItantraMessage(
                type = MessageType.valueOf(json.getString("type")),
                text = json.optString("text", ""),
                langCode = json.optString("lang", "en"),
                isAlert = json.optBoolean("alert", false),
                sentAtEpochMs = json.optLong("ts", 0L),
                sequenceId = json.optLong("seq", 0L)
            )
        }
    }
}

/** Length-prefixed frame codec so messages don't get split/merged over the RFCOMM stream. */
object FrameCodec {
    fun encode(payload: ByteArray): ByteArray {
        val len = payload.size
        val header = byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(),
            (len ushr 8).toByte(), len.toByte()
        )
        return header + payload
    }

    /** Reads exactly one frame from [input], blocking. Returns null on stream close. */
    fun decodeOne(input: java.io.InputStream): ByteArray? {
        val header = ByteArray(4)
        if (!readFully(input, header)) return null
        val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
        if (len <= 0 || len > 10_000_000) return null
        val payload = ByteArray(len)
        if (!readFully(input, payload)) return null
        return payload
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }
}
