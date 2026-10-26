package de.movedigital.oscdb.feed

import de.movedigital.oscdb.shared.web.PageRequest
import de.movedigital.oscdb.shared.web.PageResponse
import de.movedigital.oscdb.shared.web.okWithFallback
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

data class FeedDto(
    val id: Int,
    val sourceFile: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val version: String?,
    val status: FeedStatus,
    val importedAt: Instant?,
)

data class FeedDetailDto(
    val id: Int,
    val sourceFile: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val version: String?,
    val status: FeedStatus,
    val importedAt: Instant?,
    val fallback: Boolean,
)

private fun Feed.toDto() = FeedDto(id, sourceFile, startDate, endDate, version, status, importedAt)

@RestController
@RequestMapping("/api/v1/feeds")
internal class FeedController(
    private val repository: FeedRepository,
) {
    @GetMapping
    fun list(@RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): PageResponse<FeedDto> {
        val req = PageRequest.of(page, size)
        return PageResponse(repository.findAll(req).map { it.toDto() }, req.page, req.size, repository.count())
    }

    @GetMapping("/{feedRef}")
    fun get(@PathVariable feedRef: String): ResponseEntity<FeedDetailDto> {
        val r = repository.resolve(feedRef)
        val f = r.feed
        return okWithFallback(
            FeedDetailDto(f.id, f.sourceFile, f.startDate, f.endDate, f.version, f.status, f.importedAt, r.fallback),
            r.fallback,
        )
    }
}
