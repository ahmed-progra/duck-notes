package app.duck.notes.domain.repository

import app.duck.notes.domain.model.PreparedDocument
import app.duck.notes.domain.model.TransferReport

interface NoteTransferRepository {
    suspend fun prepareExport(noteIds: List<String>): PreparedDocument
    suspend fun prepareImport(sourceUri: String): String
    suspend fun importPrepared(id: String): TransferReport
    suspend fun prepareBackup(passphrase: CharArray): PreparedDocument
    suspend fun prepareRestore(sourceUri: String): String
    suspend fun authenticateRestore(id: String, passphrase: CharArray)
    suspend fun restorePrepared(id: String): TransferReport
    suspend fun writePrepared(id: String, destinationUri: String)
    suspend fun sharePrepared(document: PreparedDocument): String
    suspend fun discardPrepared(id: String)
}
