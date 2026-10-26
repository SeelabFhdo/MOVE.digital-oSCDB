package de.movedigital.oscdb.network

/** Read API of the network module for other modules. */
interface NetworkLookup {
    /** Stop names of one feed keyed by stop id (stops without a name are omitted). */
    fun stopNames(feedId: Int): Map<String, String>
}
