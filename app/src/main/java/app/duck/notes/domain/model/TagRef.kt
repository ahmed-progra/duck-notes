package app.duck.notes.domain.model

/**
 * One `note_tags` row, denormalised to the tag's **name** rather than its id.
 *
 * The name, because counts are keyed by *path* — `projects/duck/ui` contributes to `projects`,
 * `projects/duck` and itself — and an id says nothing about ancestry. `ObserveTagCounts` is the only
 * consumer.
 */
data class TagRef(val tagName: String, val noteId: String)
