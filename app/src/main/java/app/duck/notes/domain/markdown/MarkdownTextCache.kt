package app.duck.notes.domain.markdown

import java.util.concurrent.atomic.AtomicReference

/**
 * One immutable scan of a whole body. Never mutated in place — a new text produces a new instance.
 *
 * [lineStarts] and [lineCarry] are parallel to [lines]; `lineCarry[i]` is the carry state *entering*
 * line `i`, which is what lets a rescan start mid-document instead of at line 0.
 */
data class ScanResult(
    val text: String,
    val lines: List<String>,
    val lineStarts: List<Int>,
    val lineTokens: List<List<Token>>,
    val lineCarry: List<LineCarry>,
) {
    val tokens: List<Token> get() = lineTokens.flatten()

    companion object {
        /**
         * The scan of an empty body — which is one empty line, not zero lines. It has to be a
         * genuinely correct result and not a placeholder, because the cache returns it directly on a
         * text hit; a sentinel that merely claimed `text == ""` would hand out a scan that a full
         * rescan disagrees with.
         */
        val Empty = ScanResult("", listOf(""), listOf(0), listOf(emptyList()), listOf(LineCarry()))
    }
}

/**
 * Keeps the scan of a note body current across keystrokes without rescanning the note.
 *
 * ## Why this is built the way it is
 *
 * This is read from inside a Compose `OutputTransformation`, which the runtime may invoke more than
 * once per frame and across snapshots. So there are two hard rules here:
 *
 * 1. **Nothing mutates in place.** [ScanResult] is immutable and [rescan] is a pure function. The
 *    only mutable cell is one [AtomicReference] holding a whole result, swapped atomically. The
 *    worst a concurrent or repeated invocation can do is recompute and publish an equal value —
 *    there is no torn state to observe.
 * 2. **A repeat call with identical text writes nothing at all.** [scan] returns the cached instance
 *    directly on a text hit, so re-invocation is a pure read, never a diff-and-mutate.
 *
 * This is deliberately *not* Compose snapshot state: writing snapshot state from inside a
 * transformation risks an invalidation loop.
 */
class MarkdownTextCache {

    private val cached = AtomicReference(ScanResult.Empty)

    /** The scan of [text], reusing whatever of the previous scan still applies. */
    fun scan(text: String): ScanResult {
        val previous = cached.get()
        if (previous.text == text) return previous
        return rescan(previous, text).also { cached.set(it) }
    }

    companion object {

        /**
         * Pure. Rescans only the lines that changed, then keeps going while the carry state entering
         * each following line differs from what was cached — that ripple is how opening a fence
         * restyles the rest of the note without a full rescan of an unchanged one.
         */
        fun rescan(previous: ScanResult, text: String): ScanResult {
            val (lines, starts) = splitLinesWithOffsets(text)

            // Lines are identical up to `firstDirty`, so their tokens and carry are still valid.
            var firstDirty = 0
            val shared = minOf(lines.size, previous.lines.size)
            while (firstDirty < shared &&
                lines[firstDirty] == previous.lines[firstDirty] &&
                starts[firstDirty] == previous.lineStarts[firstDirty]
            ) {
                firstDirty++
            }
            // Only entry carries are stored, so there is no carry available *past* the last cached
            // line. Back up one line and rescan it — cheap, and always correct.
            if (firstDirty >= previous.lineCarry.size) firstDirty = maxOf(0, previous.lineCarry.size - 1)

            // Reuse only a fully unchanged suffix at the same absolute offsets.
            var reusableFrom = lines.size
            if (lines.size == previous.lines.size) {
                while (reusableFrom > firstDirty &&
                    lines[reusableFrom - 1] == previous.lines[reusableFrom - 1] &&
                    starts[reusableFrom - 1] == previous.lineStarts[reusableFrom - 1]
                ) {
                    reusableFrom--
                }
            }

            val tokens = ArrayList<List<Token>>(lines.size)
            val carries = ArrayList<LineCarry>(lines.size)
            for (i in 0 until firstDirty) {
                tokens += previous.lineTokens[i]
                carries += previous.lineCarry[i]
            }

            var carry = if (firstDirty == 0) LineCarry() else previous.lineCarry[firstDirty]
            var i = firstDirty
            while (i < lines.size) {
                // Matching carry makes the verified suffix safe to keep without rescanning.
                if (i > firstDirty &&
                    i >= reusableFrom &&
                    carry == previous.lineCarry[i]
                ) {
                    while (i < lines.size) {
                        tokens += previous.lineTokens[i]
                        carries += previous.lineCarry[i]
                        i++
                    }
                    break
                }
                carries += carry
                val (lineTokens, next) = MarkdownScanner.scanLine(lines[i], starts[i], carry)
                tokens += lineTokens
                carry = next
                i++
            }
            return ScanResult(text, lines, starts, tokens, carries)
        }

        /**
         * Splits on `\n`, `\r\n` and `\r`, keeping empty trailing lines, and returns each line's
         * absolute start offset alongside it.
         *
         * Offsets come from the split itself rather than being derived from line lengths, because a
         * CRLF break is two characters — deriving them would silently shift every offset after the
         * first Windows line ending in the note.
         */
        fun splitLinesWithOffsets(text: String): Pair<List<String>, List<Int>> {
            val lines = mutableListOf<String>()
            val starts = mutableListOf<Int>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') {
                    lines += text.substring(start, i)
                    starts += start
                    i = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                    start = i
                } else {
                    i++
                }
            }
            lines += text.substring(start)
            starts += start
            return lines to starts
        }
    }
}
