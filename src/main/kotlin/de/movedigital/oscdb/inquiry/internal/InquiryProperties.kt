package de.movedigital.oscdb.inquiry.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("oscdb.inquiry")
class InquiryProperties(
    /** Runs the background worker and the hourly cleanup. */
    val workerEnabled: Boolean,
    val ollamaUrl: String,
    val model: String,
    val maxRows: Int,
    val statementTimeout: Duration,
    val modelTimeout: Duration,
    val retention: Duration,
    val maxQuestionLength: Int,
    val pollInterval: Duration,
    val datasource: Datasource,
) {
    /** Login of the restricted role; [url] defaults to the main datasource's JDBC URL. */
    class Datasource(val url: String?, val username: String, val password: String)
}
