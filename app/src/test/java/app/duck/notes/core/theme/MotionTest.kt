package app.duck.notes.core.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the motion family is *complete*, *bounded*, and *collapses correctly* — the three things about
 * it that can rot without anything else noticing.
 *
 * Deliberately not here: whether an animation looks right. No assertion can hold that, and pretending
 * otherwise is how a green suite starts meaning less than it says. That is judged at 10x animator
 * scale on a device, and the phase notes say so.
 */
class MotionTest {

    private val motion = DuckMotion()

    /** Every duration, by reflection, so a slot added later cannot dodge the rules below. */
    private fun durations(m: DuckMotion): Map<String, Int> =
        DuckMotion::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == Integer.TYPE && it.name.startsWith("get") }
            .associate { it.name.removePrefix("get").replaceFirstChar(Char::lowercase) to it.invoke(m) as Int }

    /**
     * Every easing, the same way and for the same reason.
     *
     * `startsWith("get")` matters: a data class also generates `componentN()` returning the same
     * type, so counting every `Easing`-returning method counts each property twice.
     */
    private fun easings(m: DuckMotion): Map<String, Easing> =
        DuckMotion::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType.name.endsWith("Easing") && it.name.startsWith("get") }
            .associate { it.name.removePrefix("get").replaceFirstChar(Char::lowercase) to it.invoke(m) as Easing }

    /**
     * The pinned count, mirroring `TokenTotalityTest`'s tripwire for the colour scheme: a new slot is
     * a deliberate act, and adding one should make somebody say so out loud.
     */
    @Test
    fun theFamilyHasThreeDurations() {
        assertEquals(
            "durations found: ${durations(motion).keys.sorted()}",
            3,
            durations(motion).size,
        )
    }

    /**
     * The ceiling. Past ~300ms a UI animation reads as slow however good the curve is, and this is the
     * rule most likely to erode one comfortable 50ms at a time.
     */
    @Test
    fun noDurationExceedsTheCeiling() {
        durations(motion).forEach { (name, ms) ->
            assertTrue(
                "$name is ${ms}ms, over the ${DuckMotion.MAX_DURATION_MS}ms ceiling",
                ms <= DuckMotion.MAX_DURATION_MS,
            )
        }
    }

    @Test
    fun durationsAreOrdered() {
        assertTrue("fast must be under medium", motion.fast < motion.medium)
        assertTrue("medium must be under slow", motion.medium < motion.slow)
    }

    /** Reduced motion is the *absence of duration*, not the absence of the token family. */
    @Test
    fun reducedMotionZeroesEveryDuration() {
        durations(DuckMotion.none).forEach { (name, ms) ->
            assertEquals("$name should be 0 under reduced motion", 0, ms)
        }
    }

    /**
     * ...and leaves the easings intact, which is what lets every call site stay unconditional.
     *
     * A spec built from a zeroed duration is instant whatever its curve, so the easings need not be
     * stripped — and stripping them would mean two shapes of `DuckMotion` for call sites to think
     * about instead of one.
     */
    @Test
    fun reducedMotionKeepsTheEasings() {
        assertEquals(motion.easeOut, DuckMotion.none.easeOut)
        assertEquals(motion.easeInOut, DuckMotion.none.easeInOut)
        assertEquals(motion.easeExit, DuckMotion.none.easeExit)
    }

    /**
     * The two curves must actually differ. They were typed by hand from four floats each, and a
     * copy-paste slip would leave the family looking complete while everything moved identically.
     */
    /**
     * The pinned easing count. **Moved from two to three deliberately** when `easeExit` was added —
     * this number is meant to change when a token is added on purpose, and to be noticed when it
     * changes by accident.
     */
    @Test
    fun theFamilyHasThreeEasings() {
        assertEquals("easings found: ${easings(motion).keys.sorted()}", 3, easings(motion).size)
    }

    @Test
    fun theEasingsAreThreeDistinctCurves() {
        val sampled = listOf(motion.easeOut, motion.easeInOut, motion.easeExit)
            .map { it.transform(0.5f) }
        // Sampled rather than compared by identity: two CubicBezierEasing instances are unequal as
        // objects whether or not they describe the same curve, so identity proves nothing.
        assertEquals("two curves behave identically at t=0.5: $sampled", 3, sampled.distinct().size)
    }

    /**
     * `easeExit` must **accelerate** — behind halfway at the halfway point — which is the property
     * that makes it wrong for entrances and right for exits. This is the assertion that would catch
     * someone "fixing" it to match [DuckMotion.easeOut].
     */
    @Test
    fun easeExitStartsSlowlyUnlikeEveryOtherCurveHere() {
        val atHalf = motion.easeExit.transform(0.5f)
        assertTrue("easeExit is $atHalf at t=0.5; an exit curve should still be under halfway", atHalf < 0.5f)
        assertTrue("easeExit should start slower than easeOut", atHalf < motion.easeOut.transform(0.5f))
    }

    /**
     * Placement is a **spring**, and stays one. `animateItem`'s own default is
     * `spring(stiffness = 400f)`; the first draft of this phase passed tweens for all three specs and
     * silently replaced it on the one surface that is routinely interrupted — list reorder, which
     * fires on every save. A tween restarts from zero when interrupted; a spring keeps its velocity.
     */
    @Test
    fun placementIsASpringSoInterruptedReordersDoNotRestart() {
        assertTrue(
            "placement must be a spring, was ${motion.placementSpec()::class.simpleName}",
            motion.placementSpec() is SpringSpec<*>,
        )
    }

    /** ...and collapses to an instant snap under reduced motion, since a spring has no duration. */
    @Test
    fun reducedMotionSnapsPlacementInsteadOfSpringing() {
        assertTrue(
            "reduced placement must not spring, was ${DuckMotion.none.placementSpec()::class.simpleName}",
            DuckMotion.none.placementSpec() is SnapSpec<*>,
        )
    }

    /**
     * The active bar's two halves must be **the same length and different curves**.
     *
     * This is the assertion for Option B. Equal durations, or the crossover staggers and reads as two
     * events; different easings, or the "symmetry" is just the arriving spec applied to a departure
     * that nobody specified — which is exactly the state this replaced.
     */
    @Test
    fun theActiveBarLeavesAndArrivesOnDifferentCurvesAtTheSameLength() {
        val arriving = motion.activeBarSpec<Float>(selected = true)
        val leaving = motion.activeBarSpec<Float>(selected = false)
        assertEquals("both halves must run for the same time", arriving.durationMillis, leaving.durationMillis)
        assertEquals(motion.easeOut, arriving.easing)
        assertEquals(motion.easeExit, leaving.easing)
    }

    /** ...and collapses with everything else, rather than being the one spec that outlives reduction. */
    @Test
    fun reducedMotionZeroesBothHalvesOfTheActiveBar() {
        listOf(true, false).forEach {
            assertEquals(0, DuckMotion.none.activeBarSpec<Float>(it).durationMillis)
        }
    }

    /** An exit must be shorter than the entrance it reverses, or the interface reads as hesitating. */
    @Test
    fun exitsAreShorterThanEntrances() {
        assertTrue("exit ${motion.fast}ms must be under entrance ${motion.medium}ms", motion.fast < motion.medium)
    }

    /**
     * `easeOut` must front-load its movement — that is the entire reason it is the default and
     * `easeIn` is absent from the family. Past halfway by the time half the clock has run.
     */
    @Test
    fun easeOutFrontLoadsItsMovement() {
        val atHalf = motion.easeOut.transform(0.5f)
        assertTrue("easeOut is $atHalf at t=0.5; it should be well past halfway", atHalf > 0.8f)
    }

    /**
     * Every curve is anchored **and monotonic** — by reflection, so a fourth cannot dodge it.
     *
     * ## What this replaces, and why the old one was decoration
     *
     * `bothEasingsStartAtZeroAndEndAtOne` asserted only the two anchors, and **could not fail**. A
     * cubic Bezier has fixed endpoints P0=(0,0) and P3=(1,1), so `transform(0f) == 0f` and
     * `transform(1f) == 1f` hold for *every* control-point choice. Proven rather than reasoned: an
     * audit planted `easeOut = CubicBezierEasing(0.9f, 0.1f, 0.9f, 0.1f)` — a curve wrong in every
     * way that matters — and it stayed green while [easeOutFrontLoadsItsMovement] and
     * [easeExitStartsSlowlyUnlikeEveryOtherCurveHere] went red. It also only looked at two of three
     * easings, having been written when there were two.
     *
     * **Monotonicity is the half that can actually fail.** Only the *x* control points are
     * constrained to [0, 1]; a *y* outside it is legal and produces a curve that runs backwards
     * mid-flight — an animation that visibly reverses on its way to a state it already passed. That
     * is a real defect a hand-typed bezier can introduce and nothing else here would catch.
     *
     * The anchor assertions stay: they are two comparisons, and they stop being free the moment an
     * easing is written as an `Easing { }` lambda rather than a Bezier, where they can fail.
     */
    @Test
    fun everyEasingIsAnchoredAndMonotonic() {
        easings(motion).forEach { (name, e) ->
            assertEquals("$name at t=0", 0f, e.transform(0f), 0.001f)
            assertEquals("$name at t=1", 1f, e.transform(1f), 0.001f)

            var previous = 0f
            for (step in 0..STEPS) {
                val t = step.toFloat() / STEPS
                val v = e.transform(t)
                assertTrue(
                    "$name runs backwards at t=$t: $v after $previous — a y control point outside " +
                        "[0,1] makes the animation reverse mid-flight",
                    v >= previous - TOLERANCE,
                )
                previous = v
            }
        }
    }

    private companion object {
        const val STEPS = 100
        const val TOLERANCE = 0.001f
    }
}
