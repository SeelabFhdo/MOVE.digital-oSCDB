package de.movedigital.oscdb.feed

import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class FeedControllerIT {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var registry: FeedRegistry
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun clean() {
        jdbc.sql("truncate feed restart identity cascade").update()
    }

    private fun ready(sha: String, start: String, end: String) {
        registry.markReady(registry.register("$sha.zip", sha, FeedInfo("pub", LocalDate.parse(start), LocalDate.parse(end), "v")))
    }

    @Test
    fun `current fallback header and field`() {
        ready("a", "2026-01-01", "2026-08-31")
        mvc.get("/api/v1/feeds/current").andExpect {
            status { isOk() }
            header { string("OSCDB-Feed-Fallback", "true") }
            jsonPath("$.fallback") { value(true) }
        }
    }

    @Test
    fun `current without fallback has no header`() {
        ready("a", "2026-10-01", "2026-12-31")
        mvc.get("/api/v1/feeds/current").andExpect {
            status { isOk() }
            header { doesNotExist("OSCDB-Feed-Fallback") }
            jsonPath("$.fallback") { value(false) }
        }
    }

    @Test
    fun `unknown feed is 404 problem json`() {
        mvc.get("/api/v1/feeds/999").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith("application/problem+json") }
        }
    }

    @Test
    fun `oversized page is 400`() {
        mvc.get("/api/v1/feeds?size=501").andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith("application/problem+json") }
        }
    }

    @Test
    fun `list returns all statuses`() {
        ready("a", "2026-01-01", "2026-01-31")
        registry.register("b.zip", "b", FeedInfo(null, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"), null))
        mvc.get("/api/v1/feeds").andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
            jsonPath("$.items.length()") { value(2) }
        }
    }
}
