package app.arttodo.system

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionOwnerTest {
    @Test
    fun activity_recreation_does_not_release_process_owned_session() {
        val owner = SessionOwner()
        owner.claim("session:1")
        assertThat(owner.owns("session:1")).isTrue()
    }

    @Test
    fun a_stale_deadline_cannot_replace_the_current_owner() {
        val owner = SessionOwner()
        owner.claim("session:new")
        val claimed = owner.claimIfActive("session:old", activeSessionId = "session:new")

        assertThat(claimed).isEqualTo(false)
        assertThat(owner.sessionId()).isEqualTo("session:new")
    }

    @Test
    fun a_stale_active_snapshot_cannot_replace_a_newer_owner() {
        val owner = SessionOwner()
        owner.claim("session:new")

        val claimed = owner.claimIfActive("session:old", activeSessionId = "session:old")

        assertThat(claimed).isEqualTo(false)
        assertThat(owner.sessionId()).isEqualTo("session:new")
    }

    @Test
    fun a_matching_active_session_can_be_claimed() {
        val owner = SessionOwner()

        val claimed = owner.claimIfActive("session:active", activeSessionId = "session:active")

        assertThat(claimed).isEqualTo(true)
        assertThat(owner.owns("session:active")).isTrue()
    }

    @Test
    fun release_only_clears_the_matching_session() {
        val owner = SessionOwner()
        owner.claim("session:1")
        owner.release("session:other")
        assertThat(owner.owns("session:1")).isTrue()
        owner.release("session:1")
        assertThat(owner.owns("session:1")).isFalse()
    }
}
