package de.movedigital.oscdb.inquiry.internal

import com.sun.net.httpserver.HttpServer
import de.movedigital.oscdb.inquiry.OutputFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OllamaSqlTranslatorTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private var server: HttpServer? = null
    private val requests = mutableListOf<JsonNode>()

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    /** Starts a fake Ollama that answers `/api/chat` with [status] and an assistant message [content]. */
    private fun ollama(content: String, status: Int = 200): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/chat") { ex ->
            requests.add(json.readTree(ex.requestBody.readAllBytes()))
            val body = json.writeValueAsBytes(mapOf("message" to mapOf("role" to "assistant", "content" to content)))
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun translator(url: String) = OllamaSqlTranslator(props(url), { format -> "SYSTEM ${format.wire}" }, json)

    private fun props(url: String) = InquiryProperties(
        workerEnabled = false, ollamaUrl = url, model = "test-model", maxRows = 10, statementTimeout = Duration.ofSeconds(1),
        modelTimeout = Duration.ofSeconds(5), retention = Duration.ofHours(24), maxQuestionLength = 2000,
        pollInterval = Duration.ofSeconds(1), datasource = InquiryProperties.Datasource(null, "u", "p"),
    )

    @Test
    fun `parses an answer wrapped in a code fence`() {
        val url = ollama("```json\n{\"answerable\":true,\"reason\":null,\"sql\":\"select 1\",\"assumptions\":[\"a\"],\"feedIds\":[19]}\n```")
        val t = translator(url).translate("How many?", OutputFormat.CSV, null)
        assertEquals(true, t.answerable)
        assertEquals("select 1", t.sql)
        assertEquals(listOf("a"), t.assumptions)
        assertEquals(listOf(19), t.feedIds)
        assertNull(t.reason)
    }

    @Test
    fun `ignores a think block before the answer`() {
        val url = ollama("<think>maybe {x}</think>\n{\"answerable\":true,\"sql\":\"select 2\",\"assumptions\":[],\"feedIds\":[]}")
        assertEquals("select 2", translator(url).translate("q", OutputFormat.JSON, null).sql)
    }

    @Test
    fun `sends model, system prompt and question without streaming`() {
        val url = ollama("{\"answerable\":true,\"sql\":\"select 1\",\"assumptions\":[],\"feedIds\":[]}")
        translator(url).translate("How many?", OutputFormat.GEOJSON, null)
        val r = requests.single()
        assertEquals("test-model", r["model"].asString())
        assertEquals(false, r["stream"].asBoolean())
        assertEquals(false, r["think"].asBoolean())
        assertEquals(0, r["options"]["temperature"].asInt())
        assertEquals(2, r["messages"].size())
        assertEquals("SYSTEM geojson", r["messages"][0]["content"].asString())
        assertEquals("How many?", r["messages"][1]["content"].asString())
    }

    @Test
    fun `a retry sends the failed answer and the error`() {
        val url = ollama("{\"answerable\":true,\"sql\":\"select 1\",\"assumptions\":[],\"feedIds\":[]}")
        translator(url).translate("q", OutputFormat.CSV, RetryContext("{\"sql\":\"select nope\"}", "column \"nope\" does not exist"))
        val messages = requests.single()["messages"]
        assertEquals(4, messages.size())
        assertEquals("assistant", messages[2]["role"].asString())
        assertContains(messages[3]["content"].asString(), "column \"nope\" does not exist")
    }

    @Test
    fun `an answer without json is invalid output`() {
        val url = ollama("no json here")
        val e = assertFailsWith<InvalidModelOutputException> { translator(url).translate("q", OutputFormat.CSV, null) }
        assertEquals("no json here", e.raw)
    }

    @Test
    fun `an answerable answer without sql is invalid output`() {
        val url = ollama("{\"answerable\":true,\"assumptions\":[]}")
        assertFailsWith<InvalidModelOutputException> { translator(url).translate("q", OutputFormat.CSV, null) }
    }

    @Test
    fun `an unanswerable answer carries the reason`() {
        val url = ollama("{\"answerable\":false,\"reason\":\"no realtime data\",\"sql\":\"\",\"assumptions\":[],\"feedIds\":[]}")
        val t = translator(url).translate("q", OutputFormat.CSV, null)
        assertEquals(false, t.answerable)
        assertEquals("no realtime data", t.reason)
    }

    @Test
    fun `server errors mean the model is unavailable`() {
        val url = ollama("boom", status = 500)
        assertFailsWith<ModelUnavailableException> { translator(url).translate("q", OutputFormat.CSV, null) }
    }

    @Test
    fun `a closed port means the model is unavailable`() {
        val port = ServerSocket(0).use { it.localPort }
        assertFailsWith<ModelUnavailableException> { translator("http://127.0.0.1:$port").translate("q", OutputFormat.CSV, null) }
    }
}
