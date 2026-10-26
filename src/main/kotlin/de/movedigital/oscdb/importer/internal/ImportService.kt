package de.movedigital.oscdb.importer.internal

import de.movedigital.oscdb.importer.FeedImported
import de.movedigital.oscdb.feed.FeedInfo
import de.movedigital.oscdb.feed.FeedRegistry
import de.movedigital.oscdb.feed.FeedStatus
import de.movedigital.oscdb.network.NetworkLoader
import de.movedigital.oscdb.schedule.ScheduleLoader
import de.movedigital.oscdb.shared.gtfs.GtfsSource
import de.movedigital.oscdb.shared.gtfs.GtfsTable
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.sql.DataSource
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

data class ImportReport(val imported: List<Int>, val skipped: Int, val failed: List<String>)

@Service
class ImportService(
    private val props: ImportProperties,
    private val registry: FeedRegistry,
    private val networkLoader: NetworkLoader,
    private val scheduleLoader: ScheduleLoader,
    private val dataSource: DataSource,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(ImportService::class.java)

    @Synchronized
    fun importAll(): ImportReport {
        val dir = props.dataDir
        if (!dir.isDirectory()) {
            log.warn("GTFS data directory {} does not exist; nothing to import", dir.toAbsolutePath())
            return ImportReport(emptyList(), 0, emptyList())
        }
        val zips = Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && it.extension.equals("zip", true) }.sorted().toList()
        }
        val imported = mutableListOf<Int>()
        val failed = mutableListOf<String>()
        var skipped = 0
        for (zip in zips) {
            val name = dir.relativize(zip).toString()
            try {
                val id = importOne(zip, name)
                if (id == null) skipped++ else imported += id
            } catch (e: Exception) {
                log.error("Import of {} failed", name, e)
                failed += name
            }
        }
        log.info("Import finished: {} imported, {} skipped, {} failed", imported.size, skipped, failed.size)
        return ImportReport(imported, skipped, failed)
    }

    /** Returns the new feed id, or `null` if the zip was already imported. */
    private fun importOne(zip: Path, name: String): Int? {
        val started = System.nanoTime()
        val sha = sha256(zip)
        val status = registry.statusOf(sha)
        if (status == FeedStatus.READY) {
            log.info("Skipping {} (already imported)", name)
            return null
        }
        ZipGtfsSource(zip).use { source ->
            val info = readFeedInfo(source)
            if (status != null) registry.deleteBySha(sha)
            val id = registry.register(name, sha, info)
            try {
                tx.executeWithoutResult {
                    val connection = DataSourceUtils.getConnection(dataSource)
                    networkLoader.load(id, source, connection)
                    scheduleLoader.load(id, source, connection)
                }
                registry.markReady(id)
            } catch (e: Exception) {
                runCatching { registry.markFailed(id) }.onFailure { log.error("Could not mark feed {} FAILED", id, it) }
                throw e
            }
            events.publishEvent(FeedImported(id))
            log.info(
                "Imported {} as feed {} in {} ms (stops={}, trips={}, stop_times={})", name, id,
                (System.nanoTime() - started) / 1_000_000,
                count("stop", id), count("trip", id), count("stop_time", id),
            )
            return id
        }
    }

    private fun count(table: String, feedId: Int): Int =
        jdbc.sql("select count(*) from $table where feed_id = :f").param("f", feedId).query(Int::class.java).single()

    private fun readFeedInfo(source: GtfsSource): FeedInfo {
        val input = source.open("feed_info.txt") ?: error("feed_info.txt missing")
        var info: FeedInfo? = null
        input.use {
            GtfsTable.read(it) { row ->
                if (info == null) {
                    fun date(col: String) = LocalDate.parse(
                        row[col] ?: error("feed_info.txt: $col missing"), DateTimeFormatter.BASIC_ISO_DATE,
                    )
                    info = FeedInfo(row["feed_publisher_name"], date("feed_start_date"), date("feed_end_date"), row["feed_version"])
                }
            }
        }
        return info ?: error("feed_info.txt is empty")
    }

    private fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
