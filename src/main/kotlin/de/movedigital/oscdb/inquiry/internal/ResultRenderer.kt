package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.OutputFormat
import de.siegmar.fastcsv.writer.CsvWriter
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.StringWriter
import java.math.BigDecimal
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/** Renders a [ResultTable] into one of the [OutputFormat]s. */
@Component
class ResultRenderer(private val json: JsonMapper) {

    fun render(table: ResultTable, format: OutputFormat, meta: InquiryMeta): String = when (format) {
        OutputFormat.JSON -> json.writeValueAsString(mapOf("meta" to meta, "rows" to table.rows.map { row(table, it) }))
        OutputFormat.CSV -> csv(table)
        OutputFormat.XML -> xml(table, meta)
        OutputFormat.GEOJSON -> geojson(table, meta)
    }

    /** True if the table has `lat` and `lon` columns (any case), as GeoJSON needs. */
    fun hasCoordinates(table: ResultTable): Boolean = index(table, "lat") >= 0 && index(table, "lon") >= 0

    private fun index(table: ResultTable, name: String) = table.columns.indexOfFirst { it.equals(name, ignoreCase = true) }

    private fun row(table: ResultTable, values: List<Any?>): Map<String, Any?> =
        LinkedHashMap<String, Any?>().apply { table.columns.forEachIndexed { i, c -> put(c, values[i]) } }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is Double -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().let { if ('.' in it) it else "$it.0" }
        is Float -> text(value.toDouble())
        is BigDecimal -> value.toPlainString()
        else -> value.toString()
    }

    private fun csv(table: ResultTable): String {
        val out = StringWriter()
        CsvWriter.builder().build(out).use { w ->
            w.writeRecord(table.columns)
            table.rows.forEach { r -> w.writeRecord(r.map(::text)) }
        }
        return out.toString()
    }

    private fun xml(table: ResultTable, meta: InquiryMeta): String {
        val out = StringWriter()
        val w = XMLOutputFactory.newInstance().createXMLStreamWriter(out)
        w.writeStartDocument("UTF-8", "1.0")
        w.writeStartElement("result")
        w.writeStartElement("meta")
        w.element("sql", meta.sql)
        w.element("rowCount", meta.rowCount)
        w.element("truncated", meta.truncated)
        w.writeStartElement("feeds")
        meta.feeds.forEach { w.element("feed", it) }
        w.writeEndElement()
        w.writeStartElement("assumptions")
        meta.assumptions.forEach { w.element("assumption", it) }
        w.writeEndElement()
        w.writeEndElement()
        val names = table.columns.map(::xmlName)
        w.writeStartElement("rows")
        table.rows.forEach { r ->
            w.writeStartElement("row")
            names.forEachIndexed { i, n -> w.element(n, r[i]) }
            w.writeEndElement()
        }
        w.writeEndElement()
        w.writeEndElement()
        w.writeEndDocument()
        w.close()
        return out.toString()
    }

    private fun XMLStreamWriter.element(name: String, value: Any?) {
        writeStartElement(name)
        if (value == null) writeAttribute("nil", "true") else writeCharacters(text(value))
        writeEndElement()
    }

    /** Column names like `count(*)` or `1st` are not valid XML names: map to `[A-Za-z0-9_]`, never digit-first. */
    private fun xmlName(column: String): String {
        val n = column.replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (n.isEmpty() || !(n[0].isLetter() || n[0] == '_')) "_$n" else n
    }

    private fun geojson(table: ResultTable, meta: InquiryMeta): String {
        val lat = index(table, "lat")
        val lon = index(table, "lon")
        val features = table.rows.map { r ->
            val geometry = if (lat >= 0 && lon >= 0 && r[lat] is Number && r[lon] is Number) {
                mapOf("type" to "Point", "coordinates" to listOf(r[lon], r[lat]))
            } else {
                null
            }
            val properties = LinkedHashMap<String, Any?>()
            table.columns.forEachIndexed { i, c -> if (i != lat && i != lon) properties[c] = r[i] }
            mapOf("type" to "Feature", "geometry" to geometry, "properties" to properties)
        }
        return json.writeValueAsString(mapOf("type" to "FeatureCollection", "meta" to meta, "features" to features))
    }
}
