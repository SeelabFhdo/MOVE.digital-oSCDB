package de.movedigital.oscdb.network

import de.movedigital.oscdb.shared.gtfs.GtfsRow
import de.movedigital.oscdb.shared.gtfs.GtfsSource
import de.movedigital.oscdb.shared.gtfs.GtfsTable
import de.movedigital.oscdb.shared.jdbc.PgCopy
import org.springframework.stereotype.Component
import java.sql.Connection

@Component
internal class JdbcNetworkLoader : NetworkLoader {
    override fun load(feedId: Int, source: GtfsSource, connection: Connection) {
        val copy = PgCopy(connection)
        read(source, "agency.txt") { rows ->
            copy.into("agency", listOf("feed_id", "agency_id", "name", "url", "timezone", "lang", "phone", "fare_url")) { w ->
                val seen = HashSet<String>()
                rows { r ->
                    val id = r["agency_id"] ?: ""
                    if (seen.add(id)) {
                        w.row(feedId, id, r["agency_name"] ?: "", r["agency_url"], r["agency_timezone"], r["agency_lang"], r["agency_phone"], r["agency_fare_url"])
                    }
                }
            }
        }
        read(source, "routes.txt") { rows ->
            copy.into("route", listOf("feed_id", "route_id", "agency_id", "short_name", "long_name", "type", "color", "text_color")) { w ->
                val seen = HashSet<String>()
                rows { r ->
                    val id = r["route_id"] ?: return@rows
                    if (seen.add(id)) {
                        w.row(feedId, id, r["agency_id"], r["route_short_name"], r["route_long_name"], r["route_type"]?.toIntOrNull(), r["route_color"], r["route_text_color"])
                    }
                }
            }
        }

        val stops = mutableListOf<GtfsRow>()
        val seenStops = HashSet<String>()
        read(source, "stops.txt") { rows ->
            rows { r -> r["stop_id"]?.let { if (seenStops.add(it)) stops += r } }
        }
        val points = stops.mapNotNull { r ->
            val lat = r["stop_lat"]?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = r["stop_lon"]?.toDoubleOrNull() ?: return@mapNotNull null
            StopPoint(r["stop_id"]!!, r["stop_name"] ?: "", lat, lon, r["parent_station"])
        }
        val groups = StationGrouper.group(points)
        val stationOf = HashMap<String, String>()
        groups.forEach { g -> g.stopIds.forEach { stationOf[it] = g.stationId } }
        // Parent stops belong to the station of their children.
        points.forEach { p -> p.parentStation?.let { par -> stationOf[p.stopId]?.let { stationOf.putIfAbsent(par, it) } } }

        copy.into("station", listOf("feed_id", "station_id", "name", "lat", "lon")) { w ->
            groups.forEach { w.row(feedId, it.stationId, it.name, it.lat, it.lon) }
        }
        copy.into(
            "stop",
            listOf("feed_id", "stop_id", "code", "name", "lat", "lon", "location_type", "parent_station", "wheelchair_boarding", "station_id"),
        ) { w ->
            stops.forEach { r ->
                val id = r["stop_id"]!!
                w.row(
                    feedId, id, r["stop_code"], r["stop_name"],
                    r["stop_lat"]?.toDoubleOrNull(), r["stop_lon"]?.toDoubleOrNull(),
                    r["location_type"]?.toIntOrNull(), r["parent_station"], r["wheelchair_boarding"]?.toIntOrNull(),
                    stationOf[id],
                )
            }
        }
    }

    private fun read(source: GtfsSource, file: String, block: (((GtfsRow) -> Unit) -> Unit) -> Unit) {
        val input = source.open(file) ?: throw IllegalStateException("GTFS file $file is missing")
        input.use { block { consumer -> GtfsTable.read(it, consumer) } }
    }
}
