package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.feed.FeedLookup
import de.movedigital.oscdb.inquiry.OutputFormat
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Builds the system prompt from `inquiry/system-prompt.md`. The schema section is generated from the
 * `COMMENT ON` documentation of the `inquiry_api` views, so prompt and views cannot drift apart.
 */
@Component
class PromptBuilder(
    private val jdbc: JdbcClient,
    private val feeds: FeedLookup,
    private val clock: Clock,
) : SystemPromptSource {
    private val template = ClassPathResource("inquiry/system-prompt.md").inputStream.use { it.readBytes().decodeToString() }

    private val schema: String by lazy { describeSchema() }

    override fun systemPrompt(format: OutputFormat): String = template
        .replace("{{schema}}", schema)
        .replace("{{today}}", LocalDate.now(clock).toString())
        .replace("{{currentFeed}}", currentFeed())
        .replace("{{feeds}}", feedList())
        .replace("{{formatRule}}", formatRule(format))

    private fun formatRule(format: OutputFormat): String = when (format) {
        OutputFormat.GEOJSON -> "The result becomes GeoJSON: select lat and lon columns (WGS84) for every row."
        else -> "The result becomes ${format.wire.uppercase()}: select only the columns the question needs."
    }

    private fun currentFeed(): String {
        val current = feeds.current() ?: return "none (no feed imported)"
        val f = current.feed
        val base = "feed ${f.id}: ${f.startDate} to ${f.endDate}"
        return if (current.fallback) "$base. No feed covers today; this latest feed is used as fallback." else base
    }

    private fun feedList(): String =
        jdbc.sql("select feed_id, start_date, end_date from inquiry_api.v_feed order by start_date, feed_id")
            .query { rs, _ -> "  - feed ${rs.getInt(1)}: ${rs.getString(2)} to ${rs.getString(3)}" }
            .list().joinToString("\n").ifEmpty { "  (none)" }

    private fun describeSchema(): String {
        val out = StringBuilder()
        var current: String? = null
        jdbc.sql(
            """select c.relname, obj_description(c.oid, 'pg_class'), a.attname, format_type(a.atttypid, a.atttypmod),
                      col_description(c.oid, a.attnum)
               from pg_class c
               join pg_namespace n on n.oid = c.relnamespace
               join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
               where n.nspname = 'inquiry_api' and c.relkind = 'v'
               order by c.relname, a.attnum""",
        ).query { rs, _ ->
            val view = rs.getString(1)
            if (view != current) {
                current = view
                out.append("\n### ").append(view).append(" — ").append(rs.getString(2) ?: "").append('\n')
            }
            out.append("- ").append(rs.getString(3)).append(' ').append(rs.getString(4))
            rs.getString(5)?.let { out.append(" — ").append(it) }
            out.append('\n')
        }.list()
        jdbc.sql(
            """select p.proname || '(' || pg_get_function_arguments(p.oid) || ') returns ' || pg_get_function_result(p.oid),
                      obj_description(p.oid, 'pg_proc')
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
               where n.nspname = 'inquiry_api' order by p.proname""",
        ).query { rs, _ ->
            out.append("\n### function ").append(rs.getString(1)).append(" — ").append(rs.getString(2) ?: "").append('\n')
        }.list()
        return out.toString().trim()
    }
}
