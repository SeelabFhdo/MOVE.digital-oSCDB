package de.movedigital.oscdb.inquiry.internal

/** Query result; values are `String`, `Number`, `Boolean` or `null`. Column names are unique. */
data class ResultTable(val columns: List<String>, val rows: List<List<Any?>>, val truncated: Boolean)
