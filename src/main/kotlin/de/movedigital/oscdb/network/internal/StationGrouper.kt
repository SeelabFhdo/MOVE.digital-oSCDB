package de.movedigital.oscdb.network.internal

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class StopPoint(val stopId: String, val name: String, val lat: Double, val lon: Double, val parentStation: String?)

data class StationGroup(val stationId: String, val name: String, val lat: Double, val lon: Double, val stopIds: List<String>)

object StationGrouper {
    private const val RADIUS_M = 6_371_000.0
    private const val MAX_DISTANCE_M = 500.0

    private class Draft(var name: String, val members: MutableList<StopPoint> = mutableListOf())

    fun group(stops: List<StopPoint>): List<StationGroup> {
        val parentIds = stops.mapNotNull { it.parentStation }.toSet()
        val byId = stops.associateBy { it.stopId }
        val drafts = mutableListOf<Draft>()
        val byParent = HashMap<String, Draft>()
        val byName = HashMap<String, MutableList<Draft>>()

        for (stop in stops) {
            if (stop.stopId in parentIds) continue
            val parent = stop.parentStation
            if (parent != null) {
                val draft = byParent.getOrPut(parent) {
                    Draft(byId[parent]?.name ?: stop.name).also { drafts += it }
                }
                draft.members += stop
            } else {
                val candidates = byName.getOrPut(stop.name) { mutableListOf() }
                val existing = candidates.firstOrNull { distanceM(it.members.first(), stop) <= MAX_DISTANCE_M }
                if (existing != null) {
                    existing.members += stop
                } else {
                    Draft(stop.name, mutableListOf(stop)).also { candidates += it; drafts += it }
                }
            }
        }

        val ids = assignIds(drafts)
        return drafts.map { d ->
            StationGroup(
                stationId = ids.getValue(d),
                name = d.name,
                lat = d.members.map { it.lat }.average(),
                lon = d.members.map { it.lon }.average(),
                stopIds = d.members.map { it.stopId }.sorted(),
            )
        }
    }

    /**
     * Station ids independent of stop order: per slug, the group with the smallest stop_id gets the bare slug,
     * the others `<slug>-<n>` in stop_id order, skipping ids already taken (e.g. by a name that slugs to `foo-2`).
     */
    private fun assignIds(drafts: List<Draft>): Map<Draft, String> {
        val minStop = drafts.associateWith { d -> d.members.minOf { it.stopId } }
        val bySlug = drafts.groupBy { slug(it.name).ifEmpty { "station" } }.toSortedMap()
            .mapValues { (_, ds) -> ds.sortedBy { minStop.getValue(it) } }
        val result = HashMap<Draft, String>()
        val taken = HashSet<String>()
        bySlug.forEach { (base, ds) -> result[ds.first()] = base; taken += base }
        bySlug.forEach { (base, ds) ->
            var n = 2
            ds.drop(1).forEach { d ->
                while ("$base-$n" in taken) n++
                val id = "$base-$n"
                result[d] = id
                taken += id
            }
        }
        return result
    }

    private fun distanceM(a: StopPoint, b: StopPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * RADIUS_M * asin(sqrt(h))
    }
}
