package de.movedigital.oscdb.schedule.internal

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

internal data class DepartureRow(
    val tripId: String,
    val routeId: String,
    val headsign: String?,
    val stopId: String,
    val departureSecs: Int,
)

@Repository
internal class ScheduleRepository(private val jdbc: JdbcClient) {
    /**
     * Departures at [stopIds] of trips running one of [services], with `departure_secs >= minSecs`,
     * ordered by departure time, at most [limit]. A trip's last stop is excluded (no departure there).
     * Uses the index `stop_time(feed_id, stop_id, departure_secs)`.
     */
    fun departures(feedId: Int, stopIds: Collection<String>, services: Collection<String>, minSecs: Int, limit: Int): List<DepartureRow> {
        if (stopIds.isEmpty() || services.isEmpty()) return emptyList()
        return jdbc.sql(
            """select st.trip_id, t.route_id, coalesce(nullif(t.headsign, ''), t.derived_headsign) as headsign,
                      st.stop_id, st.departure_secs
               from stop_time st
               join trip t on t.feed_id = st.feed_id and t.trip_id = st.trip_id
               where st.feed_id = :feed
                 and st.stop_id in (:stops)
                 and st.departure_secs >= :min
                 and t.service_id in (:services)
                 and exists (select 1 from stop_time n
                             where n.feed_id = st.feed_id and n.trip_id = st.trip_id and n.stop_sequence > st.stop_sequence)
               order by st.departure_secs, st.trip_id, st.stop_id
               limit :limit""",
        ).param("feed", feedId).param("stops", stopIds.toList()).param("min", minSecs)
            .param("services", services.toList()).param("limit", limit)
            .query { rs, _ -> DepartureRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)) }
            .list()
    }
}
