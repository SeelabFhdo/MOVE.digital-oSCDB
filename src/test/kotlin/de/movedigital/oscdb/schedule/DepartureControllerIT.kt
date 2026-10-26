package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.Fixtures
import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.importer.internal.ZipGtfsSource
import de.movedigital.oscdb.network.NetworkLoader
import org.hamcrest.Matchers.contains
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
class DepartureControllerIT {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var networkLoader: NetworkLoader
    @Autowired lateinit var scheduleLoader: ScheduleLoader
    @Autowired lateinit var registry: FeedRegistry
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var tx: TransactionTemplate
    @TempDir lateinit var tmp: Path

    private var feedId = 0

    @BeforeEach
    fun load() {
        jdbc.sql("truncate feed restart identity cascade").update()
        feedId = registry.register("mini.zip", "sha", FeedInfo("pub", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "v"))
        ZipGtfsSource(Fixtures.miniZip(tmp)).use { src ->
            tx.execute {
                val c = DataSourceUtils.getConnection(dataSource)
                networkLoader.load(feedId, src, c)
                scheduleLoader.load(feedId, src, c)
            }
        }
        registry.markReady(feedId)
    }

    private val stops = "/api/v1/stops"

    @Test
    fun `station departures include previous service day after midnight`() {
        mvc.get("/api/v1/stations/adelenstrasse/departures?date=2026-01-08").andExpect {
            status { isOk() }
            header { doesNotExist("OSCDB-Feed-Fallback") }
            jsonPath("$.serviceDate") { value("2026-01-08") }
            jsonPath("$.feedId") { value(feedId) }
            jsonPath("$.fallback") { value(false) }
            jsonPath("$.departures[*].tripId") { value(contains("T3", "T1", "T2", "T3")) }
            jsonPath("$.departures[0].time") { value("24:30:00") }
            jsonPath("$.departures[0].timestamp") { value("2026-01-08T00:30:00+01:00") }
            jsonPath("$.departures[0].serviceDate") { value("2026-01-07") }
            jsonPath("$.departures[0].stopId") { value("101") }
            jsonPath("$.departures[1].time") { value("08:00:00") }
            jsonPath("$.departures[1].stopId") { value("101") }
            jsonPath("$.departures[1].stopName") { value("Adelenstraße") }
            jsonPath("$.departures[1].headsign") { value("Westfalenhallen") }
            jsonPath("$.departures[1].routeId") { value("1") }
            jsonPath("$.departures[1].routeShortName") { value("U41") }
            jsonPath("$.departures[1].routeColor") { value("FFCC00") }
            jsonPath("$.departures[1].routeType") { value(1) }
            jsonPath("$.departures[1].routeTypeLabel") { value("subway") }
            jsonPath("$.departures[1].timestamp") { value("2026-01-08T08:00:00+01:00") }
            jsonPath("$.departures[2].time") { value("09:00:00") }
            jsonPath("$.departures[2].stopId") { value("102") }
            jsonPath("$.departures[2].headsign") { value("Hauptbahnhof") }
            jsonPath("$.departures[3].serviceDate") { value("2026-01-08") }
            jsonPath("$.departures[3].time") { value("24:30:00") }
            jsonPath("$.departures[3].timestamp") { value("2026-01-09T00:30:00+01:00") }
        }
    }

    @Test
    fun `from filters both service days`() {
        mvc.get("$stops/101/departures?date=2026-01-08&from=07:00").andExpect {
            status { isOk() }
            jsonPath("$.departures[*].tripId") { value(contains("T1", "T3")) }
            jsonPath("$.departures[1].serviceDate") { value("2026-01-08") }
        }
    }

    @Test
    fun `calendar exception removes service`() {
        mvc.get("$stops/101/departures?date=2026-01-06").andExpect {
            status { isOk() }
            jsonPath("$.departures[*].tripId") { value(contains("T3")) }
            jsonPath("$.departures[0].serviceDate") { value("2026-01-05") }
        }
    }

    @Test
    fun `last stop of a trip has no departures`() {
        mvc.get("$stops/300/departures?date=2026-01-08").andExpect {
            status { isOk() }
            jsonPath("$.departures.length()") { value(0) }
        }
    }

    @Test
    fun `limit caps merged result`() {
        mvc.get("/api/v1/stations/adelenstrasse/departures?date=2026-01-08&limit=2").andExpect {
            status { isOk() }
            jsonPath("$.departures[*].tripId") { value(contains("T3", "T1")) }
        }
    }

    @Test
    fun `no feed for the requested date is 404`() {
        for (d in listOf("2026-02-01", "2026-03-15")) {
            mvc.get("$stops/101/departures?date=$d").andExpect {
                status { isNotFound() }
                content { contentTypeCompatibleWith("application/problem+json") }
                jsonPath("$.detail") { value("No timetable covers $d") }
            }
        }
    }

    @Test
    fun `unknown stop or station is 404`() {
        for (p in listOf("stops/999", "stations/nope")) {
            mvc.get("/api/v1/$p/departures?date=2026-01-08").andExpect {
                status { isNotFound() }
                content { contentTypeCompatibleWith("application/problem+json") }
            }
        }
    }

    @Test
    fun `without date uses latest same weekday of fallback feed`() {
        mvc.get("$stops/101/departures").andExpect {
            status { isOk() }
            header { string("OSCDB-Feed-Fallback", "true") }
            jsonPath("$.fallback") { value(true) }
            jsonPath("$.serviceDate") { value("2026-01-26") }
            jsonPath("$.departures[*].tripId") { value(contains("T1", "T3")) }
        }
    }

    @Test
    fun `malformed parameters are 400`() {
        for (q in listOf("date=2026-13-01", "from=25:99", "from=abc", "limit=201", "limit=0", "feedId=x")) {
            mvc.get("$stops/101/departures?$q").andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith("application/problem+json") }
            }
        }
    }

    @Test
    fun `explicit feedId gives same result`() {
        mvc.get("$stops/101/departures?date=2026-01-08&feedId=$feedId").andExpect {
            status { isOk() }
            jsonPath("$.feedId") { value(feedId) }
            jsonPath("$.departures[*].tripId") { value(contains("T3", "T1", "T3")) }
        }
    }

    @Test
    fun `unknown feedId or feed not covering the dates is 404`() {
        mvc.get("$stops/101/departures?date=2026-01-08&feedId=9999").andExpect { status { isNotFound() } }
        mvc.get("$stops/101/departures?date=2026-03-15&feedId=$feedId").andExpect { status { isNotFound() } }
    }

    @Test
    fun `stop missing in one day's feed only drops that day`() {
        // Empty feed imported later wins for 2026-01-08 and has no stop 101.
        val empty = registry.register("empty.zip", "sha-empty", FeedInfo("pub", LocalDate.parse("2026-01-08"), LocalDate.parse("2026-01-08"), "v"))
        registry.markReady(empty)
        mvc.get("$stops/101/departures?date=2026-01-08").andExpect {
            status { isOk() }
            jsonPath("$.feedId") { value(empty) }
            jsonPath("$.departures[*].tripId") { value(contains("T3")) }
            jsonPath("$.departures[0].serviceDate") { value("2026-01-07") }
        }
        mvc.get("$stops/101/departures?date=2026-01-09").andExpect {
            status { isOk() }
            jsonPath("$.feedId") { value(feedId) }
            jsonPath("$.departures[*].tripId") { value(contains("T1", "T3")) }
            jsonPath("$.departures[1].serviceDate") { value("2026-01-09") }
        }
    }
}
