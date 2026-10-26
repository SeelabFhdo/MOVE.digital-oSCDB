package de.movedigital.oscdb.network.internal

import de.movedigital.oscdb.network.NetworkLookup
import de.movedigital.oscdb.network.RouteInfo
import de.movedigital.oscdb.shared.web.PageRequest
import de.movedigital.oscdb.shared.web.PageResponse
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
internal class NetworkRepository(private val jdbc: JdbcClient) : NetworkLookup {
    override fun stopNames(feedId: Int): Map<String, String> {
        val result = HashMap<String, String>()
        jdbc.sql("select stop_id, name from stop where feed_id = :feed and name is not null")
            .param("feed", feedId)
            .query { rs -> result[rs.getString(1)] = rs.getString(2) }
        return result
    }

    override fun stopExists(feedId: Int, stopId: String): Boolean =
        jdbc.sql("select exists(select 1 from stop where feed_id = :feed and stop_id = :id)")
            .param("feed", feedId).param("id", stopId)
            .query(Boolean::class.java).single()

    override fun platformsOf(feedId: Int, stationId: String): List<String>? {
        if (!stationExists(feedId, stationId)) return null
        return jdbc.sql("select stop_id from stop where feed_id = :feed and station_id = :id order by stop_id")
            .param("feed", feedId).param("id", stationId)
            .query { rs, _ -> rs.getString(1) }.list()
    }

    override fun routes(feedId: Int): Map<String, RouteInfo> =
        jdbc.sql("select route_id, short_name, type, color from route where feed_id = :feed")
            .param("feed", feedId)
            .query { rs, _ ->
                val type = rs.getIntOrNull(3)
                RouteInfo(rs.getString(1), rs.getString(2), type, routeTypeLabel(type), rs.getString(4))
            }
            .list().associateBy { it.routeId }

    private fun stationExists(feedId: Int, stationId: String): Boolean =
        jdbc.sql("select exists(select 1 from station where feed_id = :feed and station_id = :id)")
            .param("feed", feedId).param("id", stationId)
            .query(Boolean::class.java).single()

    fun agencies(feedId: Int): List<AgencyDto> =
        jdbc.sql("select agency_id, name, url, timezone from agency where feed_id = :feed order by agency_id")
            .param("feed", feedId)
            .query { rs, _ -> AgencyDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
            .list()

    fun routeList(feedId: Int, type: Int?, agency: String?): List<RouteDto> =
        jdbc.sql(
            ROUTE_SQL + " where feed_id = :feed and (cast(:type as int) is null or type = :type)" +
                " and (cast(:agency as text) is null or agency_id = :agency) order by route_id",
        ).param("feed", feedId).param("type", type, java.sql.Types.INTEGER).param("agency", agency, java.sql.Types.VARCHAR)
            .query { rs, _ -> rs.toRoute() }.list()

    fun route(feedId: Int, routeId: String): RouteDto? =
        jdbc.sql("$ROUTE_SQL where feed_id = :feed and route_id = :id")
            .param("feed", feedId).param("id", routeId)
            .query { rs, _ -> rs.toRoute() }.optional().orElse(null)

    fun stops(feedId: Int, q: String?, req: PageRequest): PageResponse<StopDto> {
        val pattern = likePattern(q)
        val total = jdbc.sql(
            "select count(*) from stop where feed_id = :feed and (cast(:p as text) is null or lower(name) like :p escape '\\')",
        ).param("feed", feedId).param("p", pattern, java.sql.Types.VARCHAR).query(Long::class.java).single()
        val items = jdbc.sql(
            "$STOP_SQL where feed_id = :feed and (cast(:p as text) is null or lower(name) like :p escape '\\')" +
                " order by name, stop_id limit :lim offset :off",
        ).param("feed", feedId).param("p", pattern, java.sql.Types.VARCHAR)
            .param("lim", req.size).param("off", req.offset)
            .query { rs, _ -> rs.toStop() }.list()
        return PageResponse(items, req.page, req.size, total)
    }

    fun stop(feedId: Int, stopId: String): StopDto? =
        jdbc.sql("$STOP_SQL where feed_id = :feed and stop_id = :id")
            .param("feed", feedId).param("id", stopId)
            .query { rs, _ -> rs.toStop() }.optional().orElse(null)

    fun stations(feedId: Int, q: String?, req: PageRequest): PageResponse<StationDto> {
        val pattern = likePattern(q)
        val total = jdbc.sql(
            "select count(*) from station where feed_id = :feed and (cast(:p as text) is null or lower(name) like :p escape '\\')",
        ).param("feed", feedId).param("p", pattern, java.sql.Types.VARCHAR).query(Long::class.java).single()
        val items = jdbc.sql(
            "select station_id, name, lat, lon from station where feed_id = :feed" +
                " and (cast(:p as text) is null or lower(name) like :p escape '\\')" +
                " order by name, station_id limit :lim offset :off",
        ).param("feed", feedId).param("p", pattern, java.sql.Types.VARCHAR)
            .param("lim", req.size).param("off", req.offset)
            .query { rs, _ -> rs.toStation() }.list()
        return PageResponse(items, req.page, req.size, total)
    }

    fun station(feedId: Int, stationId: String): StationDetailDto? {
        val s = jdbc.sql("select station_id, name, lat, lon from station where feed_id = :feed and station_id = :id")
            .param("feed", feedId).param("id", stationId)
            .query { rs, _ -> rs.toStation() }.optional().orElse(null) ?: return null
        val platforms = jdbc.sql("$STOP_SQL where feed_id = :feed and station_id = :id order by stop_id")
            .param("feed", feedId).param("id", stationId)
            .query { rs, _ -> rs.toStop() }.list()
        return StationDetailDto(s.id, s.name, s.lat, s.lon, platforms)
    }

    private companion object {
        const val ROUTE_SQL = "select route_id, short_name, long_name, type, color, text_color, agency_id from route"
        const val STOP_SQL = "select stop_id, name, lat, lon, station_id from stop"

        /** `%name%` pattern with `%`, `_`, `\` escaped; null if q is blank/absent. */
        fun likePattern(q: String?): String? {
            if (q.isNullOrBlank()) return null
            val escaped = q.trim().lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return "%$escaped%"
        }

        fun ResultSet.getIntOrNull(i: Int): Int? = getInt(i).takeUnless { wasNull() }
        fun ResultSet.getDoubleOrNull(i: Int): Double? = getDouble(i).takeUnless { wasNull() }

        fun ResultSet.toRoute(): RouteDto {
            val type = getIntOrNull(4)
            return RouteDto(getString(1), getString(2), getString(3), type, routeTypeLabel(type), getString(5), getString(6), getString(7))
        }

        fun ResultSet.toStop() = StopDto(getString(1), getString(2), getDoubleOrNull(3), getDoubleOrNull(4), getString(5))
        fun ResultSet.toStation() = StationDto(getString(1), getString(2), getDouble(3), getDouble(4))
    }
}

internal fun routeTypeLabel(type: Int?): String = when (type) {
    0 -> "tram"
    1 -> "subway"
    2 -> "rail"
    3 -> "bus"
    405 -> "monorail"
    else -> "other"
}
