package de.movedigital.oscdb.schedule

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

/** Resolves which services run on a date: calendar weekday rows in range, plus exception type 1, minus type 2. */
@Component
internal class ServiceCalendar(private val jdbc: JdbcClient) {
    fun activeServices(feedId: Int, date: LocalDate): Set<String> {
        val weekday = weekdayColumn(date.dayOfWeek)
        return jdbc.sql(
            """select service_id from calendar
               where feed_id = :feed and start_date <= :d and end_date >= :d and $weekday
               union
               select service_id from calendar_date where feed_id = :feed and date = :d and exception_type = 1
               except
               select service_id from calendar_date where feed_id = :feed and date = :d and exception_type = 2""",
        ).param("feed", feedId).param("d", date)
            .query { rs, _ -> rs.getString(1) }.set()
    }

    private fun weekdayColumn(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "monday"
        DayOfWeek.TUESDAY -> "tuesday"
        DayOfWeek.WEDNESDAY -> "wednesday"
        DayOfWeek.THURSDAY -> "thursday"
        DayOfWeek.FRIDAY -> "friday"
        DayOfWeek.SATURDAY -> "saturday"
        DayOfWeek.SUNDAY -> "sunday"
    }
}
