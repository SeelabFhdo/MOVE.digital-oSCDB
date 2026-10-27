package de.movedigital.oscdb.inquiry

/** Result file formats of an inquiry. */
enum class OutputFormat(val wire: String, val contentType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
    XML("xml", "application/xml"),
    GEOJSON("geojson", "application/geo+json"),
    ;

    companion object {
        /** Case-insensitive; null if [value] is not one of the [wire] names. */
        fun parse(value: String?): OutputFormat? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}
