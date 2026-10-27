package de.movedigital.oscdb.inquiry

import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.MiniFeedLoader
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.inquiry.internal.InquiryWorker
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class, MiniFeedLoader::class, ScriptedTranslator.Config::class)
class InquiryControllerIT {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mini: MiniFeedLoader
    @Autowired lateinit var model: ScriptedTranslator
    @Autowired lateinit var worker: InquiryWorker
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper

    private val base = "/api/v1/inquiries"
    private var feedId = 0

    @BeforeEach
    fun setUp() {
        mini.reset()
        jdbc.sql("delete from inquiry").update()
        model.reset()
        feedId = mini.load()
    }

    private fun submit(question: String, format: String): String {
        val body = mvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("question" to question, "format" to format))
        }.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        return json.readTree(body)["id"].asString()
    }

    @Test
    fun `submitting returns 202 with location and queue position`() {
        mvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question": "Which lines exist?", "format": "CSV"}"""
        }.andExpect {
            status { isAccepted() }
            header { string("Location", startsWith("/api/v1/inquiries/")) }
            jsonPath("$.status") { value("QUEUED") }
            jsonPath("$.format") { value("csv") }
            jsonPath("$.question") { value("Which lines exist?") }
            jsonPath("$.queuePosition") { value(1) }
        }
    }

    @Test
    fun `an unknown format is a 400 problem naming the allowed formats`() {
        mvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question": "q", "format": "pdf"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value(containsString("json, csv, xml, geojson")) }
        }
    }

    @Test
    fun `a missing format is a 400`() {
        mvc.post(base) { contentType = MediaType.APPLICATION_JSON; content = """{"question": "q"}""" }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a blank question is a 400`() {
        mvc.post(base) { contentType = MediaType.APPLICATION_JSON; content = """{"question": "  ", "format": "json"}""" }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a question over 2000 characters is a 400`() {
        mvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("question" to "x".repeat(2001), "format" to "json"))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `malformed json is a 400 problem`() {
        mvc.post(base) { contentType = MediaType.APPLICATION_JSON; content = "{" }
            .andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
    }

    @Test
    fun `unknown and malformed ids are 404`() {
        mvc.get("$base/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
        mvc.get("$base/not-a-uuid").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Unknown inquiry 'not-a-uuid'") }
        }
        mvc.get("$base/not-a-uuid/result").andExpect { status { isNotFound() } }
    }

    @Test
    fun `the result of a queued job is a 409`() {
        val id = submit("q", "csv")
        mvc.get("$base/$id/result").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("Inquiry $id is QUEUED") }
        }
    }

    @Test
    fun `a finished job has meta and a downloadable csv with headers`() {
        val id = submit("Which lines exist?", "csv")
        model.answer("select line from v_route where feed_id = $feedId order by line", feedId)
        worker.runNext()
        mvc.get("$base/$id").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DONE") }
            jsonPath("$.meta.sql") { value("select line from v_route where feed_id = $feedId order by line") }
            jsonPath("$.meta.rowCount") { value(2) }
            jsonPath("$.meta.feeds[0]") { value(feedId) }
            jsonPath("$.finishedAt") { exists() }
        }
        mvc.get("$base/$id/result").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("text/csv") }
            header { string("OSCDB-Truncated", "false") }
            header { string("OSCDB-Row-Count", "2") }
            content { string(containsString("U41")) }
        }
    }

    @Test
    fun `a geojson result has the geojson content type`() {
        val id = submit("Stops", "geojson")
        model.answer("select name, lat, lon from v_stop where feed_id = $feedId", feedId)
        worker.runNext()
        mvc.get("$base/$id/result").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/geo+json") }
            jsonPath("$.type") { value("FeatureCollection") }
        }
    }

    @Test
    fun `a failed job shows its error and its result is a 409`() {
        val id = submit("How late is U47?", "json")
        model.reply { de.movedigital.oscdb.inquiry.internal.Translation(false, "no realtime data", null, emptyList(), emptyList(), "{}", 1) }
        worker.runNext()
        mvc.get("$base/$id").andExpect {
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.error") { value("Not answerable: no realtime data") }
        }
        mvc.get("$base/$id/result").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("Inquiry $id failed: Not answerable: no realtime data") }
        }
    }
}
