package com.example.callsummarizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptBuilderTest {
    private val context = "User name: Ivan. EGN: 1234567890."
    private val transcript = "Кажете си името"

    @Test
    fun `prompt embeds user context and transcript verbatim`() {
        val out = buildPrompt("bg", ReplyScript.LATIN, context, transcript)
        assertThat(out).contains(context)
        assertThat(out).contains(transcript)
    }

    @Test
    fun `bulgarian + Latin includes the explicit transliteration table`() {
        val out = buildPrompt("bg", ReplyScript.LATIN, context, transcript)
        assertThat(out).contains("ж→zh")
        assertThat(out).contains("ч→ch")
        assertThat(out).contains("ъ→a")
        assertThat(out).contains("Do NOT use Cyrillic")
    }

    @Test
    fun `non-bulgarian + Latin gives generic romanization instruction`() {
        val out = buildPrompt("ru", ReplyScript.LATIN, context, transcript)
        assertThat(out).doesNotContain("ж→zh") // bulgarian-specific table only for bg
        assertThat(out).contains("Latin letters")
        // Whitespace-insensitive check (the prompt is multi-line so the words can wrap):
        val flattened = out.replace(Regex("\\s+"), " ")
        assertThat(flattened).contains("standard romanization")
    }

    @Test
    fun `NATIVE script asks for source-language script`() {
        val out = buildPrompt("bg", ReplyScript.NATIVE, context, transcript)
        assertThat(out).contains("Bulgarian's native script")
        assertThat(out).doesNotContain("Latin letters")
    }

    @Test
    fun `CYRILLIC script always asks for Cyrillic`() {
        val out = buildPrompt("de", ReplyScript.CYRILLIC, context, transcript)
        assertThat(out).contains("Cyrillic script")
        assertThat(out).contains("transliterate the reply phonetically to Cyrillic")
    }

    @Test
    fun `PERSIAN script always asks for Perso-Arabic`() {
        val out = buildPrompt("bg", ReplyScript.PERSIAN, context, transcript)
        assertThat(out).contains("Perso-Arabic")
        assertThat(out).contains("Persian reader can pronounce")
    }

    @Test
    fun `unknown language code uses the code itself as the name`() {
        val out = buildPrompt("zz", ReplyScript.LATIN, context, transcript)
        assertThat(out).contains("non-zz speaker")
    }

    @Test
    fun `known language codes resolve to human names`() {
        assertThat(buildPrompt("bg", ReplyScript.LATIN, "", "")).contains("Bulgarian")
        assertThat(buildPrompt("fa", ReplyScript.LATIN, "", "")).contains("Persian")
        assertThat(buildPrompt("ru", ReplyScript.LATIN, "", "")).contains("Russian")
        assertThat(buildPrompt("de", ReplyScript.LATIN, "", "")).contains("German")
    }

    @Test
    fun `prompt asks for JSON output, not prose`() {
        val out = buildPrompt("bg", ReplyScript.LATIN, context, transcript)
        assertThat(out).contains("\"translation\"")
        assertThat(out).contains("\"reply\"")
        assertThat(out).contains("Output ONLY the JSON object")
    }

    @Test
    fun `prompt enforces brevity in the reply`() {
        val out = buildPrompt("bg", ReplyScript.LATIN, context, transcript)
        assertThat(out).contains("SHORTEST possible")
        assertThat(out).contains("1–3 words")
    }
}
