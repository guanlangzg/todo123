package app.arttodo.system

/** In-memory process ownership: stale heartbeats alone never imply a crash. */
class SessionOwner {
    @Volatile
    private var ownedSessionId: String? = null

    @Synchronized
    fun claim(sessionId: String) { ownedSessionId = sessionId }

    @Synchronized
    fun release(sessionId: String) { if (ownedSessionId == sessionId) ownedSessionId = null }

    @Synchronized
    fun claimIfActive(sessionId: String, activeSessionId: String?): Boolean {
        if (activeSessionId != sessionId || (ownedSessionId != null && ownedSessionId != sessionId)) return false
        ownedSessionId = sessionId
        return true
    }

    fun sessionId(): String? = ownedSessionId

    fun owns(sessionId: String): Boolean = ownedSessionId == sessionId
}
