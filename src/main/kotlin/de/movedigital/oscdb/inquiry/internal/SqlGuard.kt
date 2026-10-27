package de.movedigital.oscdb.inquiry.internal

class InvalidSqlException(message: String) : RuntimeException(message)

/**
 * Early, readable rejection of SQL that cannot be a single read query. Not the security boundary: that is the
 * `oscdb_inquiry` role (SELECT on `inquiry_api` only) and the read-only transaction in [QueryExecutor].
 */
object SqlGuard {
    private val start = Regex("^(select|with)\\b", RegexOption.IGNORE_CASE)

    /** Returns [sql] trimmed and without trailing semicolons. */
    fun check(sql: String): String {
        val cleaned = sql.trim().trimEnd(';', ' ', '\n', '\r', '\t')
        if (cleaned.isEmpty()) throw InvalidSqlException("The SQL is empty")
        if (';' in cleaned) throw InvalidSqlException("Only one SQL statement is allowed (found ';')")
        if (!start.containsMatchIn(cleaned)) throw InvalidSqlException("The SQL must be a single SELECT or WITH query")
        return cleaned
    }
}
