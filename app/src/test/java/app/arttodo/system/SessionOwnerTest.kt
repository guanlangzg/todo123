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
    fun release_only_clears_the_matching_session() {
        val owner = SessionOwner()
        owner.claim("session:1")
        owner.release("session:other")
        assertThat(owner.owns("session:1")).isTrue()
        owner.release("session:1")
        assertThat(owner.owns("session:1")).isFalse()
    }
}
