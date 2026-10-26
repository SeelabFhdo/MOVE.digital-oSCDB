package de.movedigital.oscdb.network

import de.movedigital.oscdb.Fixtures
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.ZipTestSource
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.shared.gtfs.GtfsSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.InputStream
import java.nio.file.Path
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NetworkLoaderIT {
    @Autowired lateinit var loader: NetworkLoader
    @Autowired lateinit var registry: FeedRegistry
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dataSource: DataSource
    @TempDir lateinit var tmp: Path

    @BeforeEach
    fun clean() {
        jdbc.sql("truncate feed restart identity cascade").update()
    }

    private fun feed() = registry.register("mini.zip", "sha", FeedInfo("pub", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "v"))

    private fun load(feedId: Int, source: GtfsSource) =
        dataSource.connection.use { loader.load(feedId, source, it) }

    private fun count(table: String) = jdbc.sql("select count(*) from $table").query(Int::class.java).single()

    @Test
    fun `loads mini fixture`() {
        val id = feed()
        ZipTestSource(Fixtures.miniZip(tmp)).use { load(id, it) }
        assertEquals(1, count("agency"))
        assertEquals(2, count("route"))
        assertEquals(5, count("stop"))
        assertEquals(4, count("station"))
        assertEquals(
            "adelenstrasse",
            jdbc.sql("select station_id from stop where stop_id = '102'").query(String::class.java).single(),
        )
        assertEquals(
            "FFCC00",
            jdbc.sql("select color from route where route_id = '1'").query(String::class.java).single(),
        )
    }

    @Test
    fun `missing stops file fails`() {
        val id = feed()
        val empty = object : GtfsSource {
            override fun open(fileName: String): InputStream? = null
        }
        assertThrows<IllegalStateException> { load(id, empty) }
    }
}
