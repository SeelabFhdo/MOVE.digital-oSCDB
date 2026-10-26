package de.movedigital.oscdb.network

import de.movedigital.oscdb.feed.FeedLookup
import de.movedigital.oscdb.shared.web.NotFoundException
import de.movedigital.oscdb.shared.web.PageRequest
import de.movedigital.oscdb.shared.web.PageResponse
import de.movedigital.oscdb.shared.web.okWithFallback
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class AgencyDto(val id: String, val name: String, val url: String?, val timezone: String?)

data class RouteDto(
    val id: String,
    val shortName: String?,
    val longName: String?,
    val type: Int?,
    val typeLabel: String,
    val color: String?,
    val textColor: String?,
    val agencyId: String?,
)

data class StopDto(val id: String, val name: String?, val lat: Double?, val lon: Double?, val stationId: String?)

data class StationDto(val id: String, val name: String, val lat: Double, val lon: Double)

data class StationDetailDto(val id: String, val name: String, val lat: Double, val lon: Double, val platforms: List<StopDto>)

@RestController
@RequestMapping("/api/v1/feeds/{feedRef}")
internal class NetworkController(
    private val feeds: FeedLookup,
    private val repo: NetworkRepository,
) {
    @GetMapping("/agencies")
    fun agencies(@PathVariable feedRef: String): ResponseEntity<List<AgencyDto>> {
        val r = feeds.resolve(feedRef)
        return okWithFallback(repo.agencies(r.feed.id), r.fallback)
    }

    @GetMapping("/routes")
    fun routes(
        @PathVariable feedRef: String,
        @RequestParam(required = false) type: Int?,
        @RequestParam(required = false) agency: String?,
    ): ResponseEntity<List<RouteDto>> {
        val r = feeds.resolve(feedRef)
        return okWithFallback(repo.routeList(r.feed.id, type, agency), r.fallback)
    }

    @GetMapping("/routes/{routeId}")
    fun route(@PathVariable feedRef: String, @PathVariable routeId: String): ResponseEntity<RouteDto> {
        val r = feeds.resolve(feedRef)
        val route = repo.route(r.feed.id, routeId) ?: throw NotFoundException("Route '$routeId' not found")
        return okWithFallback(route, r.fallback)
    }

    @GetMapping("/stops")
    fun stops(
        @PathVariable feedRef: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<PageResponse<StopDto>> {
        val req = PageRequest.of(page, size)
        val r = feeds.resolve(feedRef)
        return okWithFallback(repo.stops(r.feed.id, q, req), r.fallback)
    }

    @GetMapping("/stops/{stopId}")
    fun stop(@PathVariable feedRef: String, @PathVariable stopId: String): ResponseEntity<StopDto> {
        val r = feeds.resolve(feedRef)
        val stop = repo.stop(r.feed.id, stopId) ?: throw NotFoundException("Stop '$stopId' not found")
        return okWithFallback(stop, r.fallback)
    }

    @GetMapping("/stations")
    fun stations(
        @PathVariable feedRef: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<PageResponse<StationDto>> {
        val req = PageRequest.of(page, size)
        val r = feeds.resolve(feedRef)
        return okWithFallback(repo.stations(r.feed.id, q, req), r.fallback)
    }

    @GetMapping("/stations/{stationId}")
    fun station(@PathVariable feedRef: String, @PathVariable stationId: String): ResponseEntity<StationDetailDto> {
        val r = feeds.resolve(feedRef)
        val station = repo.station(r.feed.id, stationId) ?: throw NotFoundException("Station '$stationId' not found")
        return okWithFallback(station, r.fallback)
    }
}
