package de.movedigital.oscdb

import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.importer.internal.ZipGtfsSource
import de.movedigital.oscdb.network.NetworkLoader
import de.movedigital.oscdb.schedule.ScheduleLoader
import org.springframework.boot.test.context.TestComponent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.LocalDate
import javax.sql.DataSource

/** Loads the `mini` fixture feed as a READY feed; import with `@Import(MiniFeedLoader::class)`. */
@TestComponent
class MiniFeedLoader(
    private val registry: FeedRegistry,
    private val networkLoader: NetworkLoader,
    private val scheduleLoader: ScheduleLoader,
    private val dataSource: DataSource,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
) {
    fun reset() {
        jdbc.sql("truncate feed restart identity cascade").update()
    }

    /** Returns the new feed id. [sha] must differ per loaded copy. */
    fun load(sha: String = "sha"): Int {
        val id = registry.register("mini.zip", sha, FeedInfo("pub", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), "v"))
        val dir = Files.createTempDirectory("mini")
        try {
            ZipGtfsSource(Fixtures.miniZip(dir)).use { src ->
                tx.execute {
                    val c = DataSourceUtils.getConnection(dataSource)
                    networkLoader.load(id, src, c)
                    scheduleLoader.load(id, src, c)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        registry.markReady(id)
        return id
    }
}
