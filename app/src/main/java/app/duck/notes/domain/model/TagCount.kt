package app.duck.notes.domain.model

/**
 * How many notes a tag path holds, counted **two ways, because two controls need different ones**.
 *
 * @param subtree the path and every descendant. What the sidebar row shows, because tapping the row
 *   filters the subtree — a count that did not match the list it opens is the defect this whole
 *   pairing exists to avoid.
 * @param own the exact tag only. What the **delete** dialog quotes, because delete does not cascade:
 *   quoting `subtree` there would promise to remove the tag from 12 notes and then remove it from 3.
 *
 * The two differ exactly when a parent has descendants, which is the case a user is most likely to
 * be deleting carefully.
 */
data class TagCount(val subtree: Int, val own: Int)
