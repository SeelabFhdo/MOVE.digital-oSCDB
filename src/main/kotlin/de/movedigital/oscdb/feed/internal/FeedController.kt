package de.movedigital.oscdb.feed.internal

import de.movedigital.oscdb.feed.Feed
import de.movedigital.oscdb.feed.FeedCatalog
import de.movedigital.oscdb.feed.FeedLookup
import de.movedigital.oscdb.feed.FeedStatus
import de.movedigital.oscdb.shared.web.PageRequest
import de.movedigital.oscdb.shared.web.PageResponse
import de.movedigital.oscdb.shared.web.okWithFallback
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import com.fasterxml.jackson.annotation.JsonUnwrapped
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
    @get:JsonUnwrapped val feed: FeedDto,
    val fallback: Boolean,
)

private fun Feed.toDto() = FeedDto(id, sourceFile, startDate, endDate, version, status, importedAt)

@RestController
@RequestMapping("/api/v1/feeds")
internal class FeedController(
    private val catalog: FeedCatalog,
    private val lookup: FeedLookup,
) {
    @GetMapping
    fun list(@RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): PageResponse<FeedDto> {
        val req = PageRequest.of(page, size)
        return PageResponse(catalog.findAll(req).map { it.toDto() }, req.page, req.size, catalog.count())
    }

    @GetMapping("/{feedRef}")
    fun get(@PathVariable feedRef: String): ResponseEntity<FeedDetailDto> {
        val r = lookup.resolve(feedRef)
        return okWithFallback(FeedDetailDto(r.feed.toDto(), r.fallback), r.fallback)
    }
}
