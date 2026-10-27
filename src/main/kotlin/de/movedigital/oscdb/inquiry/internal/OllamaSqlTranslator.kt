package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.OutputFormat
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration

/**
 * [SqlTranslator] backed by a local Ollama model (`POST /api/chat`). The installed models do not support Ollama's
 * JSON output mode, so the JSON object is requested by the prompt and extracted leniently from the answer.
 */
@Component
class OllamaSqlTranslator(
    private val props: InquiryProperties,
    private val prompts: SystemPromptSource,
    private val json: JsonMapper,
) : SqlTranslator {
    override val model: String get() = props.model

    private val client = RestClient.builder()
        .baseUrl(props.ollamaUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                .apply { setReadTimeout(props.modelTimeout) },
        )
        .build()

    override fun translate(question: String, format: OutputFormat, retry: RetryContext?): Translation {
        val messages = mutableListOf(
            mapOf("role" to "system", "content" to prompts.systemPrompt(format)),
            mapOf("role" to "user", "content" to question),
        )
        if (retry != null) {
            messages += mapOf("role" to "assistant", "content" to retry.previousAnswer)
            messages += mapOf(
                "role" to "user",
                "content" to "That answer failed: ${retry.error}\nReply with a corrected JSON object only.",
            )
        }
        val request = mapOf(
            "model" to props.model,
            "stream" to false,
            "think" to false,
            "options" to mapOf("temperature" to 0, "num_ctx" to 16384),
            "messages" to messages,
        )
        val started = System.nanoTime()
        val body = try {
            client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(request))
                .retrieve().body(String::class.java)
        } catch (e: RestClientException) {
            throw ModelUnavailableException("Model unavailable: ${e.message}", e)
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val content = try {
            json.readTree(body ?: "")["message"]?.get("content")?.asString()
        } catch (e: JacksonException) {
            null
        } ?: throw ModelUnavailableException("Model unavailable: unexpected response from Ollama")
        return parse(content, millis)
    }

    private fun parse(content: String, millis: Long): Translation {
        val text = content.replace(Regex("(?s)<think>.*?</think>"), "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start) throw InvalidModelOutputException(content, "The answer contained no JSON object")
        val node = try {
            json.readTree(text.substring(start, end + 1))
        } catch (e: JacksonException) {
            throw InvalidModelOutputException(content, "The answer was not valid JSON: ${e.originalMessage}")
        }
        val answerable = node["answerable"]?.asBoolean(true) ?: true
        val sql = node.string("sql")
        if (answerable && sql.isNullOrBlank()) throw InvalidModelOutputException(content, "The answer has no sql")
        return Translation(
            answerable = answerable,
            reason = node.string("reason"),
            sql = sql,
            assumptions = node["assumptions"]?.mapNotNull { a -> a.takeIf { !it.isNull }?.asString() } ?: emptyList(),
            feedIds = node["feedIds"]?.mapNotNull { f -> f.takeIf { it.isNumber }?.asInt() } ?: emptyList(),
            raw = content,
            millis = millis,
        )
    }

    private fun JsonNode.string(field: String): String? = get(field)?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }
}
