package com.morty649.murmur

/** Fast, rule-based cleanup. Parakeet already outputs punctuation and capitals. */
object Cleanup {
    private val filler = Regex("""(?i)(?<![\w'])(?:u+h+m*|u+m+|e+r+m+|h+m+|m{2,}|a+h+)(?![\w'])[,.…]?""")
    private val spaceBeforePunct = Regex("""\s+([,.!?;:])""")
    private val commaBeforePunct = Regex(""",(?=\s*[,.!?;:])""")
    private val leadingPunct = Regex("""^[\s,.;:…]+""")
    private val multiSpace = Regex("""\s{2,}""")
    private val sentenceStart = Regex("""([.!?]\s+)(\p{Ll})""")

    fun apply(text: String, mode: CleanupMode): String {
        var t = text.trim()
        if (mode == CleanupMode.OFF || t.isEmpty()) return t
        t = filler.replace(t, "")
        t = multiSpace.replace(t, " ")
        t = spaceBeforePunct.replace(t, "$1")
        t = commaBeforePunct.replace(t, "")
        t = leadingPunct.replace(t, "")
        t = sentenceStart.replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        t = t.trim()
        return t.replaceFirstChar { it.uppercaseChar() }
    }
}
