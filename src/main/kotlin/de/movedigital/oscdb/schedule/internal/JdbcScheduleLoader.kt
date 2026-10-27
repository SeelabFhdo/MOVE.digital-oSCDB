package de.movedigital.oscdb.schedule.internal

import de.movedigital.oscdb.schedule.ScheduleLoader
import de.movedigital.oscdb.network.NetworkLookup
import de.movedigital.oscdb.shared.gtfs.GtfsRow
import de.movedigital.oscdb.shared.gtfs.GtfsSource
import de.movedigital.oscdb.shared.gtfs.GtfsTable
import de.movedigital.oscdb.shared.gtfs.GtfsTime
import de.movedigital.oscdb.shared.jdbc.PgCopy
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
internal class JdbcScheduleLoader(private val network: NetworkLookup) : ScheduleLoader {
    private val log = LoggerFactory.getLogger(JdbcScheduleLoader::class.java)
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE

    override fun load(feedId: Int, source: GtfsSource, connection: Connection) {
        val copy = PgCopy(connection)
        loadCalendar(feedId, source, copy)
        loadCalendarDates(feedId, source, copy)
        connection.prepareStatement("select fill_service_days(?)").use { it.setInt(1, feedId); it.execute() }
        loadTrips(feedId, source, copy)
        val lastStops = loadStopTimes(feedId, source, copy)
        deriveHeadsigns(feedId, lastStops, connection, copy)
    }

    private fun loadCalendar(feedId: Int, source: GtfsSource, copy: PgCopy) {
        val cols = listOf("feed_id", "service_id", "start_date", "end_date", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        copy.into("calendar", cols) { w ->
            val seen = HashSet<String>()
            read(source, "calendar.txt") { r ->
                val id = r["service_id"] ?: return@read
                val start = date(r["start_date"]) ?: return@read
                val end = date(r["end_date"]) ?: return@read
                if (seen.add(id)) {
                    w.row(
                        feedId, id, start, end,
                        flag(r, "monday"), flag(r, "tuesday"), flag(r, "wednesday"), flag(r, "thursday"),
                        flag(r, "friday"), flag(r, "saturday"), flag(r, "sunday"),
                    )
                }
            }
        }
    }

    private fun loadCalendarDates(feedId: Int, source: GtfsSource, copy: PgCopy) {
        if (source.open("calendar_dates.txt")?.also { it.close() } == null) return
        copy.into("calendar_date", listOf("feed_id", "service_id", "date", "exception_type")) { w ->
            val seen = HashSet<Pair<String, LocalDate>>()
            read(source, "calendar_dates.txt") { r ->
                val id = r["service_id"] ?: return@read
                val date = date(r["date"]) ?: return@read
                val type = r["exception_type"]?.toIntOrNull() ?: return@read
                if (seen.add(id to date)) w.row(feedId, id, date, type)
            }
        }
    }

    private fun loadTrips(feedId: Int, source: GtfsSource, copy: PgCopy) {
        val cols = listOf("feed_id", "trip_id", "route_id", "service_id", "headsign", "direction_id", "block_id", "wheelchair_accessible")
        copy.into("trip", cols) { w ->
            val seen = HashSet<String>()
            read(source, "trips.txt") { r ->
                val id = r["trip_id"] ?: return@read
                val route = r["route_id"] ?: return@read
                val service = r["service_id"] ?: return@read
                if (seen.add(id)) {
                    w.row(feedId, id, route, service, r["trip_headsign"], r["direction_id"]?.toIntOrNull(), r["block_id"], r["wheelchair_accessible"]?.toIntOrNull())
                }
            }
        }
    }

    private class LastStop(var sequence: Int, var stopId: String)

    /** Streams stop_times into the table; returns the last stop (max sequence) per trip. */
    private fun loadStopTimes(feedId: Int, source: GtfsSource, copy: PgCopy): Map<String, LastStop> {
        val last = HashMap<String, LastStop>()
        val cols = listOf("feed_id", "trip_id", "stop_sequence", "stop_id", "arrival_secs", "departure_secs", "pickup_type", "drop_off_type")
        var prevTrip: String? = null
        var prevSeq = 0
        var skipped = 0L
        copy.into("stop_time", cols) { w ->
            read(source, "stop_times.txt") { r ->
                val trip = r["trip_id"] ?: return@read
                val stop = r["stop_id"] ?: return@read
                val seq = r["stop_sequence"]?.toIntOrNull() ?: return@read
                if (trip == prevTrip && seq == prevSeq) {
                    skipped++
                    return@read
                }
                prevTrip = trip
                prevSeq = seq
                val arrival = r["arrival_time"]?.let(GtfsTime::toSeconds)
                val departure = r["departure_time"]?.let(GtfsTime::toSeconds)
                w.row(feedId, trip, seq, stop, arrival ?: departure, departure ?: arrival, r["pickup_type"]?.toIntOrNull(), r["drop_off_type"]?.toIntOrNull())
                val cur = last[trip]
                if (cur == null) last[trip] = LastStop(seq, stop)
                else if (seq > cur.sequence) {
                    cur.sequence = seq
                    cur.stopId = stop
                }
            }
        }
        if (skipped > 0) log.warn("Feed {}: skipped {} consecutive duplicate stop_times rows", feedId, skipped)
        return last
    }

    private fun deriveHeadsigns(feedId: Int, lastStops: Map<String, LastStop>, connection: Connection, copy: PgCopy) {
        if (lastStops.isEmpty()) return
        val names = network.stopNames(feedId)
        check(names.isNotEmpty()) { "No stop names for feed $feedId; NetworkLoader must run first in the same transaction" }
        connection.createStatement().use { it.execute("create temp table tmp_headsign (trip_id text primary key, headsign text not null)") }
        try {
            copy.into("tmp_headsign", listOf("trip_id", "headsign")) { w ->
                lastStops.forEach { (trip, last) -> names[last.stopId]?.let { w.row(trip, it) } }
            }
            connection.prepareStatement(
                "update trip t set derived_headsign = h.headsign from tmp_headsign h where t.feed_id = ? and t.trip_id = h.trip_id",
            ).use { it.setInt(1, feedId); it.executeUpdate() }
        } finally {
            // Best effort: in an aborted transaction the drop fails and must not mask the original error.
            runCatching { connection.createStatement().use { it.execute("drop table if exists tmp_headsign") } }
        }
    }

    private fun read(source: GtfsSource, file: String, consumer: (GtfsRow) -> Unit) {
        val input = source.open(file) ?: throw IllegalStateException("GTFS file $file is missing")
        input.use { GtfsTable.read(it, consumer) }
    }

    private fun date(value: String?): LocalDate? = value?.let { LocalDate.parse(it, dateFormat) }

    private fun flag(r: GtfsRow, column: String): Boolean = r[column] == "1"
}
