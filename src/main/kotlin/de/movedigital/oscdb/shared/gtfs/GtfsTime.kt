package de.movedigital.oscdb.shared.gtfs

import java.util.Locale

object GtfsTime {
    private val pattern = Regex("""(\d{1,3}):([0-5]\d):([0-5]\d)""")

    /** Parses `H:MM:SS`/`HH:MM:SS` (hours may exceed 23) to seconds since service-day start. */
    fun toSeconds(value: String): Int {
        val m = pattern.matchEntire(value.trim())
            ?: throw IllegalArgumentException("Invalid GTFS time: '$value'")
        val (h, min, s) = m.destructured
        return h.toInt() * 3600 + min.toInt() * 60 + s.toInt()
    }

    /** Formats seconds since service-day start as `HH:MM:SS` (hours may exceed 23), always with ASCII digits. */
    fun format(secs: Int): String {
        require(secs >= 0) { "GTFS time must not be negative: $secs" }
        return String.format(Locale.ROOT, "%02d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60)
    }
}
