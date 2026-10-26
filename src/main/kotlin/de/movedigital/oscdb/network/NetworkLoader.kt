package de.movedigital.oscdb.network

import de.movedigital.oscdb.shared.gtfs.GtfsSource
import java.sql.Connection

/** Loads agencies, routes, stations and stops of one feed on the caller's connection/transaction. */
interface NetworkLoader {
    /** @throws IllegalStateException if `agency.txt`, `routes.txt` or `stops.txt` is missing. */
    fun load(feedId: Int, source: GtfsSource, connection: Connection)
}
