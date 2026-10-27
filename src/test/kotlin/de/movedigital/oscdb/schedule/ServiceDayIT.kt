package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.MiniFeedLoader
import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class, MiniFeedLoader::class)
class ServiceDayIT {
    @Autowired lateinit var mini: MiniFeedLoader
    @Autowired lateinit var jdbc: JdbcClient

    private var feedId = 0

    @BeforeEach
    fun load() {
        mini.reset()
        feedId = mini.load()
    }

    private fun days(service: String): List<LocalDate?> =
        jdbc.sql("select service_date from service_day where feed_id = :f and service_id = :s order by service_date")
            .param("f", feedId).param("s", service).query(LocalDate::class.java).list()

    @Test
    fun `service 1 runs on 21 days and not on 2026-01-06`() {
        val days = days("1")
        assertEquals(21, days.size)
        assertEquals(false, LocalDate.parse("2026-01-06") in days)
    }

    @Test
    fun `service 2 runs on weekends plus 2026-01-06`() {
        val days = days("2")
        assertEquals(10, days.size)
        assertEquals(true, LocalDate.parse("2026-01-06") in days)
    }

    @Test
    fun `deleting the feed removes its service days`() {
        jdbc.sql("delete from feed where id = :f").param("f", feedId).update()
        assertEquals(0, jdbc.sql("select count(*) from service_day").query(Int::class.java).single())
    }
}
