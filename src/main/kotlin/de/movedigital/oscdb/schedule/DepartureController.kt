package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.shared.web.BadRequestException
import de.movedigital.oscdb.shared.web.okWithFallback
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1")
internal class DepartureController(private val service: DepartureService) {
    @GetMapping("/stops/{stopId}/departures")
    fun stopDepartures(
        @PathVariable stopId: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) feedId: Int?,
    ): ResponseEntity<DeparturesDto> = respond(DeparturePlace.Stop(stopId), date, from, limit, feedId)

    @GetMapping("/stations/{stationId}/departures")
    fun stationDepartures(
        @PathVariable stationId: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) feedId: Int?,
    ): ResponseEntity<DeparturesDto> = respond(DeparturePlace.Station(stationId), date, from, limit, feedId)

    private fun respond(place: DeparturePlace, date: LocalDate?, from: String?, limit: Int?, feedId: Int?): ResponseEntity<DeparturesDto> {
        val fromSecs = parseFrom(from)
        val max = limit ?: DEFAULT_LIMIT
        if (max < 1 || max > MAX_LIMIT) throw BadRequestException("limit must be between 1 and $MAX_LIMIT")
        val result = service.departures(place, date, fromSecs, max, feedId)
        return okWithFallback(result, result.fallback)
    }

    private fun parseFrom(from: String?): Int {
        if (from == null) return 0
        val m = FROM_PATTERN.matchEntire(from) ?: throw BadRequestException("from must be HH:MM (00:00-23:59)")
        return m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 200
        private val FROM_PATTERN = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")
    }
}
