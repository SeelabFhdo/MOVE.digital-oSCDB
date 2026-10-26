package de.movedigital.oscdb.shared.gtfs

object GtfsTime {
    private val pattern = Regex("""(\d{1,3}):([0-5]\d):([0-5]\d)""")

    /** Parses `H:MM:SS`/`HH:MM:SS` (hours may exceed 23) to seconds since service-day start. */
    fun toSeconds(value: String): Int {
        val m = pattern.matchEntire(value.trim())
            ?: throw IllegalArgumentException("Invalid GTFS time: '$value'")
        val (h, min, s) = m.destructured
        return h.toInt() * 3600 + min.toInt() * 60 + s.toInt()
    }

    fun format(secs: Int): String =
        "%02d:%02d:%02d".format(secs / 3600, secs % 3600 / 60, secs % 60)
}
