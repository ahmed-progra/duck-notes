package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.TagCount
import app.duck.notes.domain.model.TagRef
import app.duck.notes.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * How many notes each tag row holds, keyed by path — **counting descendants**.
 *
 * ## Why descendants
 *
 * Because that is what tapping the row shows. `ObserveNotes.byTagPath` filters the subtree, so a
 * count that excluded descendants would label a row `3` and open a list of twelve — and the number
 * sits directly beside the control that contradicts it. The inverse error is the milder one.
 *
 * ## Why distinct note ids, not a sum of child counts
 *
 * Summing is three lines shorter and wrong in a case that is not rare: a note carrying both
 * `#projects/duck` and `#projects/duck/ui` would count twice under `projects`, so the row would read
 * `2` and open a list of `1` — reintroducing the exact defect the with-descendants choice was made
 * to avoid, by way of the cheap implementation of it.
 *
 * ## Why this is Kotlin and not a `GROUP BY`
 *
 * **Intermediate segments have no `tags` row.** `personal` exists only because somebody wrote
 * `#personal/recipes`, so no aggregate over `tags` can produce a count for it. A Kotlin pass is
 * needed regardless, and one mechanism beats a SQL aggregate for stored tags plus a second
 * derivation for the intermediates.
 *
 * It also keeps `ObserveTagTree` untouched: counts arrive as their own flow, keyed by path, which
 * the ViewModel's existing `combine` absorbs.
 */
class ObserveTagCounts @Inject constructor(
    private val tags: TagRepository,
) {
    operator fun invoke(): Flow<Map<String, TagCount>> = tags.observeNoteRefs().map(::fold)

    /**
     * Each ref contributes its note id to its own path **and every ancestor prefix of it**, so
     * `projects/duck/ui` lands in the subtree sets for `projects`, `projects/duck` and itself — but
     * in the *own* set for `projects/duck/ui` alone.
     *
     * Sets rather than counters throughout, which is what makes a note tagged both `#projects/duck`
     * and `#projects/duck/ui` count **once** under `projects`. A counter would say 2 and the row
     * would open a list of 1.
     */
    private fun fold(refs: List<TagRef>): Map<String, TagCount> {
        val subtree = mutableMapOf<String, MutableSet<String>>()
        val own = mutableMapOf<String, MutableSet<String>>()
        for (ref in refs) {
            own.getOrPut(ref.tagName) { mutableSetOf() } += ref.noteId
            var path = ""
            for (segment in ref.tagName.split(SEPARATOR)) {
                if (segment.isEmpty()) continue
                path = if (path.isEmpty()) segment else "$path$SEPARATOR$segment"
                subtree.getOrPut(path) { mutableSetOf() } += ref.noteId
            }
        }
        // ponytail: O(note_tags) with a Set per path, recomputed per emission. Cheap next to
        // observeNotes, which already maps every note row INCLUDING full bodies on each emission.
        // Move to a SQL aggregate plus a recursive CTE for the intermediates if a trace shows it.
        return subtree.mapValues { (path, notes) ->
            TagCount(subtree = notes.size, own = own[path]?.size ?: 0)
        }
    }

    private companion object {
        const val SEPARATOR = "/"
    }
}
