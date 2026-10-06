package app.arttodo.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(val code: String, cause: Throwable? = null) : Exception(code, cause)

/** Versioned authenticated container for a database snapshot. The caller supplies a manifest + DB. */
object BackupArchive {
    private val magic = byteArrayOf(0x41, 0x52, 0x54, 0x42, 0x4b, 0x55, 0x50, 0x00) // ARTBKUP\0
    const val HEADER_SIZE = 50
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 12
    const val KDF_ITERATIONS = 600_000
    const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
    private const val VERSION = 1
    private const val GCM_TAG_BITS = 128
    private const val PLAIN_DIGEST_BYTES = 32
    private val random = SecureRandom()

    /**
     * Payload budget of [encode]: the header and the plaintext digest are paid out of the same
     * [MAX_ARCHIVE_BYTES] cap, so no accepted payload can push the container past it.
     */
    internal const val MAX_PAYLOAD_BYTES = MAX_ARCHIVE_BYTES - HEADER_SIZE - PLAIN_DIGEST_BYTES

    fun encode(payload: ByteArray, password: CharArray?): ByteArray =
        encodeWithin(payload, password, MAX_PAYLOAD_BYTES)

    /**
     * Encodes with an explicit payload budget instead of [MAX_PAYLOAD_BYTES].
     *
     * The parameter exists so the size boundary can be crossed by a unit test that must not
     * materialise 512 MiB; [encode] is the only production caller and always passes the production
     * budget. The check runs first and reads the declared length only, so an oversized payload is
     * refused before anything is copied or encrypted.
     */
    internal fun encodeWithin(payload: ByteArray, password: CharArray?, maxPayloadBytes: Int): ByteArray {
        if (payload.size > maxPayloadBytes) {
            throw BackupException("ArchiveTooLarge")
        }
        if (password != null && password.isEmpty()) throw BackupException("EmptyPassword")
        val encrypted = password != null
        if (password != null) {
            val iterations = KDF_ITERATIONS
            if (iterations != 600_000) throw BackupException("UnsupportedKdfParameters")
        }
        val salt = ByteArray(SALT_SIZE)
        val nonce = ByteArray(NONCE_SIZE)
        if (encrypted) {
            random.nextBytes(salt)
            random.nextBytes(nonce)
        }
        val iterations = if (encrypted) KDF_ITERATIONS else 0
        val payloadSize = payload.size + if (encrypted) GCM_TAG_BITS / 8 else 0
        val header = makeHeader(encrypted, iterations, salt, nonce, payloadSize.toLong())
        return try {
            if (encrypted) {
                val cipherText = crypt(Cipher.ENCRYPT_MODE, payload, password!!, salt, nonce, header)
                header + cipherText
            } else {
                val body = header + payload
                body + MessageDigest.getInstance("SHA-256").digest(body)
            }
        } finally {
            salt.fill(0)
            nonce.fill(0)
        }
    }

    fun decode(archive: ByteArray, password: CharArray?): ByteArray {
        if (archive.size < HEADER_SIZE) throw BackupException("TruncatedArchive")
        if (archive.size > MAX_ARCHIVE_BYTES) throw BackupException("ArchiveTooLarge")
        val header = archive.copyOfRange(0, HEADER_SIZE)
        val fields = parseHeader(header)
        val expected = HEADER_SIZE.toLong() + fields.payloadLength +
            if (fields.encrypted) 0 else PLAIN_DIGEST_BYTES.toLong()
        if (fields.payloadLength < 0 || expected != archive.size.toLong()) {
            throw BackupException("TruncatedArchive")
        }
        val body = archive.copyOfRange(HEADER_SIZE, archive.size)
        if (!fields.encrypted) {
            val expectedDigest = MessageDigest.getInstance("SHA-256").digest(archive.copyOfRange(0, archive.size - PLAIN_DIGEST_BYTES))
            val actualDigest = archive.copyOfRange(archive.size - PLAIN_DIGEST_BYTES, archive.size)
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) throw BackupException("ChecksumMismatch")
            return body.copyOf(body.size - PLAIN_DIGEST_BYTES)
        }
        val actualPassword = password ?: throw BackupException("PasswordRequired")
        if (actualPassword.isEmpty()) throw BackupException("EmptyPassword")
        return try {
            crypt(Cipher.DECRYPT_MODE, body, actualPassword, fields.salt, fields.nonce, header)
        } catch (_: AEADBadTagException) {
            throw BackupException("AuthenticationFailed")
        } catch (_: BackupException) {
            throw BackupException("AuthenticationFailed")
        } finally {
            fields.salt.fill(0)
            fields.nonce.fill(0)
        }
    }

    fun readBounded(input: InputStream): ByteArray = readBoundedWithin(input, MAX_ARCHIVE_BYTES)

    /**
     * Streams an import under an explicit cap instead of [MAX_ARCHIVE_BYTES].
     *
     * As with [encodeWithin], the parameter exists so a unit test can cross the cap for real
     * without buffering 512 MiB; [readBounded] is the only production caller and always passes the
     * production cap. The running total is checked before each write, so a hostile file cannot make
     * the caller hold more than the cap.
     */
    internal fun readBoundedWithin(input: InputStream, limitBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limitBytes) throw BackupException("ArchiveTooLarge")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun crypt(
        mode: Int,
        input: ByteArray,
        password: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, KDF_ITERATIONS, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (_: Exception) {
            throw BackupException("CryptoUnavailable")
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(input)
            }
        } catch (error: AEADBadTagException) {
            throw error
        } catch (_: Exception) {
            throw BackupException("CryptoFailure")
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun makeHeader(
        encrypted: Boolean,
        iterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
        payloadLength: Long,
    ): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        .put(magic)
        .put(VERSION.toByte())
        .put(if (encrypted) 1 else 0)
        .putInt(iterations)
        .put(salt)
        .put(nonce)
        .putLong(payloadLength)
        .array()

    private data class Fields(val encrypted: Boolean, val salt: ByteArray, val nonce: ByteArray, val payloadLength: Long)

    private fun parseHeader(header: ByteArray): Fields {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size).also(buffer::get)
        if (!actualMagic.contentEquals(magic)) throw BackupException("BadMagic")
        if (buffer.get().toInt() and 0xff != VERSION) throw BackupException("UnsupportedVersion")
        val flags = buffer.get().toInt() and 0xff
        if (flags !in 0..1) throw BackupException("UnsupportedFlags")
        val iterations = buffer.int
        val salt = ByteArray(SALT_SIZE).also(buffer::get)
        val nonce = ByteArray(NONCE_SIZE).also(buffer::get)
        val payloadLength = buffer.long
        val encrypted = flags == 1
        if ((encrypted && iterations != KDF_ITERATIONS) || (!encrypted && iterations != 0)) {
            throw BackupException("UnsupportedKdfParameters")
        }
        if (encrypted && (salt.all { it == 0.toByte() } || nonce.all { it == 0.toByte() })) {
            throw BackupException("InvalidRandomParameters")
        }
        return Fields(encrypted, salt, nonce, payloadLength)
    }
}
