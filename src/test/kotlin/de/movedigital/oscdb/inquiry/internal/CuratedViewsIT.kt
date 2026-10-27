package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.MiniFeedLoader
import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class, MiniFeedLoader::class)
class CuratedViewsIT {
    @Autowired lateinit var mini: MiniFeedLoader
    @Autowired lateinit var jdbc: JdbcClient

    private var feedId = 0

    @BeforeEach
    fun load() {
        mini.reset()
        feedId = mini.load()
    }

    private fun count(sql: String): Int = jdbc.sql(sql).query(Int::class.java).single()

    @Test
    fun `departures on 2026-01-08`() {
        assertEquals(7, count("select count(*) from inquiry_api.v_departure where service_date = date '2026-01-08' and effective"))
    }

    @Test
    fun `no departures on 2026-01-06`() {
        assertEquals(0, count("select count(*) from inquiry_api.v_departure where service_date = date '2026-01-06'"))
    }

    @Test
    fun `after-midnight trip keeps its service date`() {
        val row = jdbc.sql(
            """select departure_time, departure_at, is_first_stop, is_last_stop, headsign, line, route_type
               from inquiry_api.v_departure
               where service_date = date '2026-01-08' and trip_id = 'T3' and stop_id = '101'""",
        ).query { rs, _ ->
            listOf(
                rs.getString(1), rs.getObject(2, OffsetDateTime::class.java), rs.getBoolean(3), rs.getBoolean(4),
                rs.getString(5), rs.getString(6), rs.getString(7),
            )
        }.single()
        assertEquals("24:30:00", row[0])
        assertEquals(OffsetDateTime.of(2026, 1, 9, 0, 30, 0, 0, ZoneOffset.ofHours(1)).toInstant(), (row[1] as OffsetDateTime).toInstant())
        assertEquals(true, row[2])
        assertEquals(false, row[3])
        assertEquals("Westfalenhallen", row[4])
        assertEquals("U41", row[5])
        assertEquals("subway", row[6])
    }

    @Test
    fun `overlapping feed wins and nothing is double counted`() {
        val second = mini.load(sha = "sha-2")
        val effective = jdbc.sql(
            "select feed_id from inquiry_api.v_departure where service_date = date '2026-01-08' and effective",
        ).query(Int::class.java).list()
        assertEquals(7, effective.size)
        assertTrue(effective.all { it == second })
        assertEquals(14, count("select count(*) from inquiry_api.v_departure where service_date = date '2026-01-08'"))
    }

    @Test
    fun `route type label`() {
        val types = jdbc.sql("select line, route_type from inquiry_api.v_route order by line")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
        assertEquals(listOf("440" to "bus", "U41" to "subway"), types)
    }

    @Test
    fun `stations count their platforms`() {
        assertEquals(2, count("select platform_count from inquiry_api.v_station where station_id = 'adelenstrasse'"))
    }

    @Test
    fun `distance between two Adelenstrasse platforms`() {
        val d = jdbc.sql(
            """select inquiry_api.distance_m(a.lat, a.lon, b.lat, b.lon)
               from inquiry_api.v_stop a, inquiry_api.v_stop b where a.stop_id = '101' and b.stop_id = '102'""",
        ).query(Double::class.java).single()
        assertTrue(d in 10.0..30.0, "distance $d")
    }

    @Test
    fun `failed feeds are invisible`() {
        jdbc.sql("update feed set status = 'FAILED'").update()
        assertEquals(0, count("select count(*) from inquiry_api.v_stop"))
        assertEquals(0, count("select count(*) from inquiry_api.v_departure"))
    }

    @Test
    fun `every view and column has a comment`() {
        val missing = jdbc.sql(
            """select c.relname || coalesce('.' || a.attname, '')
               from pg_class c join pg_namespace n on n.oid = c.relnamespace
               left join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
               where n.nspname = 'inquiry_api' and c.relkind = 'v'
                 and (obj_description(c.oid, 'pg_class') is null or (a.attname is not null and col_description(c.oid, a.attnum) is null))""",
        ).query(String::class.java).list()
        assertEquals(emptyList(), missing)
    }
}
