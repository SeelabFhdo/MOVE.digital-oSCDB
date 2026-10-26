package de.movedigital.oscdb.shared.jdbc

import org.postgresql.PGConnection
import org.postgresql.copy.PGCopyOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.sql.Connection

class CopyRowWriter internal constructor(private val out: BufferedWriter) {
    /** Writes one CSV row: null as empty unquoted field, strings quoted, others via toString(). */
    fun row(vararg values: Any?) {
        values.forEachIndexed { i, v ->
            if (i > 0) out.write(','.code)
            when (v) {
                null -> {}
                is String -> {
                    out.write('"'.code)
                    out.write(v.replace("\"", "\"\""))
                    out.write('"'.code)
                }
                else -> out.write(v.toString())
            }
        }
        out.write('\n'.code)
    }
}

class PgCopy(private val conn: Connection) {
    /** Streams rows into [table] via COPY ... FROM STDIN; returns the number of rows copied. */
    fun into(table: String, columns: List<String>, block: (CopyRowWriter) -> Unit): Long {
        val copyIn = conn.unwrap(PGConnection::class.java).copyAPI
            .copyIn("COPY $table (${columns.joinToString(", ")}) FROM STDIN (FORMAT csv)")
        val stream = PGCopyOutputStream(copyIn)
        try {
            val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16)
            block(CopyRowWriter(writer))
            writer.flush()
            stream.close()
        } catch (e: Throwable) {
            if (copyIn.isActive) runCatching { copyIn.cancelCopy() }
            throw e
        }
        return copyIn.handledRowCount
    }
}
