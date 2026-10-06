package app.arttodo.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Size caps of the backup container (规格 §9.5 / 架构契约 §8.3 step ②: import limited to 512 MiB).
 *
 * The caps are production constants, so the real boundary can only be reached by materialising
 * 512 MiB. An earlier revision of this file did exactly that (`ByteArray(MAX_ARCHIVE_BYTES)`) and
 * died with `OutOfMemoryError` in the 512 MiB test heap before any assertion about the limit ran —
 * a red gate that proved nothing. The checks are therefore asserted twice over:
 *
 *  * the **real contract numbers** ([BackupArchive.MAX_ARCHIVE_BYTES] is 512 MiB, and the payload
 *    budget is the cap minus the overhead an actual encoded archive costs), and
 *  * the **real production bodies** ([BackupArchive.encodeWithin] / [BackupArchive.readBoundedWithin],
 *    which [BackupArchive.encode] / [BackupArchive.readBounded] delegate to unconditionally) driven
 *    across their boundary with a small cap, so the position of the check and its comparison are
 *    exercised for real — including that the streaming loop stops pulling instead of draining the
 *    stream.
 *
 * An oversized archive is a data condition the user must be told about, not a programming error:
 * every other rejection in [BackupArchive] (and in BackupArchiveTest) is a [BackupException] whose
 * `code` reaches the UI through `CommandExecutor`'s `DomainFailure.RecoveryPointUnavailable(code)`
 * and `AppViewModel`'s error text. Throwing anything else would relabel `ArchiveTooLarge` — e.g.
 * `BackupManager`'s `catch (error: Exception) → "ExportFailed"` — so the code is asserted, not just
 * the type.
 */
class BackupArchiveLimitTest {

    @Test
    fun the_cap_is_512_mib_and_the_payload_budget_is_the_cap_minus_the_real_container_overhead() {
        assertThat(BackupArchive.MAX_ARCHIVE_BYTES).isEqualTo(512 * 1024 * 1024)

        // Observed on a real archive rather than restated from the source: an unencrypted archive
        // costs header + plaintext digest on top of the payload.
        val sample = ByteArray(64) { it.toByte() }
        val overhead = BackupArchive.encode(sample, password = null).size - sample.size
        assertThat(BackupArchive.MAX_PAYLOAD_BYTES)
            .isEqualTo(BackupArchive.MAX_ARCHIVE_BYTES - overhead)
    }

    @Test
    fun a_payload_at_the_budget_is_encoded_but_one_byte_more_is_refused_by_declared_length() {
        val payload = ByteArray(1024) { (it % 251).toByte() }

        // The boundary value itself is accepted, through the very body `encode` delegates to, and
        // produces the same archive as the production entry point.
        assertThat(BackupArchive.encodeWithin(payload, null, payload.size))
            .isEqualTo(BackupArchive.encode(payload, null))

        // One byte over the budget is refused with the production error code, before any copy,
        // digest or encryption could have happened.
        val error = assertThrows(BackupException::class.java) {
            BackupArchive.encodeWithin(payload, null, payload.size - 1)
        }
        assertThat(error.code).isEqualTo("ArchiveTooLarge")
    }

    @Test
    fun a_stream_at_the_cap_round_trips_but_one_byte_more_is_refused_without_draining_it() {
        val limit = 128 * 1024
        val accepted = ByteArray(limit) { (it % 251).toByte() }
        assertThat(BackupArchive.readBoundedWithin(ByteArrayInputStream(accepted), limit))
            .isEqualTo(accepted)

        // A stream that never ends: the loop must throw on the read that crosses the cap instead of
        // buffering until the heap dies. Counting what it was asked for proves the early exit.
        var pulled = 0
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(buffer, offset, offset + length, 0)
                pulled += length
                return length
            }
        }
        val error = assertThrows(BackupException::class.java) {
            BackupArchive.readBoundedWithin(endless, limit)
        }
        assertThat(error.code).isEqualTo("ArchiveTooLarge")
        assertThat(pulled).isLessThan(limit + 64 * 1024)
    }

    @Test
    fun the_production_entry_points_still_accept_an_archive_below_the_cap() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val archive = BackupArchive.encode(payload, password = null)

        assertThat(BackupArchive.decode(archive, password = null)).isEqualTo(payload)
        assertThat(BackupArchive.readBounded(ByteArrayInputStream(archive))).isEqualTo(archive)
    }
}
