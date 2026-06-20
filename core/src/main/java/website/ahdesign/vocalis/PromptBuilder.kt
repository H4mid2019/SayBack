package website.ahdesign.vocalis

internal val LANGUAGE_NAMES: Map<String, String> =
    mapOf(
        "bg" to "Bulgarian",
        "en" to "English",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish",
        "it" to "Italian",
        "ru" to "Russian",
        "uk" to "Ukrainian",
        "pl" to "Polish",
        "ro" to "Romanian",
        "tr" to "Turkish",
        "el" to "Greek",
        "ar" to "Arabic",
        "fa" to "Persian",
        "nl" to "Dutch",
        "pt" to "Portuguese",
        "sv" to "Swedish",
        "no" to "Norwegian",
        "fi" to "Finnish",
        "da" to "Danish",
        "cs" to "Czech",
        "hu" to "Hungarian",
        "sr" to "Serbian",
        "hr" to "Croatian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "et" to "Estonian",
        "mk" to "Macedonian",
        "sq" to "Albanian",
    )

/**
 * Build the LLM prompt for one transcript turn. Language-aware and script-aware.
 * Pure function — shared by the phone call path and the watch in-person path.
 */
fun buildPrompt(
    lang: String,
    script: ReplyScript,
    userContext: String,
    transcript: String,
): String {
    val langName = LANGUAGE_NAMES[lang] ?: lang

    val scriptInstruction =
        when (script) {
            ReplyScript.LATIN ->
                if (lang == "bg") {
                    """
                    - Write the reply in Latin letters (Romanized $langName) so the user can read it phonetically.
                    - Use this transliteration: ж→zh, ч→ch, ш→sh, щ→sht, ц→ts, й→y, ю→yu, я→ya, ъ→a.
                      Example: "Добър ден, Иван съм" → "Dobar den, Ivan sam".
                    - Do NOT use Cyrillic.
                    """.trimIndent()
                } else {
                    """
                    - Write the reply in Latin letters. If $langName uses a non-Latin script natively
                      (Cyrillic, Greek, Arabic, Persian, etc.), transliterate using the standard
                      romanization for that language. If $langName already uses Latin script, write it as-is.
                    """.trimIndent()
                }
            ReplyScript.NATIVE ->
                "- Write the reply in $langName's native script, exactly as a native speaker would write it."
            ReplyScript.CYRILLIC ->
                "- Write the reply in Cyrillic script. If $langName natively uses a different script, " +
                    "transliterate the reply phonetically to Cyrillic."
            ReplyScript.PERSIAN ->
                "- Write the reply in Perso-Arabic script (the alphabet used for Persian/Farsi). " +
                    "If $langName uses a different script natively, transliterate the reply phonetically " +
                    "to Perso-Arabic so a Persian reader can pronounce it."
        }

    return """
        You are helping a non-$langName speaker survive a $langName phone call in real time.
        The other party always speaks $langName. Use the user's context to answer accurately
        when the caller asks about the user (name, ID, address, accounts, situation, …).

        User context:
        $userContext

        Caller just said ($langName, may be partial/noisy):
        $transcript

        Return a single JSON object with exactly these two fields:
          "translation": short, plain-English translation of what the caller said.
          "reply":       the SHORTEST possible $langName reply the user can say.

        Reply rules:
        - Be as short as humanly possible. Prefer 1–3 words.
          Use one-word replies when sufficient: yes/no/thanks/one moment/I understand.
        - Only go longer when actual information must be conveyed (name, ID, amount, address).
          Even then, no filler — just the fact.
        $scriptInstruction
        - No quotes, no parentheses, no explanation in the reply.

        Output ONLY the JSON object. No prose, no code fences.
        """.trimIndent()
}

/**
 * Prompt for a one-line topic tag summarizing a finished session's transcripts. Reuses the
 * {translation, reply} JSON contract (tag goes in "reply") so [callOpenRouter]/[parseLlmAnswer]
 * can parse it unchanged.
 */
fun buildTagPrompt(
    lang: String,
    transcripts: List<String>,
): String {
    val langName = LANGUAGE_NAMES[lang] ?: lang
    val convo = transcripts.joinToString("\n")
    return """
        Here is what one side said during a $langName conversation:
        $convo

        Return a single JSON object with exactly one field:
          "reply": a SHORT 1-3 word English topic tag for this conversation
                   (e.g. "grocery store", "doctor visit", "taxi", "bank call").
        Output ONLY the JSON object. No prose, no code fences.
        """.trimIndent()
}
