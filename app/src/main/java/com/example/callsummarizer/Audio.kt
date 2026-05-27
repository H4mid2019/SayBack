package com.example.callsummarizer

import kotlin.math.sqrt

/**
 * RMS of a 16-bit little-endian PCM byte buffer. Returns 0.0 for empty/odd-length input.
 * Pure function — extracted out of CallService for testability.
 */
internal fun rms(
    buffer: ByteArray,
    length: Int,
): Double {
    if (length < 2) return 0.0
    var sum = 0.0
    var n = 0
    var i = 0
    while (i + 1 < length) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
        sum += sample.toDouble() * sample
        i += 2
        n++
    }
    return if (n == 0) 0.0 else sqrt(sum / n)
}

/** Frames below this RMS are dropped before hitting the WebSocket (digital noise floor). */
internal const val SILENCE_RMS_FLOOR = 30.0
