package app.arttodo.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class BackupArchiveTest {
    private val sample = "SQLite snapshot with sensitive task title".toByteArray(Charsets.UTF_8)

    @Test
    fun unencrypted_archive_round_trips_and_detects_payload_changes() {
        val archive = BackupArchive.encode(sample, password = null)
        assertThat(BackupArchive.decode(archive, password = null)).isEqualTo(sample)

        val damaged = archive.copyOf().also { copy ->
            val last = copy[copy.lastIndex]
            copy[copy.lastIndex] = (last.toInt() xor 1).toByte()
        }
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(damaged, password = null)
        }.code).isEqualTo("ChecksumMismatch")
    }

    @Test
    fun encrypted_archive_requires_the_right_password_and_authenticates_every_byte() {
        val archive = BackupArchive.encode(sample, password = "a long private passphrase".toCharArray())
        assertThat(BackupArchive.decode(archive, password = "a long private passphrase".toCharArray()))
            .isEqualTo(sample)
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(archive, password = "wrong password".toCharArray())
        }.code).isEqualTo("AuthenticationFailed")

        val damaged = archive.copyOf().also { copy ->
            val last = copy[copy.lastIndex]
            copy[copy.lastIndex] = (last.toInt() xor 1).toByte()
        }
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(damaged, password = "a long private passphrase".toCharArray())
        }.code).isEqualTo("AuthenticationFailed")
    }

    @Test
    fun malformed_truncated_and_unknown_version_archives_are_rejected() {
        val plain = BackupArchive.encode(sample, password = null)
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(plain.copyOf(plain.size - 1), password = null)
        }.code).isEqualTo("TruncatedArchive")

        val unknown = plain.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(unknown, password = null)
        }.code).isEqualTo("UnsupportedVersion")

        val badMagic = plain.copyOf().also { it[0] = 0 }
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(badMagic, password = null)
        }.code).isEqualTo("BadMagic")
    }

    @Test
    fun passwords_are_never_accepted_as_an_empty_value_and_size_is_bounded() {
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.encode(sample, password = CharArray(0))
        }.code).isEqualTo("EmptyPassword")
        assertThat(assertThrows(BackupException::class.java) {
            BackupArchive.decode(BackupArchive.encode(sample, password = null).copyOf(BackupArchive.HEADER_SIZE), password = null)
        }.code).isEqualTo("TruncatedArchive")
    }
}
