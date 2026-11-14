package app.duck.notes.data.transfer

import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import app.duck.notes.domain.model.TransferLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object BackupEncryption {
    private val configuration by lazy { StreamingAeadConfig.register(); RegistryConfiguration.get() }
    private val parameters = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB

    suspend fun encrypt(source: File, target: File, passphrase: CharArray) = output(target, passphrase) {
        if (source.length() !in 0..TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Invalid backup size")
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val handle = KeysetHandle.generateNew(parameters, configuration)
        val keyset = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get(), configuration)
        try {
            val wrappedSize = keyset.size + 16
            if (wrappedSize !in 17..4096) throw IOException("Unsupported backup keyset")
            val prefix = ByteBuffer.allocate(48).put("DUCKBKUP".toByteArray(Charsets.US_ASCII))
                .putInt(1).putInt(600_000).put(salt).put(iv).putInt(wrappedSize).array()
            val headerSize = 56L + wrappedSize
            RandomAccessFile(target, "rw").use { file ->
                file.setLength(headerSize)
                file.seek(headerSize)
                val encrypted = BoundedOutput(object : OutputStream() {
                    override fun write(value: Int) { file.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { file.write(bytes, offset, length) }
                }, TransferLimits.MAX_ARCHIVE_BYTES - headerSize)
                handle.getPrimitive(configuration, StreamingAead::class.java).newEncryptingStream(encrypted, prefix).use { stream ->
                    source.inputStream().use { copyTransfer(it, stream, TransferLimits.MAX_ARCHIVE_BYTES) }
                }
                currentCoroutineContext().ensureActive()
                val header = ByteBuffer.allocate(56).put(prefix).putLong(encrypted.count).array()
                val wrapped = wrappingCipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv, header).doFinal(keyset)
                file.seek(0)
                file.write(header)
                file.write(wrapped)
                file.fd.sync()
            }
        } finally { keyset.fill(0) }
    }

    suspend fun decrypt(source: File, target: File, passphrase: CharArray) = output(target, passphrase) {
        FileInputStream(source).use { input ->
            val size = input.channel.size()
            if (size !in 74..TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Invalid backup size")
            val header = ByteArray(56)
            DataInputStream(input).readFully(header)
            val data = ByteBuffer.wrap(header)
            val magic = ByteArray(8).also { data.get(it) }
            if (!magic.contentEquals("DUCKBKUP".toByteArray(Charsets.US_ASCII)) || data.int != 1 || data.int != 600_000) throw IOException("Unsupported backup format")
            val salt = ByteArray(16).also { data.get(it) }
            val iv = ByteArray(12).also { data.get(it) }
            val wrappedSize = data.int
            val bodySize = data.long
            if (wrappedSize !in 17..4096 || bodySize <= 0 || size != 56L + wrappedSize + bodySize) throw IOException("Invalid backup length")
            val wrapped = ByteArray(wrappedSize)
            DataInputStream(input).readFully(wrapped)
            val keyset = wrappingCipher(Cipher.DECRYPT_MODE, passphrase, salt, iv, header).doFinal(wrapped)
            val handle = try { TinkProtoKeysetFormat.parseKeyset(keyset, InsecureSecretKeyAccess.get(), configuration) }
                finally { keyset.fill(0) }
            if (handle.size() != 1 || handle.primary.key.parameters != parameters) throw IOException("Unsupported backup encryption")
            handle.getPrimitive(configuration, StreamingAead::class.java).newDecryptingStream(input, header.copyOfRange(0, 48)).use { stream ->
                FileOutputStream(target).use { output ->
                    // Tink authenticates segments separately: parsing must wait for authenticated EOF.
                    copyTransfer(stream, output, TransferLimits.MAX_ARCHIVE_BYTES)
                    if (input.channel.size() != size) throw IOException("Backup changed while reading")
                    output.fd.sync()
                }
            }
        }
    }

    private fun wrappingCipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray, header: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(header)
        } } finally { key.fill(0) }
    }

    private suspend fun output(target: File, passphrase: CharArray, action: suspend () -> Unit) {
        var owned = false
        try {
            withContext(Dispatchers.IO) {
                if (passphrase.size !in 1..1024) throw IOException("Invalid passphrase length")
                if (!target.createNewFile()) throw IOException("Backup staging collision")
                owned = true
                action()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (owned && target.exists() && !target.delete()) failure.addSuppressed(IOException("Backup cleanup unavailable"))
            }
            throw failure
        } finally { passphrase.fill('\u0000') }
    }
}
