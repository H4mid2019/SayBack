package com.example.callsummarizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LlmParserTest {
    @Test
    fun `well-formed JSON object parses both fields`() {
        val raw = """{"translation":"Say your name","reply":"Ivan sam"}"""
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEqualTo("Say your name")
        assertThat(out.reply).isEqualTo("Ivan sam")
    }

    @Test
    fun `whitespace around the JSON is trimmed`() {
        val raw = "   \n  {\"translation\":\"x\",\"reply\":\"y\"}  \n  "
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEqualTo("x")
        assertThat(out.reply).isEqualTo("y")
    }

    @Test
    fun `json code fence wrapper is stripped`() {
        val raw = "```json\n{\"translation\":\"a\",\"reply\":\"b\"}\n```"
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEqualTo("a")
        assertThat(out.reply).isEqualTo("b")
    }

    @Test
    fun `bare code fence wrapper is stripped`() {
        val raw = "```\n{\"translation\":\"a\",\"reply\":\"b\"}\n```"
        val out = parseLlmAnswer(raw)
        assertThat(out.reply).isEqualTo("b")
    }

    @Test
    fun `missing translation field becomes empty string`() {
        val raw = """{"reply":"Da"}"""
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEmpty()
        assertThat(out.reply).isEqualTo("Da")
    }

    @Test
    fun `missing reply field becomes empty string`() {
        val raw = """{"translation":"Hello"}"""
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEqualTo("Hello")
        assertThat(out.reply).isEmpty()
    }

    @Test
    fun `field values are trimmed`() {
        val raw = """{"translation":"   hi   ","reply":"  Da  "}"""
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEqualTo("hi")
        assertThat(out.reply).isEqualTo("Da")
    }

    @Test
    fun `non-JSON plain text falls back to reply-only`() {
        val raw = "Da"
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEmpty()
        assertThat(out.reply).isEqualTo("Da")
    }

    @Test
    fun `malformed JSON falls back to reply-only`() {
        val raw = """{"translation":"hi","reply":}"""
        val out = parseLlmAnswer(raw)
        assertThat(out.translation).isEmpty()
        assertThat(out.reply).isEqualTo(raw.trim())
    }

    @Test
    fun `empty input produces empty answer (fallback)`() {
        val out = parseLlmAnswer("")
        assertThat(out.translation).isEmpty()
        assertThat(out.reply).isEmpty()
    }
}
