package de.movedigital.oscdb.feed

import java.time.LocalDate

interface FeedLookup {
    fun findById(id: Int): Feed?

    /** READY feed covering [date]; newest import wins. */
    fun findByDate(date: LocalDate): Feed?

    /** Feed covering today, else READY feed with latest end date (fallback); null if no READY feed. */
    fun current(): FeedResolution?

    /** "current" or numeric READY feed id. Throws NotFoundException otherwise. */
    fun resolve(ref: String): FeedResolution
}
