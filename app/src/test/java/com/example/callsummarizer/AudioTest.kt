package com.example.callsummarizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTest {
    @Test
    fun `rms of empty buffer is zero`() {
        assertThat(rms(ByteArray(0), 0)).isEqualTo(0.0)
    }

    @Test
    fun `rms of single byte (odd length) is zero`() {
        assertThat(rms(ByteArray(1), 1)).isEqualTo(0.0)
    }

    @Test
    fun `rms of all-zero samples is zero`() {
        val buf = ByteArray(64) // 32 samples of 0
        assertThat(rms(buf, buf.size)).isEqualTo(0.0)
    }

    @Test
    fun `rms of constant non-zero samples equals absolute value`() {
        val samples = ShortArray(100) { 1000 }
        val buf = samplesToBytes(samples)
        assertThat(rms(buf, buf.size)).isWithin(0.5).of(1000.0)
    }

    @Test
    fun `rms of alternating max-min equals max amplitude`() {
        val samples = ShortArray(100) { if (it % 2 == 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }
        val buf = samplesToBytes(samples)
        // RMS of ±32767 alternating = sqrt(32767^2) = 32767
        assertThat(rms(buf, buf.size)).isWithin(1.0).of(32767.0)
    }

    @Test
    fun `rms honors length parameter and ignores trailing bytes`() {
        val samples = ShortArray(100) { 5000 }
        val buf = samplesToBytes(samples)
        // Consider only the first 10 samples (20 bytes)
        assertThat(rms(buf, 20)).isWithin(0.5).of(5000.0)
    }

    @Test
    fun `silence floor constant is sane`() {
        // Should be well above electronic noise (~10-50) but below any audible speech (~200+)
        assertThat(SILENCE_RMS_FLOOR).isAtLeast(10.0)
        assertThat(SILENCE_RMS_FLOOR).isAtMost(100.0)
    }

    private fun samplesToBytes(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putShort(it) }
        return bb.array()
    }
}
