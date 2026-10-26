package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.Fixtures
import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.importer.ZipGtfsSource
import de.movedigital.oscdb.network.NetworkLoader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class ServiceCalendarIT {
    @Autowired internal lateinit var calendar: ServiceCalendar
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

    private fun active(date: String) = calendar.activeServices(feedId, LocalDate.parse(date))

    @Test
    fun `weekday flags and calendar exceptions`() {
        assertEquals(setOf("1"), active("2026-01-05"))
        assertEquals(setOf("2"), active("2026-01-06"))
        assertEquals(setOf("2"), active("2026-01-10"))
        assertEquals(emptySet<String>(), active("2026-02-01"))
    }
}
