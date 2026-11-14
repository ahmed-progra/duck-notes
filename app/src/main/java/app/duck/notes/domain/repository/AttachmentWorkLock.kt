package app.duck.notes.domain.repository

import kotlinx.coroutines.sync.Mutex

/** Single-process coordination between file snapshots, publication and deferred deletion recovery. */
internal object AttachmentWorkLock {
    val mutex = Mutex()
}
