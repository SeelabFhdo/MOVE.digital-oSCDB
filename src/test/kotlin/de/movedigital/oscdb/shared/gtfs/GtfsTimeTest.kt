package de.movedigital.oscdb.shared.gtfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GtfsTimeTest {
    @Test
    fun parses() {
        assertEquals(10722, GtfsTime.toSeconds("2:58:42"))
        assertEquals(28800, GtfsTime.toSeconds("08:00:00"))
        assertEquals(90000, GtfsTime.toSeconds("25:00:00"))
    }

    @Test
    fun formats() {
        assertEquals("25:00:00", GtfsTime.format(90000))
        assertEquals("02:58:42", GtfsTime.format(10722))
    }

    @Test
    fun rejectsGarbage() {
        assertThrows<IllegalArgumentException> { GtfsTime.toSeconds("abc") }
    }
}
