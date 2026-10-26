package de.movedigital.oscdb.network

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

        val used = HashMap<String, Int>()
        return drafts.map { d ->
            val base = slug(d.name).ifEmpty { "station" }
            val n = used.merge(base, 1, Int::plus)!!
            StationGroup(
                stationId = if (n == 1) base else "$base-$n",
                name = d.name,
                lat = d.members.map { it.lat }.average(),
                lon = d.members.map { it.lon }.average(),
                stopIds = d.members.map { it.stopId },
            )
        }
    }

    private fun distanceM(a: StopPoint, b: StopPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * RADIUS_M * asin(sqrt(h))
    }
}
