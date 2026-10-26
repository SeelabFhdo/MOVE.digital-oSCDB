package de.movedigital.oscdb.feed

import java.time.Instant
import java.time.LocalDate

enum class FeedStatus { IMPORTING, READY, FAILED }

data class Feed(
    val id: Int,
    val sourceFile: String,
    val publisher: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val version: String?,
    val importedAt: Instant?,
    val status: FeedStatus,
)

data class FeedResolution(val feed: Feed, val fallback: Boolean)

data class FeedInfo(
    val publisher: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val version: String?,
)
