package app.duck.notes.core.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset

/**
 * Durations and easings. Like [DuckSpacing] and [DuckShapes] these are not theme-varying — all four
 * themes move the same way — so they carry defaults.
 *
 * ## Why so little of the app animates
 *
 * Animation frequency decides whether to animate at all, and most of what Duck does is
 * high-frequency: selecting a note, tapping a sidebar row, typing. **Four surfaces animate**: the
 * Compact pane swap, sidebar item insert/remove, note-list item insert/remove, and the active bar.
 *
 * Press feedback does not — `selectable` already gives Material ripple, and a press-scale on top
 * would be two feedback mechanisms disagreeing about one tap. `DropdownMenu` and `AlertDialog` do
 * not either; Material 3 already animates them, origin-aware. The editor never animates on a
 * keystroke path.
 *
 * **The tag-tree chevron was planned as a fifth and dropped.** It cannot rotate: `DuckSidebar`
 * already carries the rule that expanded and collapsed must be *two distinct glyphs, not one
 * rotated*, so there is no rotation for a spec to animate. A planned surface lost to a decision that
 * was already recorded — worth naming here, because "five" outlived it in three places.
 *
 * ## Why these easings and not Compose's
 *
 * `FastOutSlowInEasing` and friends are Material's defaults and are too weak to read as intentional.
 * These are the stronger variants.
 *
 * **There is no `easeIn`, and that is a decision rather than an omission.** An ease-in delays the
 * initial movement — the exact moment the user is watching hardest — so it reads sluggish at any
 * duration. A dropdown at 300ms ease-in *feels* slower than the same 300ms ease-out.
 *
 * ## Reduced motion
 *
 * [none] is what the theme provides when the system asks for no animation. Every duration is zero and
 * the easings are untouched, so **call sites stay unconditional** — no consumer carries an
 * `if (reduceMotion)` branch to rot as surfaces are added. An easing applied over 0ms is a no-op.
 */
@Immutable
data class DuckMotion(
    /** Press-adjacent feedback and the active bar. */
    val fast: Int = 120,
    /** The default for anything entering or leaving. */
    val medium: Int = 200,
    /** Pane-sized movement, the largest thing that moves here. */
    val slow: Int = 280,
    /** Everything entering or exiting. Starts fast, so the interface answers immediately. */
    val easeOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f),
    /** Movement *on* screen, where something accelerates away and decelerates in. */
    val easeInOut: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f),
    /**
     * Exits only. **Accelerating, which is the one place an ease-in shape is right.**
     *
     * Arriving and leaving are different jobs. [easeOut] answers instantly because the user is
     * waiting on the thing that is appearing; an exit is the opposite — nobody is waiting for it, and
     * lingering at full opacity keeps dead state on screen while the new state is trying to be read.
     * So it starts slow and clears fast.
     *
     * This is *not* the `easeIn` deliberately absent from entrances. Same shape, opposite job.
     */
    val easeExit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
) {
    /**
     * Ready-made specs, because **call sites may not build their own** — `TokenSourceTest` bans
     * `tween(` and friends outside this package, on the same argument that bans colour literals: a
     * duration written at a call site is outside the contract and cannot be changed from one place.
     *
     * Generic in the animated type, since a spec is typed by what it animates and these serve
     * `Float`, `Color` and `IntOffset` alike. They are small value objects; building one per
     * recomposition costs less than remembering it.
     */
    fun <T> fastSpec(): TweenSpec<T> = tween(fast, easing = easeOut)

    fun <T> mediumSpec(): TweenSpec<T> = tween(medium, easing = easeOut)

    fun <T> slowSpec(): TweenSpec<T> = tween(slow, easing = easeOut)

    /** For something moving *across* the screen rather than entering or leaving it. */
    fun <T> movementSpec(): TweenSpec<T> = tween(medium, easing = easeInOut)

    /**
     * Anything leaving. Shorter than the matching entrance and on [easeExit] — an exit that takes as
     * long as an entrance reads as the interface hesitating.
     */
    fun <T> exitSpec(): TweenSpec<T> = tween(fast, easing = easeExit)

    /**
     * The active bar, **both directions**, because moving the selection is two events and not one.
     *
     * The first draft specified only the arriving half: the new row's bar grew in on [easeOut] and
     * the old row's bar left on whatever spec happened to be there — the same one, by accident rather
     * than decision. Selection moves tens of times a day, so the unspecified half was running as
     * often as the specified one.
     *
     * Symmetric now, and symmetric does not mean identical: growing in answers the tap and leads
     * ([easeOut]), shrinking out clears dead state and trails ([easeExit]). Both at [fast], so the
     * two halves are the same length and the crossover reads as one exchange rather than a stagger.
     *
     * **This is the cheap half of a choice, and the choice is recorded.** Two unrelated elements
     * animating oppositely is not the same as one indicator *translating* between rows, which is what
     * the motion actually depicts. That version — a single bar in the list's coordinate space, driven
     * by item offsets — is deferred, not rejected: it needs per-frame layout reads on a list already
     * under suspicion for the unresolved 60fps question, and it should be judged on a device first.
     */
    fun <T> activeBarSpec(selected: Boolean): TweenSpec<T> =
        if (selected) fastSpec() else exitSpec()

    /**
     * **A spring, not a tween, and not generic.** For `Modifier.animateItem`'s *placement* — rows
     * changing position in a list.
     *
     * Placement is the one animation here that is routinely **interrupted**: `SaveNote` restamps
     * `modifiedAt` on every save and the query is newest-first, so an edited note climbs the list
     * while it is still climbing. A tween restarts from zero on each interruption and the row
     * stutters; a spring carries its velocity across and stays smooth.
     *
     * This matches what `animateItem` already does by default — `spring(stiffness = 400f)`, i.e.
     * [Spring.StiffnessMediumLow], verified in the 1.9.4 bytecode rather than assumed. Passing tweens
     * for all three specs, as the first draft of this phase did, silently replaced that default and
     * made the most interruption-prone surface the least able to cope. The token layer exists to name
     * the platform's good default, not to overwrite it.
     *
     * Not generic, because placement is always `IntOffset` and the type is what lets the correct
     * [VisibilityThreshold] be passed — without it a spring settles on a threshold meant for pixels
     * as fractions and runs visibly long.
     */
    /**
     * Whether anything should animate at all — false under reduced motion.
     *
     * Exists because **a zero duration is not always the same as no animation.** `AnimatedContent`
     * with zero-length specs still keeps the outgoing content composed for two frames while its
     * transition settles; measured, not assumed. Call sites that can skip their animation machinery
     * entirely should branch on this rather than trusting a zero to be free.
     */
    val animates: Boolean get() = medium > 0

    fun placementSpec(): FiniteAnimationSpec<IntOffset> =
        // No duration means no animation: `snap` is the honest spring-shaped hole.
        if (medium == 0) {
            snap()
        } else {
            spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
        }

    companion object {
        /**
         * Every duration zero, easings intact.
         *
         * Not `DuckMotion(0, 0, 0)` at each call site: this is the one place the reduced form is
         * defined, so `MotionTest` can assert the easings survived it.
         */
        val none = DuckMotion(fast = 0, medium = 0, slow = 0)

        /**
         * The ceiling. A UI animation past this reads as slow no matter how good the curve is, and
         * `MotionTest` holds every duration under it.
         */
        const val MAX_DURATION_MS = 300
    }
}
