package de.movedigital.oscdb.network

import de.movedigital.oscdb.Fixtures
import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.importer.ZipGtfsSource
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.schedule.ScheduleLoader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.LocalDate
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class NetworkControllerIT {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var networkLoader: NetworkLoader
    @Autowired lateinit var scheduleLoader: ScheduleLoader
    @Autowired lateinit var registry: FeedRegistry
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var lookup: NetworkLookup
    @TempDir lateinit var tmp: Path

    private var feedId = 0

    @BeforeEach
    fun load() {
        jdbc.sql("truncate feed restart identity cascade").update()
        feedId = registry.register("mini.zip", "sha", FeedInfo("pub", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "v"))
        ZipGtfsSource(Fixtures.miniZip(tmp)).use { src ->
            tx.execute {
                val c = DataSourceUtils.getConnection(dataSource)
                networkLoader.load(feedId, src, c)
                scheduleLoader.load(feedId, src, c)
            }
        }
        registry.markReady(feedId)
    }

    private val base get() = "/api/v1/feeds/$feedId"

    @Test
    fun `station search`() {
        mvc.get("$base/stations?q=adelen").andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[0].id") { value("adelenstrasse") }
            jsonPath("$.items[1].id") { value("adelenstrasse-2") }
        }
    }

    @Test
    fun `station detail lists platforms`() {
        mvc.get("$base/stations/adelenstrasse").andExpect {
            status { isOk() }
            jsonPath("$.platforms.length()") { value(2) }
            jsonPath("$.platforms[0].id") { value("101") }
            jsonPath("$.platforms[1].id") { value("102") }
        }
    }

    @Test
    fun `special characters in q are literal`() {
        mvc.get("$base/stops?q=%25").andExpect { status { isOk() }; jsonPath("$.total") { value(0) } }
        mvc.get("$base/stops?q=_").andExpect { status { isOk() }; jsonPath("$.total") { value(0) } }
        mvc.get("$base/stops?q=%5C").andExpect { status { isOk() }; jsonPath("$.total") { value(0) } }
        mvc.get("$base/stops?q=O'").andExpect { status { isOk() } }
    }

    @Test
    fun `routes filter and labels`() {
        mvc.get("$base/routes?type=3").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value("2") }
            jsonPath("$[0].shortName") { value("440") }
            jsonPath("$[0].typeLabel") { value("bus") }
        }
        mvc.get("$base/routes/1").andExpect { status { isOk() }; jsonPath("$.typeLabel") { value("subway") } }
        mvc.get("$base/routes?agency=1").andExpect { status { isOk() }; jsonPath("$.length()") { value(2) } }
    }

    @Test
    fun `agencies and stop detail`() {
        mvc.get("$base/agencies").andExpect { status { isOk() }; jsonPath("$[0].name") { value("DSW21") } }
        mvc.get("$base/stops/101").andExpect { status { isOk() }; jsonPath("$.stationId") { value("adelenstrasse") } }
    }

    @Test
    fun `unknown ids are 404 problem json`() {
        for (p in listOf("stops/999", "stations/nope", "routes/nope")) {
            mvc.get("$base/$p").andExpect {
                status { isNotFound() }
                content { contentTypeCompatibleWith("application/problem+json") }
            }
        }
    }

    @Test
    fun `lookup additions`() {
        assert(lookup.stopExists(feedId, "101"))
        assert(!lookup.stopExists(feedId, "999"))
        assert(lookup.platformsOf(feedId, "adelenstrasse")!!.toSet() == setOf("101", "102"))
        assert(lookup.platformsOf(feedId, "nope") == null)
        assert(lookup.routes(feedId)["1"] == RouteInfo("1", "U41", 1, "FFCC00"))
    }
}
