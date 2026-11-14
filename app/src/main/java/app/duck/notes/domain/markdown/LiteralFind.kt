package app.duck.notes.domain.markdown

/** Linear-time, non-overlapping source offsets. Punctuation is always literal. */
object LiteralFind {
    fun matches(text: CharSequence, query: String, matchCase: Boolean): IntArray {
        if (query.isEmpty() || query.length > text.length) return intArrayOf()
        fun fold(c: Char) = if (matchCase) c else c.uppercaseChar().lowercaseChar()
        val needle = CharArray(query.length) { fold(query[it]) }
        val prefix = IntArray(needle.size)
        var matched = 0
        for (i in 1 until needle.size) {
            while (matched > 0 && needle[i] != needle[matched]) matched = prefix[matched - 1]
            if (needle[i] == needle[matched]) matched++
            prefix[i] = matched
        }
        var starts = IntArray(16)
        var count = 0
        matched = 0
        for (i in text.indices) {
            val c = fold(text[i])
            while (matched > 0 && c != needle[matched]) matched = prefix[matched - 1]
            if (c == needle[matched]) matched++
            if (matched == needle.size) {
                if (count == starts.size) starts = starts.copyOf(starts.size * 2)
                starts[count++] = i - needle.size + 1
                matched = 0
            }
        }
        return starts.copyOf(count)
    }
}
