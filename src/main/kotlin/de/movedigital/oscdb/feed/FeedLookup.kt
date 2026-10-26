package de.movedigital.oscdb.feed

import java.time.LocalDate

interface FeedLookup {
    fun findById(id: Int): Feed?

    /** Feed [id] if it exists and is READY, else null. */
    fun findReady(id: Int): Feed?

    /** READY feed covering [date]; newest import wins. */
    fun findByDate(date: LocalDate): Feed?

    /** READY feeds whose date range overlaps [start]..[end], ordered by id. */
    fun findReadyOverlapping(start: LocalDate, end: LocalDate): List<Feed>

    /** Feed covering today, else READY feed with latest end date (fallback); null if no READY feed. */
    fun current(): FeedResolution?

    /** "current" or numeric READY feed id. Throws NotFoundException (`Unknown feed '<ref>'`) otherwise. */
    fun resolve(ref: String): FeedResolution
}
