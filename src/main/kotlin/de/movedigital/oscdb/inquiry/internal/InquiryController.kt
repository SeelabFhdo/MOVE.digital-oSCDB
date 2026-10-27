package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.InquiryStatus
import de.movedigital.oscdb.inquiry.OutputFormat
import de.movedigital.oscdb.shared.web.BadRequestException
import de.movedigital.oscdb.shared.web.ConflictException
import de.movedigital.oscdb.shared.web.NotFoundException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

data class InquiryRequest(val question: String?, val format: String?)

data class InquiryDto(
    val id: UUID,
    val question: String,
    val format: String,
    val status: InquiryStatus,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val queuePosition: Int?,
    val error: String?,
    val meta: InquiryMeta?,
)

private fun Inquiry.toDto() = InquiryDto(id, question, format.wire, status, createdAt, startedAt, finishedAt, queuePosition, error, meta)

const val TRUNCATED_HEADER = "OSCDB-Truncated"
const val ROW_COUNT_HEADER = "OSCDB-Row-Count"

@RestController
@RequestMapping("/api/v1/inquiries")
internal class InquiryController(private val repo: InquiryRepository, private val props: InquiryProperties) {
    private val formats = OutputFormat.entries.joinToString { it.wire }

    @PostMapping
    fun submit(@RequestBody request: InquiryRequest): ResponseEntity<InquiryDto> {
        val question = request.question?.trim()
        if (question.isNullOrEmpty()) throw BadRequestException("'question' is required")
        if (question.length > props.maxQuestionLength) {
            throw BadRequestException("'question' must be at most ${props.maxQuestionLength} characters")
        }
        val format = OutputFormat.parse(request.format)
            ?: throw BadRequestException("'format' must be one of: $formats")
        val job = repo.create(question, format)
        return ResponseEntity.accepted().location(URI.create("/api/v1/inquiries/${job.id}")).body(job.toDto())
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): InquiryDto = repo.find(parse(id))?.toDto() ?: throw unknown(id)

    @GetMapping("/{id}/result")
    fun result(@PathVariable id: String): ResponseEntity<String> {
        val uuid = parse(id)
        val r = repo.result(uuid) ?: throw unknown(id)
        when (r.status) {
            InquiryStatus.DONE -> {}
            InquiryStatus.FAILED -> throw ConflictException("Inquiry $id failed: ${r.error}")
            else -> throw ConflictException("Inquiry $id is ${r.status}")
        }
        val type = MediaType(MediaType.parseMediaType(r.format.contentType), StandardCharsets.UTF_8)
        return ResponseEntity.ok()
            .contentType(type)
            .header(TRUNCATED_HEADER, (r.meta?.truncated ?: false).toString())
            .header(ROW_COUNT_HEADER, (r.meta?.rowCount ?: 0).toString())
            .body(r.body)
    }

    private fun parse(id: String): UUID = runCatching { UUID.fromString(id) }.getOrNull() ?: throw unknown(id)

    private fun unknown(id: String) = NotFoundException("Unknown inquiry '$id'")
}
