package app.duck.notes.domain.fake

import app.duck.notes.domain.Clock

/** A [Clock] the test moves by hand, so timestamp assertions are exact rather than approximate. */
class FixedClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now
}
