package de.movedigital.oscdb.shared.gtfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GtfsTableTest {
    @Test
    fun readsRowsStrippingBomAndBlanks() {
        val csv = "﻿stop_id,stop_name\n101,Adelenstraße\n102,\n"
        val rows = mutableListOf<GtfsRow>()
        GtfsTable.read(csv.byteInputStream(Charsets.UTF_8)) { rows += it }
        assertEquals(2, rows.size)
        assertEquals("101", rows[0]["stop_id"])
        assertEquals("Adelenstraße", rows[0]["stop_name"])
        assertEquals("102", rows[1]["stop_id"])
        assertNull(rows[1]["stop_name"])
        assertNull(rows[0]["missing"])
    }
}
