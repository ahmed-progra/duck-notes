package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.markdown.TagSpan
import javax.inject.Inject

/**
 * Extracts tag names from a note body — what gets written to `note_tags` on every save.
 *
 * The syntax itself lives in [TagScanner], which the editor's highlighter and the CommonMark
 * extension also call. This is only the "names, deduped, first-appearance order" view of it, so
 * those three can never disagree about what a tag is.
 */
class ParseTags @Inject constructor() {

    /**
     * Pass [caret] to drop the tag **currently being typed**.
     *
     * `#w` is a complete, valid tag to [TagScanner] — nothing about it is "in progress" from the
     * parser's point of view — so before autosave existed no partial ever reached the database, and
     * after it, typing `#work` minted `w`, `wo` and `wor` as real rows on the way past. The caret is
     * the only thing that separates in-progress from finished, and it separates them *categorically*
     * rather than by a timeout: a span whose `end` is exactly the caret may still grow by one more
     * character. A settle timer cannot make that distinction — pause longer than the window mid-word
     * and the partial is minted anyway, which is precisely how the defect was reported.
     *
     * **This filters spans [TagScanner] produced; it does not describe what a tag is.** The syntax
     * stays in one place.
     *
     * **No bounds check, deliberately — the filter is total.** Every span end lies inside [body], so
     * a caret past `body.length`, or negative, matches nothing and the result is identical to a null
     * one. A range guard here would be dead code.
     *
     * ponytail: only the open form is caught. Typing `#multi word tag#`, at the instant the body
     * reads `#multi word` with the caret at 11, [TagScanner] returns `TagSpan(0, 6, "multi")` — the
     * open form ends at the space — so `6 != 11`, nothing is suppressed, and `multi` is minted until
     * the closing `#` lands. (`#multi` with the caret at 6 *is* suppressed; the space opens the
     * leak.) Closing it needs the scanner to report an unterminated closed-form candidate, a change
     * to a frozen module for the rarer form. Revisit if it bites.
     */
    operator fun invoke(body: String, caret: Int? = null): List<String> =
        TagScanner.scan(body)
            .filterNot { it.end == caret }
            .map(TagSpan::name)
            .distinct()
}
