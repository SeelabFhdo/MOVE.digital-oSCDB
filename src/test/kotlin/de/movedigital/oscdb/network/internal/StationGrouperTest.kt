package de.movedigital.oscdb.network.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class StationGrouperTest {
    private val stops = listOf(
        StopPoint("101", "Adelenstraße", 51.487622, 7.530952, null),
        StopPoint("102", "Adelenstraße", 51.487477, 7.531048, null),
        StopPoint("103", "Adelenstraße", 51.520000, 7.460000, null),
        StopPoint("200", "Hauptbahnhof", 51.517800, 7.459300, null),
        StopPoint("300", "Westfalenhallen", 51.495000, 7.451000, null),
    )

    @Test
    fun `groups by name and distance`() {
        val groups = StationGrouper.group(stops)
        assertEquals(listOf("adelenstrasse", "adelenstrasse-2", "hauptbahnhof", "westfalenhallen"), groups.map { it.stationId })
        assertEquals(listOf("101", "102"), groups[0].stopIds)
        assertEquals(listOf("103"), groups[1].stopIds)
        assertEquals((51.487622 + 51.487477) / 2, groups[0].lat, 1e-9)
        assertEquals((7.530952 + 7.531048) / 2, groups[0].lon, 1e-9)
    }

    @Test
    fun `parent station overrides distance`() {
        val withParent = stops.take(3).mapIndexed { i, s -> if (i < 2) s.copy(parentStation = "P1") else s }
        val groups = StationGrouper.group(withParent)
        val g = groups.first { "101" in it.stopIds }
        assertEquals(listOf("101", "102"), g.stopIds)
        assertEquals("Adelenstraße", g.name)
    }

    @Test
    fun `parent stop names station and is excluded`() {
        val list = listOf(
            StopPoint("P1", "Dortmund Hbf", 51.5, 7.4, null),
            StopPoint("a", "Gleis 1", 51.5001, 7.4, "P1"),
            StopPoint("b", "Gleis 2", 51.5002, 7.4, "P1"),
        )
        val groups = StationGrouper.group(list)
        assertEquals(1, groups.size)
        assertEquals("Dortmund Hbf", groups[0].name)
        assertEquals("dortmund-hbf", groups[0].stationId)
        assertEquals(listOf("a", "b"), groups[0].stopIds)
    }

    @Test
    fun `ids do not depend on stop order`() {
        fun ids(list: List<StopPoint>) = StationGrouper.group(list).flatMap { g -> g.stopIds.map { it to g.stationId } }.toMap()
        val forward = ids(stops)
        val reversed = ids(stops.reversed())
        assertEquals(forward, reversed)
        assertEquals("adelenstrasse", reversed["101"])
        assertEquals("adelenstrasse-2", reversed["103"])
    }

    @Test
    fun `literal name with suffix does not collide with split group`() {
        val list = listOf(
            StopPoint("1", "Foo", 51.0, 7.0, null),
            StopPoint("2", "Foo", 52.0, 7.0, null),
            StopPoint("3", "Foo 2", 51.0001, 7.0, null),
        )
        for (input in listOf(list, list.reversed())) {
            val byStop = StationGrouper.group(input).associate { it.stopIds.single() to it.stationId }
            assertEquals(mapOf("1" to "foo", "2" to "foo-3", "3" to "foo-2"), byStop)
        }
    }
}
