package de.movedigital.oscdb.feed

import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.shared.web.NotFoundException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class, FeedLookupIT.FixedClock::class)
class FeedLookupIT {
    @TestConfiguration
    class FixedClock {
        @Bean
        @Primary
        fun fixedClock(): Clock =
            Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    }

    @Autowired lateinit var lookup: FeedLookup
    @Autowired lateinit var registry: FeedRegistry
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun clean() {
        jdbc.sql("truncate feed restart identity cascade").update()
    }

    private fun ready(sha: String, start: String, end: String): Int {
        val id = registry.register("$sha.zip", sha, FeedInfo("pub", LocalDate.parse(start), LocalDate.parse(end), "v"))
        registry.markReady(id)
        return id
    }

    @Test
    fun `newest imported feed wins on date`() {
        ready("a", "2026-01-01", "2026-01-31")
        val second = ready("b", "2026-01-01", "2026-01-31")
        assertEquals(second, lookup.findByDate(LocalDate.parse("2026-01-10"))?.id)
    }

    @Test
    fun `importing feed not returned`() {
        registry.register("a.zip", "a", FeedInfo(null, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), null))
        assertNull(lookup.findByDate(LocalDate.parse("2026-01-10")))
    }

    @Test
    fun `current falls back to latest ending feed`() {
        ready("a", "2026-01-01", "2026-06-13")
        val late = ready("b", "2026-06-14", "2026-08-31")
        val r = lookup.current()!!
        assertEquals(late, r.feed.id)
        assertTrue(r.fallback)
    }

    @Test
    fun `current covering today is not fallback`() {
        ready("a", "2026-10-01", "2026-12-31")
        assertFalse(lookup.current()!!.fallback)
    }

    @Test
    fun `current null without ready feed`() {
        assertNull(lookup.current())
    }

    @Test
    fun `resolve unknown throws`() {
        assertThrows<NotFoundException> { lookup.resolve("abc") }
        assertThrows<NotFoundException> { lookup.resolve("999") }
    }

    @Test
    fun `resolve numeric id`() {
        val id = ready("a", "2026-01-01", "2026-01-31")
        val r = lookup.resolve(id.toString())
        assertEquals(id, r.feed.id)
        assertFalse(r.fallback)
    }
}
