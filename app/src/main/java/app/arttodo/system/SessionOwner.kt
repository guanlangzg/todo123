package app.arttodo.system

/** In-memory process ownership: stale heartbeats alone never imply a crash. */
class SessionOwner {
    @Volatile
    private var ownedSessionId: String? = null

    fun claim(sessionId: String) { ownedSessionId = sessionId }
    fun release(sessionId: String) { if (ownedSessionId == sessionId) ownedSessionId = null }
    fun sessionId(): String? = ownedSessionId

    fun owns(sessionId: String): Boolean = ownedSessionId == sessionId
}
