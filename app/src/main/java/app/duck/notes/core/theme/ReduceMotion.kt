package app.duck.notes.core.theme

import android.animation.ValueAnimator
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The three system animation scales, all of which *Accessibility → Remove animations* writes.
 *
 * **All three, deliberately.** Measured on API 35: the toggle zeroes every one of them. Which key a
 * given Settings build writes is an implementation detail, and minSdk is 26 — eight API levels this
 * project cannot practically verify. Reading all three makes the question moot at every level rather
 * than answering it at one. (API 26–27 has no stock AOSP toggle at all, so only Developer Options
 * writes these there; the same read covers it.)
 *
 * The accepted cost: someone who zeroes *only* `window_animation_scale` in Developer Options also
 * loses Duck's in-app motion. A defensible reading of their intent, and cheaper than being wrong
 * about an OEM.
 */
private val ANIMATION_SCALE_KEYS = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
    Settings.Global.WINDOW_ANIMATION_SCALE,
)

/**
 * The accessibility half of the signal: any of the three persisted scales at zero.
 *
 * **Defaulted to 1f, and that is load-bearing.** On a device where the setting has never been touched
 * the key is absent — measured `null` on a fresh API 35 image — and the overload without a default
 * throws `SettingNotFoundException` there. Which is most devices.
 */
private fun accessibilityScalesZeroed(resolver: ContentResolver): Boolean =
    ANIMATION_SCALE_KEYS.any { Settings.Global.getFloat(resolver, it, 1f) == 0f }

/**
 * Both signals, OR'd. **Named for the system state rather than for "reduced motion"**, so the two
 * reasons a user can end up here stay distinguishable: an accessibility preference and a platform
 * condition are not the same fact about the user.
 *
 * ## What the second signal is, traced in AOSP source rather than taken secondhand
 *
 * - `ValueAnimator.areAnimatorsEnabled()` is `sDurationScale != 0`.
 * - `sDurationScale` is written only by `ValueAnimator.setDurationScale`, whose only callers live in
 *   `WindowManagerGlobal` — seeded from `IWindowManager.getCurrentAnimatorScale()` and pushed live
 *   through the window session's `onAnimatorScaleChanged`.
 * - So it reports **WindowManagerService's effective scale**, which can differ from anything
 *   persisted in `Settings.Global`.
 *
 * That makes it additive rather than redundant *in principle*: the Settings read covers OEM variation
 * in which key a toggle writes, and this covers a cause that writes no key at all.
 *
 * ## But it does not, in fact, catch battery saver — measured
 *
 * The javadoc claims it does: *"This can change if either the user sets a Developer Option to set the
 * animator duration scale to 0 or by Battery Savery mode being enabled"* (AOSP's own typo). **On API
 * 35 that is not true.** With `powerSaveMode=true`, `areAnimatorsEnabled()` still returns `true` and
 * all three Settings scales still read 1.0.
 *
 * The measurement is trustworthy because it has a positive control: an earlier probe reported `true`
 * unconditionally — including with `animator_duration_scale=0` — because **`sDurationScale` is only
 * populated in a process that owns a window**, and a bare instrumentation process never receives
 * `onAnimatorScaleChanged`. With an Activity present the probe correctly reports `false` at scale 0,
 * and still `true` under battery saver.
 *
 * **So battery saver remains a stated gap, not a covered case.** This call is kept anyway: it is one
 * static field read, and it is correct wherever the platform *does* lower the effective scale — other
 * versions, other OEMs, future policy. What was removed is the `ACTION_POWER_SAVE_MODE_CHANGED`
 * receiver that existed solely to react to it, since it would be machinery carried for an event
 * measured not to affect us.
 *
 * `areAnimatorsEnabled()` is **API 26**, exactly this project's minSdk, so no version guard.
 */
private fun animationsDisabledBySystem(resolver: ContentResolver): Boolean =
    accessibilityScalesZeroed(resolver) || !ValueAnimator.areAnimatorsEnabled()

/**
 * Whether the system wants animation, kept live for as long as this stays in composition.
 *
 * **Recomputed, never polled**, on two events:
 *
 * | trigger | covers |
 * |---|---|
 * | `ContentObserver` on the three scale URIs | the accessibility toggle, and Developer Options |
 * | `ON_RESUME` | anything that moved while Duck was backgrounded |
 *
 * A single read at the theme boundary would keep animating until process death after someone turns
 * the toggle on — and it is a setting people reach for *because* motion is bothering them right now,
 * so taking effect at next launch is close to not taking effect at all.
 *
 * **No `ACTION_POWER_SAVE_MODE_CHANGED` receiver.** It was written, then removed: battery saver was
 * measured not to change either signal on API 35, so it would be a registered receiver reacting to an
 * event that provably does not affect us. `ON_RESUME` covers the residual case — a platform lowering
 * the scale while Duck is foregrounded and unaware — at three lines instead of a receiver lifecycle.
 *
 * **Not used: `ValueAnimator.registerDurationScaleChangeListener`.** Public, and it would make the
 * animator half properly reactive, but it is **API 33** against a minSdk of 26. Recorded as the
 * upgrade path if minSdk ever rises.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    // Backed by snapshot state, or a change would update nothing — the whole point is recomposition.
    var disabled by remember(resolver) { mutableStateOf(animationsDisabledBySystem(resolver)) }

    DisposableEffect(context) {
        val recheck = { disabled = animationsDisabledBySystem(resolver) }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = recheck()
        }
        ANIMATION_SCALE_KEYS.forEach {
            resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer)
        }

        // Re-read now the observer is live: a change between the initial read and this line is
        // otherwise missed forever, because nothing will notify us about it.
        recheck()

        // One call clears all three registrations — they share this observer instance.
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    // Catches anything that moved while Duck was not in the foreground to hear about it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        disabled = animationsDisabledBySystem(resolver)
    }

    return !disabled
}
