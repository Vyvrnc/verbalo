package cz.vyvrnc.transcribronpu.npu

/**
 * Spoken punctuation and layout commands ("lomeno" -> "/", "nový řádek" -> line break),
 * applied to the transcript before any cleanup. Deterministic on purpose: the small cleanup
 * model did not follow them reliably. Only unambiguous command words are listed (not "tečka"
 * or "čárka", which Whisper punctuates by itself and which are also ordinary words).
 */
object SpokenCommands {
    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"

    /** Whisper often wraps a command in its own commas or full stops: they go with it. */
    private fun command(words: String) =
        Regex("[ \\t]*[,.]?[ \\t]*$NOT_WORD_BEFORE(?:$words)$NOT_WORD_AFTER[,.:]?[ \\t]*", RegexOption.IGNORE_CASE)

    /** Line commands keep the punctuation before them (the end of the previous sentence). */
    private fun lineCommand(words: String) =
        Regex("[ \\t]*$NOT_WORD_BEFORE(?:$words)$NOT_WORD_AFTER[,.:]?[ \\t]*", RegexOption.IGNORE_CASE)

    private val czech = listOf(
        lineCommand("nový odstavec|novej odstavec|nový odstavce") to "\n\n",
        lineCommand("nový řádek|novej řádek|nový řádku|nový riadok") to "\n",
        command("lomeno|lomene") to "/",
        command("dvojtečka|dvojbodka") to ": ",
        command("středník|bodkočiarka") to "; ",
        command("otevřít závorku|levá závorka|otvoriť zátvorku") to " (",
        command("zavřít závorku|pravá závorka|zavrieť zátvorku") to ") ",
    )

    fun apply(text: String, language: String): String {
        if (language != "cs" && language != "sk") return text
        var out = czech.fold(text) { acc, (re, to) -> re.replace(acc, Regex.escapeReplacement(to)) }
        // Tidy up what the replacements leave behind.
        out = out.replace(Regex("\\( +"), "(").replace(Regex(" +\\)"), ")")
            .replace(Regex(" +([:;])"), "$1")
            .replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n[ \\t]+"), "\n")
            .replace(Regex(" {2,}"), " ")
        // A new line starts with a capital letter.
        out = Regex("\n(\\p{Ll})").replace(out) { "\n" + it.groupValues[1].uppercase() }
        return out.trim(' ', '\t')
    }
}
