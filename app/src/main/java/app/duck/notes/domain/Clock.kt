package app.duck.notes.domain

/**
 * Wall-clock time as a domain port, so use cases that stamp timestamps stay deterministic under
 * test. Epoch milliseconds to match the schema — no `java.time` conversion layer.
 */
fun interface Clock {
    fun nowMillis(): Long
}
