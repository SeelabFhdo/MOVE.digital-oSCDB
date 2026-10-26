package de.movedigital.oscdb.feed

interface FeedRegistry {
    fun statusOf(sha256: String): FeedStatus?
    fun deleteBySha(sha256: String)

    /** Inserts an IMPORTING feed row and returns its id. */
    fun register(sourceFile: String, sha256: String, info: FeedInfo): Int
    fun markReady(id: Int)
    fun markFailed(id: Int)
}
