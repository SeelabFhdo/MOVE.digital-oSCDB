package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.shared.gtfs.GtfsSource
import java.sql.Connection

/** Loads calendar, calendar dates, trips and stop times of one feed on the caller's connection/transaction. */
interface ScheduleLoader {
    /**
     * [connection] MUST be the Spring transaction-bound connection (`DataSourceUtils.getConnection(dataSource)`
     * inside a `TransactionTemplate`), so network rows loaded in the same transaction are visible.
     * Network data of [feedId] must already be loaded (headsigns are derived from stop names).
     * @throws IllegalStateException if `calendar.txt`, `trips.txt` or `stop_times.txt` is missing.
     */
    fun load(feedId: Int, source: GtfsSource, connection: Connection)
}
