package de.movedigital.oscdb.schedule

import de.movedigital.oscdb.feed.Feed
import de.movedigital.oscdb.feed.FeedLookup
import de.movedigital.oscdb.feed.FeedStatus
import de.movedigital.oscdb.network.NetworkLookup
import de.movedigital.oscdb.shared.gtfs.GtfsTime
import de.movedigital.oscdb.shared.web.NotFoundException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class DepartureDto(
    val tripId: String,
    val routeId: String,
    val routeShortName: String?,
    val routeColor: String?,
    val routeType: Int?,
    val headsign: String?,
    val stopId: String,
    val stopName: String?,
    /** `HH:MM:SS` since service-day start; hours may be >= 24. */
    val time: String,
    val timestamp: OffsetDateTime,
    val serviceDate: LocalDate,
)

data class DeparturesDto(
    val serviceDate: LocalDate,
    val feedId: Int,
    val fallback: Boolean,
    val departures: List<DepartureDto>,
)

/** Where departures are asked for: one platform or all platforms of a station. */
internal sealed interface DeparturePlace {
    data class Stop(val stopId: String) : DeparturePlace
    data class Station(val stationId: String) : DeparturePlace
}

@Service
internal class DepartureService(
    private val feeds: FeedLookup,
    private val network: NetworkLookup,
    private val calendar: ServiceCalendar,
    private val repo: ScheduleRepository,
    private val clock: Clock,
) {
    private val zone = ZoneId.of("Europe/Berlin")

    fun departures(place: DeparturePlace, date: LocalDate?, fromSecs: Int, limit: Int, feedId: Int?): DeparturesDto {
        val explicitFeed = feedId?.let { id ->
            feeds.findById(id)?.takeIf { it.status == FeedStatus.READY } ?: throw NotFoundException("Unknown feed '$id'")
        }
        val (day, fallback) = targetDate(date)
        val previous = day.minusDays(1)

        val feedOfDay: (LocalDate) -> Feed? = if (explicitFeed != null) {
            { s -> explicitFeed.takeIf { s >= it.startDate && s <= it.endDate } }
        } else {
            feeds::findByDate
        }
        val dayFeed = feedOfDay(day)
        val prevFeed = feedOfDay(previous)
        if (explicitFeed != null && dayFeed == null && prevFeed == null) {
            throw NotFoundException("Feed ${explicitFeed.id} does not cover $day")
        }
        if (explicitFeed == null && dayFeed == null) throw NotFoundException("No timetable covers $day")

        val prevStops = prevFeed?.let { stopIds(it.id, place) }
        val dayStops = dayFeed?.let { stopIds(it.id, place) }
        if (prevStops == null && dayStops == null) throw NotFoundException("${describe(place)} not found")

        val merged = buildList {
            if (prevFeed != null && prevStops != null) addAll(query(prevFeed.id, previous, prevStops, SECONDS_PER_DAY + fromSecs, limit))
            if (dayFeed != null && dayStops != null) addAll(query(dayFeed.id, day, dayStops, fromSecs, limit))
        }.sortedWith(compareBy({ it.timestamp }, { it.tripId }, { it.stopId })).take(limit)

        return DeparturesDto(day, (dayFeed ?: prevFeed)!!.id, fallback, merged)
    }

    /** D and whether it was derived from a fallback feed. */
    private fun targetDate(date: LocalDate?): Pair<LocalDate, Boolean> {
        if (date != null) return date to false
        val current = feeds.current() ?: throw NotFoundException("No timetable available")
        val today = LocalDate.now(clock)
        if (!current.fallback) return today to false
        val feed = current.feed
        // Latest date in the fallback feed with today's weekday.
        val shift = (feed.endDate.dayOfWeek.value - today.dayOfWeek.value + 7) % 7
        val candidate = feed.endDate.minusDays(shift.toLong())
        if (candidate < feed.startDate) throw NotFoundException("No timetable covers a ${today.dayOfWeek} in feed ${feed.id}")
        return candidate to true
    }

    private fun stopIds(feedId: Int, place: DeparturePlace): List<String>? = when (place) {
        is DeparturePlace.Stop -> listOf(place.stopId).takeIf { network.stopExists(feedId, place.stopId) }
        is DeparturePlace.Station -> network.platformsOf(feedId, place.stationId)
    }

    private fun describe(place: DeparturePlace) = when (place) {
        is DeparturePlace.Stop -> "Stop '${place.stopId}'"
        is DeparturePlace.Station -> "Station '${place.stationId}'"
    }

    private fun query(feedId: Int, serviceDate: LocalDate, stopIds: List<String>, minSecs: Int, limit: Int): List<DepartureDto> {
        val services = calendar.activeServices(feedId, serviceDate)
        val rows = repo.departures(feedId, stopIds, services, minSecs, limit)
        if (rows.isEmpty()) return emptyList()
        val routes = network.routes(feedId)
        val names = network.stopNames(feedId)
        val dayStart = ZonedDateTime.of(serviceDate, LocalTime.NOON, zone).minusHours(12)
        return rows.map { r ->
            val route = routes[r.routeId]
            DepartureDto(
                tripId = r.tripId,
                routeId = r.routeId,
                routeShortName = route?.shortName,
                routeColor = route?.color,
                routeType = route?.type,
                headsign = r.headsign,
                stopId = r.stopId,
                stopName = names[r.stopId],
                time = GtfsTime.format(r.departureSecs),
                timestamp = dayStart.plusSeconds(r.departureSecs.toLong()).toOffsetDateTime(),
                serviceDate = serviceDate,
            )
        }
    }

    companion object {
        const val SECONDS_PER_DAY = 86_400
    }
}
