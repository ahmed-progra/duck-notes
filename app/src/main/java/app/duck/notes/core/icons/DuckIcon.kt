package app.duck.notes.core.icons

import java.util.Locale

/**
 * Duck's tag icon set, and the rule mapping a tag name to one of them.
 *
 * **The table is keyed by icon, not by tag**, which is the whole point: it grows when a designer adds
 * a glyph, never when a user invents a tag. Eight entries today, growing toward ~60 without a line of
 * logic changing — [forTag] and its tests are set-size-independent.
 *
 * Matching is a **curated keyword table**: canonicalize, then exact-match. Deliberately not fuzzy —
 * no edit distance, no stemming, no semantic similarity. Plurals and synonyms are *listed*
 * (`task`, `tasks`) rather than derived, because a list is auditable and a stemmer is a source of
 * surprises nobody asked for.
 *
 * Contains **no Compose types**, so the resolver is a plain JVM unit test. The glyph lookup lives
 * next door in `DuckIconRes`.
 */
enum class DuckIcon(val keywords: Set<String>) {
    Todo(setOf("todo", "todos", "task", "tasks", "checklist", "done")),
    Work(setOf("work", "job", "office", "career", "business")),
    Home(setOf("home", "house", "personal", "life", "family")),
    Idea(setOf("idea", "ideas", "brainstorm", "thought", "thoughts", "inspiration")),
    Book(setOf("book", "books", "reading", "read", "study", "research")),
    Code(setOf("code", "dev", "programming", "tech", "software", "git")),
    People(setOf("people", "contacts", "team", "friends", "meeting", "meetings")),

    /**
     * The fallback, and the reason [forTag] can be total.
     *
     * Holds no keywords: it is what an unmatched tag gets, not something a tag can match into.
     * Deliberately not hash-derived — a hash would make two unrelated tags look meaningfully
     * different for no reason, and would reshuffle every user's icons the day the set grew.
     */
    Tag(emptySet()),
    ;

    companion object {

        /** Built once, lazily, from the enum. Keyword → icon; a duplicate keyword is a bug. */
        private val index: Map<String, DuckIcon> by lazy {
            entries.flatMap { icon -> icon.keywords.map { it to icon } }.toMap()
        }

        /** Exposed for `DuckIconTest`, which has to prove the index is not empty. */
        internal val keywordIndex: Map<String, DuckIcon> get() = index

        /**
         * The icon for a tag. **Total** — never null, never throws.
         *
         * 1. An explicit [iconKey] the user picked wins outright. Phase 7 exposes that picker; the
         *    value already exists in the Phase 2 schema.
         * 2. Otherwise split on `/` and resolve **leaf-first, then walk up**: `projects/duck/ui` tries
         *    `ui`, then `duck`, then `projects`. Leaf-first because the leaf is the row the user is
         *    looking at; walk-up rather than leaf-only because when the leaf is a proper noun the
         *    parent's icon is strictly more informative than the generic glyph, and it makes a parent
         *    visually group its children in the sidebar tree.
         * 3. Unmatched → [Tag].
         *
         * Inheriting an *ancestor row's stored* `iconKey` needs the whole tag tree and belongs to
         * Phase 7. This sees one name and one key.
         */
        fun forTag(name: String, iconKey: String? = null): DuckIcon {
            iconKey?.let { key -> entries.firstOrNull { it.name.equals(key, ignoreCase = true) } }
                ?.let { return it }

            return name.split('/')
                .asReversed()
                .firstNotNullOfOrNull { index[canonicalize(it)] }
                ?: Tag
        }

        /**
         * Lowercase and strip separators, so `to-do`, `to_do` and `to do` all become `todo`.
         *
         * A canonical form followed by an exact lookup — not fuzzy matching. The transform is
         * deterministic and total; two names either canonicalize to the same string or they do not.
         *
         * `internal` rather than `private` so `DuckIconTest.keywordsAreAlreadyCanonical` can call
         * **this** instead of retyping it. Its copy would have kept passing while this drifted, which
         * is the shape CLAUDE.md's Guards rule calls *drive the real thing*.
         */
        internal fun canonicalize(segment: String): String =
            segment.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }
}
