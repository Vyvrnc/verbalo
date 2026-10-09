package cz.vyvrnc.transcribronpu.npu

/**
 * User replacement dictionary ("dejíčko => DI"): whole words or phrases, case-insensitive,
 * applied to the finished transcript in the order written.
 */
class Replacements(rules: List<Pair<String, String>>) {
    private val compiled = rules.map { (from, to) ->
        // Letters or digits right next to the match mean it is part of a longer word.
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])",
            setOf(RegexOption.IGNORE_CASE)) to to
    }

    fun apply(text: String): String =
        compiled.fold(text) { acc, (re, to) -> re.replace(acc, Regex.escapeReplacement(to)) }

    val isEmpty get() = compiled.isEmpty()
}
