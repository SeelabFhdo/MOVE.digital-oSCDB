package de.movedigital.oscdb.inquiry.internal

/** How a result was produced; shown on the job and embedded in json/xml/geojson results. */
data class InquiryMeta(
    val sql: String?,
    /** SQL of the failed first attempt when the query had to be repaired. */
    val previousSql: String?,
    val assumptions: List<String>,
    val feeds: List<Int>,
    val rowCount: Int?,
    val truncated: Boolean?,
    val model: String,
    val modelMillis: Long,
    val queryMillis: Long?,
    val attempts: Int,
)
