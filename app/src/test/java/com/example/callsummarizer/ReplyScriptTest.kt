package com.example.callsummarizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReplyScriptTest {
    @Test
    fun `every enum value round-trips through fromId`() {
        ReplyScript.values().forEach { script ->
            assertThat(ReplyScript.fromId(script.id)).isEqualTo(script)
        }
    }

    @Test
    fun `unknown id falls back to LATIN (default)`() {
        assertThat(ReplyScript.fromId("nonsense")).isEqualTo(ReplyScript.LATIN)
    }

    @Test
    fun `null id falls back to LATIN`() {
        assertThat(ReplyScript.fromId(null)).isEqualTo(ReplyScript.LATIN)
    }

    @Test
    fun `display names are non-empty and distinct`() {
        val names = ReplyScript.values().map { it.displayName }
        assertThat(names).containsNoDuplicates()
        names.forEach { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `ids are stable kebab-case slugs (never rename without a migration)`() {
        // If you change these, existing users' saved settings will silently reset to LATIN.
        assertThat(ReplyScript.LATIN.id).isEqualTo("latin")
        assertThat(ReplyScript.NATIVE.id).isEqualTo("native")
        assertThat(ReplyScript.CYRILLIC.id).isEqualTo("cyrillic")
        assertThat(ReplyScript.PERSIAN.id).isEqualTo("persian")
    }
}
