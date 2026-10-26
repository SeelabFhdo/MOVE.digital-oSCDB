package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.Fixtures
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.ZipTestSource
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.network.NetworkLoader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.nio.file.Path
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ScheduleLoaderIT {
    @Autowired lateinit var networkLoader: NetworkLoader
    @Autowired lateinit var scheduleLoader: ScheduleLoader
    @Autowired lateinit var registry: FeedRegistry
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dataSource: DataSource
    @TempDir lateinit var tmp: Path

    @BeforeEach
    fun clean() {
        jdbc.sql("truncate feed restart identity cascade").update()
        val id = registry.register("mini.zip", "sha", FeedInfo("pub", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "v"))
        ZipTestSource(Fixtures.miniZip(tmp)).use { src ->
            dataSource.connection.use { c ->
                networkLoader.load(id, src, c)
                scheduleLoader.load(id, src, c)
            }
        }
    }

    private fun count(table: String) = jdbc.sql("select count(*) from $table").query(Int::class.java).single()

    @Test
    fun `loads mini fixture`() {
        assertEquals(2, count("calendar"))
        assertEquals(2, count("calendar_date"))
        assertEquals(3, count("trip"))
        assertEquals(7, count("stop_time"))
        assertEquals(
            88200,
            jdbc.sql("select departure_secs from stop_time where trip_id = 'T3' and stop_sequence = 1").query(Int::class.java).single(),
        )
        assertEquals(
            30000,
            jdbc.sql("select arrival_secs from stop_time where trip_id = 'T1' and stop_sequence = 3").query(Int::class.java).single(),
        )
        assertEquals(
            true,
            jdbc.sql("select monday from calendar where service_id = '1'").query(Boolean::class.java).single(),
        )
    }

    @Test
    fun `derives headsign from last stop`() {
        fun hs(t: String) = jdbc.sql("select derived_headsign from trip where trip_id = :t").param("t", t).query(String::class.java).single()
        assertEquals("Westfalenhallen", hs("T1"))
        assertEquals("Hauptbahnhof", hs("T2"))
        assertEquals("Westfalenhallen", hs("T3"))
    }
}
