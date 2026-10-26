package de.movedigital.oscdb.shared.gtfs

import java.io.InputStream

/** Access to the files of one GTFS feed. */
interface GtfsSource {
    /** Opens e.g. `"stops.txt"` (case-insensitive lookup); `null` if the file is absent. */
    fun open(fileName: String): InputStream?
}
