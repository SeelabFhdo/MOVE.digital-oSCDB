package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.inquiry.InquiryStatus
import de.movedigital.oscdb.inquiry.OutputFormat
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class Inquiry(
    val id: UUID,
    val question: String,
    val format: OutputFormat,
    val status: InquiryStatus,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val meta: InquiryMeta?,
    val error: String?,
    /** 1-based position among queued jobs; null unless QUEUED. */
    val queuePosition: Int?,
)

data class StoredResult(
    val status: InquiryStatus,
    val format: OutputFormat,
    val body: String?,
    val meta: InquiryMeta?,
    val error: String?,
)

/** Jobs older than `oscdb.inquiry.retention` are treated as gone even before [deleteExpired] removes them. */
@Repository
class InquiryRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val clock: Clock,
    private val props: InquiryProperties,
) {
    private val columns = """i.id, i.question, i.format, i.status, i.created_at, i.started_at, i.finished_at, i.meta::text, i.error,
        case when i.status = 'QUEUED' then (select count(*) from inquiry q where q.status = 'QUEUED' and q.seq <= i.seq) end"""

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun cutoff() = OffsetDateTime.ofInstant(clock.instant().minus(props.retention), ZoneOffset.UTC)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Inquiry(
        id = rs.getObject(1, UUID::class.java),
        question = rs.getString(2),
        format = OutputFormat.valueOf(rs.getString(3)),
        status = InquiryStatus.valueOf(rs.getString(4)),
        createdAt = rs.getObject(5, OffsetDateTime::class.java).toInstant(),
        startedAt = rs.getObject(6, OffsetDateTime::class.java)?.toInstant(),
        finishedAt = rs.getObject(7, OffsetDateTime::class.java)?.toInstant(),
        meta = meta(rs.getString(8)),
        error = rs.getString(9),
        queuePosition = rs.getObject(10)?.let { (it as Number).toInt() },
    )

    private fun meta(text: String?): InquiryMeta? = text?.let { json.readValue(it, InquiryMeta::class.java) }

    fun create(question: String, format: OutputFormat): Inquiry {
        val id = UUID.randomUUID()
        jdbc.sql("insert into inquiry (id, question, format, status, created_at) values (:id, :q, :f, 'QUEUED', :t)")
            .param("id", id).param("q", question).param("f", format.name).param("t", now()).update()
        return find(id)!!
    }

    fun find(id: UUID): Inquiry? =
        jdbc.sql("select $columns from inquiry i where i.id = :id and i.created_at > :cutoff")
            .param("id", id).param("cutoff", cutoff()).query(::map).optional().orElse(null)

    fun result(id: UUID): StoredResult? =
        jdbc.sql("select status, format, result, meta::text, error from inquiry where id = :id and created_at > :cutoff")
            .param("id", id).param("cutoff", cutoff())
            .query { rs, _ ->
                StoredResult(
                    InquiryStatus.valueOf(rs.getString(1)), OutputFormat.valueOf(rs.getString(2)),
                    rs.getString(3), meta(rs.getString(4)), rs.getString(5),
                )
            }.optional().orElse(null)

    /** Marks the oldest QUEUED job RUNNING and returns it; null if the queue is empty. */
    fun claimNext(): Inquiry? {
        val id = jdbc.sql(
            """update inquiry set status = 'RUNNING', started_at = :now
               where id = (select id from inquiry where status = 'QUEUED' order by seq limit 1 for update skip locked)
               returning id""",
        ).param("now", now()).query(UUID::class.java).optional().orElse(null) ?: return null
        return find(id)
    }

    fun complete(id: UUID, meta: InquiryMeta, body: String) {
        jdbc.sql("update inquiry set status = 'DONE', finished_at = :now, meta = cast(:meta as jsonb), result = :body, error = null where id = :id")
            .param("now", now()).param("meta", json.writeValueAsString(meta)).param("body", body).param("id", id).update()
    }

    fun fail(id: UUID, error: String, meta: InquiryMeta?) {
        jdbc.sql("update inquiry set status = 'FAILED', finished_at = :now, meta = cast(:meta as jsonb), error = :error where id = :id")
            .param("now", now()).param("meta", meta?.let { json.writeValueAsString(it) }).param("error", error).param("id", id).update()
    }

    /** After a crash or restart: jobs left RUNNING go back to the queue. */
    fun requeueRunning(): Int =
        jdbc.sql("update inquiry set status = 'QUEUED', started_at = null where status = 'RUNNING'").update()

    fun deleteExpired(): Int =
        jdbc.sql("delete from inquiry where created_at <= :cutoff").param("cutoff", cutoff()).update()
}
