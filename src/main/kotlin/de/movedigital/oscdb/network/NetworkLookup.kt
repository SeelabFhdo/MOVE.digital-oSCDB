package de.movedigital.oscdb.network

/** Read API of the network module for other modules. */
interface NetworkLookup {
    /** Stop names of one feed keyed by stop id (stops without a name are omitted). */
    fun stopNames(feedId: Int): Map<String, String>

    fun stopExists(feedId: Int, stopId: String): Boolean

    /** Stop ids of the station's platforms, or null if the station is unknown. */
    fun platformsOf(feedId: Int, stationId: String): List<String>?

    /** All routes of one feed keyed by route id. */
    fun routes(feedId: Int): Map<String, RouteInfo>
}

data class RouteInfo(val routeId: String, val shortName: String?, val type: Int?, val color: String?)
