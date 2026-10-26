package de.movedigital.oscdb.network

import de.movedigital.oscdb.shared.gtfs.GtfsSource
import java.sql.Connection

/** Loads agencies, routes, stations and stops of one feed on the caller's connection/transaction. */
interface NetworkLoader {
    /**
     * [connection] MUST be the Spring transaction-bound connection (`DataSourceUtils.getConnection(dataSource)`
     * inside a `TransactionTemplate`).
     * @throws IllegalStateException if `agency.txt`, `routes.txt` or `stops.txt` is missing.
     */
    fun load(feedId: Int, source: GtfsSource, connection: Connection)
}
