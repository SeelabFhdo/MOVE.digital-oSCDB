package de.movedigital.oscdb.inquiry.internal

import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Runs one generated query as `oscdb_inquiry` in a read-only transaction that is always rolled back. */
@Component
class QueryExecutor(private val database: InquiryDatabase, private val props: InquiryProperties) {
    private val zone = ZoneId.of("Europe/Berlin")

    /** [sql] must have passed [SqlGuard.check]. Throws `SQLException` on any database error. */
    fun run(sql: String): ResultTable = database.withConnection { c ->
        c.autoCommit = false
        c.isReadOnly = true
        try {
            c.createStatement().use { st ->
                st.execute("set transaction read only")
                st.execute("set local statement_timeout = '${props.statementTimeout.toMillis()}ms'")
                st.execute("set local search_path = inquiry_api")
                st.execute("set local timezone = 'Europe/Berlin'")
                st.execute("explain $sql")
                // Newline before ')' so a trailing line comment in the generated SQL cannot swallow it.
                st.executeQuery("select * from ($sql\n) as q limit ${props.maxRows + 1}").use { read(it) }
            }
        } finally {
            c.rollback()
        }
    }

    private fun read(rs: ResultSet): ResultTable {
        val meta = rs.metaData
        val types = (1..meta.columnCount).map { meta.getColumnTypeName(it) }
        val columns = unique((1..meta.columnCount).map { meta.getColumnLabel(it) })
        val rows = ArrayList<List<Any?>>()
        while (rs.next() && rows.size < props.maxRows + 1) {
            rows += types.mapIndexed { i, type -> value(rs, i + 1, type) }
        }
        val truncated = rows.size > props.maxRows
        return ResultTable(columns, if (truncated) rows.subList(0, props.maxRows) else rows, truncated)
    }

    private fun value(rs: ResultSet, i: Int, type: String): Any? {
        val v: Any? = when (type) {
            "timestamptz" -> rs.getObject(i, OffsetDateTime::class.java)?.atZoneSameInstant(zone)?.toOffsetDateTime()
            "timestamp" -> rs.getObject(i, LocalDateTime::class.java)
            "date" -> rs.getObject(i, LocalDate::class.java)
            "time" -> rs.getObject(i, LocalTime::class.java)
            else -> rs.getObject(i)
        }
        return when (v) {
            null, is String, is Number, is Boolean -> v
            else -> v.toString()
        }
    }

    private fun unique(names: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return names.map { name ->
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            if (n == 1) name else "${name}_$n"
        }
    }
}
