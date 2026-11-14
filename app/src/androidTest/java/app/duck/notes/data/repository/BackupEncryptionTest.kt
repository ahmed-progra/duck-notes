package app.duck.notes.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.transfer.BackupEncryption
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupEncryptionTest {
    private lateinit var directory: File
    private val password get() = "a portable research notebook".toCharArray()
    @Before fun setUp() { directory = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "crypto-${UUID.randomUUID()}").apply { check(mkdir()) } }
    @After fun close() { directory.deleteRecursively() }

    /** Independent JCA unwrap and Tink reader pin the actual format, KDF, cipher and segment size. */
    @Test fun actualEnvelopeUsesTheSpecifiedAlgorithmsAndFreshSecrets() = runBlocking {
        val bytes = ByteArray(1_100_000) { (it * 37).toByte() }
        val source = File(directory, "source").apply { writeBytes(bytes) }
        val encrypted = File(directory, "one.duckbackup")
        val secret = password
        BackupEncryption.encrypt(source, encrypted, secret)
        assertTrue(secret.all { it == '\u0000' })
        val all = encrypted.readBytes()
        val header = ByteBuffer.wrap(all)
        val magic = ByteArray(8).also { header.get(it) }
        assertEquals("DUCKBKUP", String(magic, Charsets.US_ASCII))
        assertEquals(1, header.int)
        assertEquals(600_000, header.int)
        val salt = ByteArray(16).also { header.get(it) }
        val iv = ByteArray(12).also { header.get(it) }
        val wrappedSize = header.int
        assertTrue(wrappedSize in 17..4096)
        val length = header.long
        assertEquals(all.size.toLong() - 56 - wrappedSize, length)
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        key.fill(0)
        cipher.updateAAD(all.copyOfRange(0, 56))
        val keyset = cipher.doFinal(all.copyOfRange(56, 56 + wrappedSize))
        StreamingAeadConfig.register()
        val config = RegistryConfiguration.get()
        val handle = TinkProtoKeysetFormat.parseKeyset(keyset, InsecureSecretKeyAccess.get(), config)
        keyset.fill(0)
        assertEquals(1, handle.size())
        assertEquals(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB, handle.primary.key.parameters)
        val decoded = handle.getPrimitive(config, StreamingAead::class.java)
            .newDecryptingStream(all.inputStream().apply { skip(56L + wrappedSize) }, all.copyOfRange(0, 48)).use { it.readBytes() }
        assertArrayEquals(bytes, decoded)
        val second = File(directory, "two.duckbackup")
        BackupEncryption.encrypt(source, second, password)
        val next = second.readBytes()
        assertFalse(all.copyOfRange(16, 32).contentEquals(next.copyOfRange(16, 32)))
        assertFalse(all.copyOfRange(32, 44).contentEquals(next.copyOfRange(32, 44)))
        assertFalse(all.contentEquals(next))
        val restored = File(directory, "restored")
        BackupEncryption.decrypt(encrypted, restored, password)
        assertArrayEquals(bytes, restored.readBytes())
    }

    /** Plants: omit keyset AAD, stream AAD, final EOF authentication, or exact-length checking. */
    @Test fun wrongPasswordsHeaderChangesLateTamperingAndTruncationLeaveNoPlaintext() = runBlocking {
        val source = File(directory, "source").apply { writeBytes(ByteArray(1_100_000) { it.toByte() }) }
        val encrypted = File(directory, "valid")
        BackupEncryption.encrypt(source, encrypted, password)
        val valid = encrypted.readBytes()
        suspend fun rejected(bytes: ByteArray, pass: CharArray = password) {
            val input = File(directory, "invalid").apply { writeBytes(bytes) }
            val target = File(directory, "must-not-exist")
            var failed = false
            try { BackupEncryption.decrypt(input, target, pass) } catch (_: Exception) { failed = true }
            assertTrue("Unauthenticated input accepted", failed)
            assertFalse("Unauthenticated plaintext retained", target.exists())
            assertTrue(pass.all { it == '\u0000' })
        }
        rejected(valid, "wrong passphrase".toCharArray())
        for (offset in listOf(0, 11, 15, 16, 32, 47, 55, 60, valid.lastIndex - 25)) {
            rejected(valid.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() })
        }
        rejected(valid.copyOf(valid.size - 1))
        rejected(valid + byteArrayOf(0))
        val restored = File(directory, "positive-control")
        BackupEncryption.decrypt(encrypted, restored, password)
        assertArrayEquals(source.readBytes(), restored.readBytes())
    }

    /** Plant: delete a target this operation did not create when exclusive creation fails. */
    @Test fun aStagingCollisionCannotOverwriteOrDeleteAnExistingFile() = runBlocking {
        val source = File(directory, "source").apply { writeText("Synthetic contents", Charsets.UTF_8) }
        val encrypted = File(directory, "encrypted")
        BackupEncryption.encrypt(source, encrypted, password)
        val target = File(directory, "existing").apply { writeText("Existing synthetic sentinel", Charsets.UTF_8) }
        for (decrypt in listOf(false, true)) {
            val pass = password
            var failed = false
            try {
                if (decrypt) BackupEncryption.decrypt(encrypted, target, pass) else BackupEncryption.encrypt(source, target, pass)
            } catch (_: java.io.IOException) { failed = true }
            assertTrue(failed)
            assertTrue(pass.all { it == '\u0000' })
            assertEquals("Existing synthetic sentinel", target.readText(Charsets.UTF_8))
        }
    }
}
