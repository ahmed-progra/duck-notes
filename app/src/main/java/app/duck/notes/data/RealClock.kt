package app.duck.notes.data

import app.duck.notes.domain.Clock
import javax.inject.Inject

/** The production [Clock]. Tests bind a fixed one instead. */
class RealClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
