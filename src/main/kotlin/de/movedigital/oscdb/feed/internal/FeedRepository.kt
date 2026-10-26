package de.movedigital.oscdb.feed.internal

import de.movedigital.oscdb.feed.Feed
import de.movedigital.oscdb.feed.FeedCatalog
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedLookup
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.feed.FeedResolution
import de.movedigital.oscdb.feed.FeedStatus
import de.movedigital.oscdb.shared.web.NotFoundException
import de.movedigital.oscdb.shared.web.PageRequest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

@Repository
internal class FeedRepository(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : FeedLookup, FeedRegistry, FeedCatalog {

    private val columns = "id, source_file, publisher, start_date, end_date, version, imported_at, status"

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Feed(
        id = rs.getInt("id"),
        sourceFile = rs.getString("source_file"),
        publisher = rs.getString("publisher"),
        startDate = rs.getObject("start_date", LocalDate::class.java),
        endDate = rs.getObject("end_date", LocalDate::class.java),
        version = rs.getString("version"),
        importedAt = rs.getObject("imported_at", OffsetDateTime::class.java)?.toInstant(),
        status = FeedStatus.valueOf(rs.getString("status")),
    )

    override fun findAll(page: PageRequest): List<Feed> =
        jdbc.sql("select $columns from feed order by id limit :limit offset :offset")
            .param("limit", page.size).param("offset", page.offset)
            .query(::map).list()

    override fun count(): Long = jdbc.sql("select count(*) from feed").query(Long::class.java).single()

    override fun findById(id: Int): Feed? =
        jdbc.sql("select $columns from feed where id = :id").param("id", id).query(::map).optional().orElse(null)

    override fun findReady(id: Int): Feed? = findById(id)?.takeIf { it.status == FeedStatus.READY }

    override fun findByDate(date: LocalDate): Feed? =
        jdbc.sql(
            """select $columns from feed
               where status = 'READY' and start_date <= :d and end_date >= :d
               order by imported_at desc, id desc limit 1""",
        ).param("d", date).query(::map).optional().orElse(null)

    override fun current(): FeedResolution? {
        findByDate(LocalDate.now(clock))?.let { return FeedResolution(it, false) }
        val latest = jdbc.sql(
            "select $columns from feed where status = 'READY' order by end_date desc, imported_at desc, id desc limit 1",
        ).query(::map).optional().orElse(null)
        return latest?.let { FeedResolution(it, true) }
    }

    override fun resolve(ref: String): FeedResolution {
        if (ref == "current") return current() ?: throw NotFoundException("No feed available")
        val id = ref.toIntOrNull() ?: throw NotFoundException("Unknown feed '$ref'")
        val feed = findReady(id) ?: throw NotFoundException("Unknown feed '$ref'")
        return FeedResolution(feed, false)
    }

    override fun statusOf(sha256: String): FeedStatus? =
        jdbc.sql("select status from feed where file_sha256 = :s").param("s", sha256)
            .query(String::class.java).optional().map { FeedStatus.valueOf(it) }.orElse(null)

    override fun deleteBySha(sha256: String) {
        jdbc.sql("delete from feed where file_sha256 = :s").param("s", sha256).update()
    }

    override fun register(sourceFile: String, sha256: String, info: FeedInfo): Int =
        jdbc.sql(
            """insert into feed (source_file, file_sha256, publisher, start_date, end_date, version, status)
               values (:f, :s, :p, :sd, :ed, :v, 'IMPORTING') returning id""",
        ).param("f", sourceFile).param("s", sha256).param("p", info.publisher)
            .param("sd", info.startDate).param("ed", info.endDate).param("v", info.version)
            .query(Int::class.java).single()

    override fun markReady(id: Int) {
        jdbc.sql("update feed set status = 'READY', imported_at = now() where id = :id").param("id", id).update()
    }

    override fun markFailed(id: Int) {
        jdbc.sql("update feed set status = 'FAILED' where id = :id").param("id", id).update()
    }
}
