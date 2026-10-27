package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.OutputFormat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.SQLException

/** Answers one job: model translation, guard, restricted execution, rendering; one repair attempt on failure. */
@Component
class InquiryProcessor(
    private val translator: SqlTranslator,
    private val executor: QueryExecutor,
    private val renderer: ResultRenderer,
    private val repo: InquiryRepository,
) {
    private val log = LoggerFactory.getLogger(InquiryProcessor::class.java)

    private val maxAttempts = 2

    /** Never throws; the job always ends DONE or FAILED. */
    fun process(job: Inquiry) {
        try {
            answer(job)
        } catch (e: Exception) {
            log.error("Inquiry {} failed unexpectedly", job.id, e)
            runCatching { repo.fail(job.id, "Internal error", null) }
                .onFailure { log.error("Could not mark inquiry {} FAILED", job.id, it) }
        }
    }

    private fun answer(job: Inquiry) {
        var retry: RetryContext? = null
        var lastError = ""
        var modelMillis = 0L
        val sqls = mutableListOf<String>()
        var assumptions = emptyList<String>()

        fun meta(attempts: Int, feeds: List<Int> = emptyList(), table: ResultTable? = null, queryMillis: Long? = null) = InquiryMeta(
            sql = sqls.lastOrNull(), previousSql = sqls.getOrNull(sqls.size - 2), assumptions = assumptions, feeds = feeds,
            rowCount = table?.rows?.size, truncated = table?.truncated, model = translator.model,
            modelMillis = modelMillis, queryMillis = queryMillis, attempts = attempts,
        )

        for (attempt in 1..maxAttempts) {
            val translation = try {
                translator.translate(job.question, job.format, retry)
            } catch (e: ModelUnavailableException) {
                repo.fail(job.id, e.message ?: "Model unavailable", meta(attempt))
                return
            } catch (e: InvalidModelOutputException) {
                lastError = "Invalid model answer: ${e.message}"
                retry = RetryContext(e.raw, e.message ?: "invalid answer")
                continue
            }
            modelMillis += translation.millis
            assumptions = translation.assumptions
            if (!translation.answerable) {
                repo.fail(job.id, "Not answerable: ${translation.reason ?: "no reason given"}", meta(attempt))
                return
            }
            val sql = translation.sql!!
            sqls += sql
            val error = try {
                val checked = SqlGuard.check(sql)
                val started = System.nanoTime()
                val table = executor.run(checked)
                val queryMillis = (System.nanoTime() - started) / 1_000_000
                if (job.format == OutputFormat.GEOJSON && !renderer.hasCoordinates(table)) {
                    "geojson needs lat and lon columns in the result"
                } else {
                    val meta = meta(attempt, feeds(translation.feedIds, table), table, queryMillis)
                    repo.complete(job.id, meta, renderer.render(table, job.format, meta))
                    return
                }
            } catch (e: InvalidSqlException) {
                e.message ?: "invalid SQL"
            } catch (e: SQLException) {
                e.message ?: "SQL error"
            }
            lastError = error
            retry = RetryContext(translation.raw, error)
        }
        repo.fail(job.id, "Query failed: $lastError", meta(maxAttempts))
    }

    /** Feeds the model says it used, plus any `feed_id` values in the result. */
    private fun feeds(declared: List<Int>, table: ResultTable): List<Int> {
        val column = table.columns.indexOfFirst { it.equals("feed_id", ignoreCase = true) }
        val found = if (column < 0) emptyList() else table.rows.mapNotNull { (it[column] as? Number)?.toInt() }
        return (declared + found).distinct().sorted()
    }
}
