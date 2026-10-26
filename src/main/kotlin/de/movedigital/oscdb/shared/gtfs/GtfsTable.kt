package de.movedigital.oscdb.shared.gtfs

import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.NamedCsvRecord
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader

class GtfsRow internal constructor(private val record: NamedCsvRecord) {
    /** Value of [column]; `null` if the column is missing or the value blank. */
    operator fun get(column: String): String? =
        record.findField(column).orElse(null)?.takeIf { it.isNotBlank() }
}

object GtfsTable {
    fun read(input: InputStream, consumer: (GtfsRow) -> Unit) {
        val reader = PushbackReader(InputStreamReader(input, Charsets.UTF_8), 1)
        val first = reader.read()
        if (first == -1) return
        if (first != 0xFEFF) reader.unread(first)
        CsvReader.builder()
            .allowExtraFields(true)
            .allowMissingFields(true)
            .ofNamedCsvRecord(reader)
            .use { csv -> csv.forEach { consumer(GtfsRow(it)) } }
    }
}
