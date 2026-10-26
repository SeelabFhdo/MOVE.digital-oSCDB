package de.movedigital.oscdb.importer

import de.movedigital.oscdb.Fixtures
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.feed.FeedStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class ImportServiceIT {
    companion object {
        val dir: Path = Files.createTempDirectory("oscdb-import-it")

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("oscdb.import.data-dir") { dir.toString() }
        }
    }

    @Autowired lateinit var service: ImportService
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var events: ApplicationEvents

    @BeforeEach
    fun clean() {
        jdbc.sql("truncate feed restart identity cascade").update()
        dir.toFile().listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun count(table: String) = jdbc.sql("select count(*) from $table").query(Int::class.java).single()

    private fun status(file: String) =
        jdbc.sql("select status from feed where source_file = :f").param("f", file).query(String::class.java).single()

    @Test
    fun `imports mini feed and is idempotent`() {
        Fixtures.miniZip(dir)
        val first = service.importAll()
        assertEquals(1, first.imported.size)
        assertEquals(0, first.failed.size)
        assertEquals("READY", status("mini.zip"))
        assertEquals(
            "2026-01-01 2026-01-31",
            jdbc.sql("select start_date || ' ' || end_date from feed").query(String::class.java).single(),
        )
        assertEquals(
            "Westfalenhallen",
            jdbc.sql("select derived_headsign from trip where trip_id = 'T1'").query(String::class.java).single(),
        )
        assertEquals(1, events.stream(FeedImported::class.java).count())

        val second = service.importAll()
        assertEquals(1, second.skipped)
        assertEquals(0, second.imported.size)
        assertEquals(1, count("feed"))
        assertEquals(7, count("stop_time"))
        assertEquals(1, events.stream(FeedImported::class.java).count())
    }

    @Test
    fun `corrupt zip fails alone and others still import`() {
        Fixtures.miniZip(dir)
        Files.write(dir.resolve("broken.zip"), ByteArray(512) { (it * 31).toByte() })
        val report = service.importAll()
        assertEquals(listOf("broken.zip"), report.failed)
        assertEquals(1, report.imported.size)
        assertEquals("READY", status("mini.zip"))
    }

    @Test
    fun `zip missing stops rolls back and marks feed failed`() {
        val mini = Fixtures.miniZip(Files.createTempDirectory("src"))
        val nested = Files.createDirectories(dir.resolve("sub"))
        ZipFile(mini.toFile()).use { zf ->
            ZipOutputStream(Files.newOutputStream(nested.resolve("nostops.zip"))).use { out ->
                for (e in zf.entries().asSequence().filter { !it.name.equals("STOPS.TXT", true) }) {
                    out.putNextEntry(ZipEntry(e.name))
                    zf.getInputStream(e).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        val report = service.importAll()
        assertEquals(1, report.failed.size)
        assertEquals("FAILED", status("sub/nostops.zip"))
        val id = jdbc.sql("select id from feed").query(Int::class.java).single()
        for (t in listOf("agency", "route", "stop", "calendar", "trip", "stop_time")) {
            assertEquals(
                0,
                jdbc.sql("select count(*) from $t where feed_id = :f").param("f", id).query(Int::class.java).single(),
                t,
            )
        }
        assertEquals(0, events.stream(FeedImported::class.java).count())
    }

    @Test
    fun `failed feed is retried`() {
        val zip = Fixtures.miniZip(dir)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip))
            .joinToString("") { "%02x".format(it) }
        jdbc.sql(
            "insert into feed(source_file, file_sha256, start_date, end_date, status) values ('mini.zip', :s, '2026-01-01', '2026-01-31', 'FAILED')",
        ).param("s", sha).update()
        val report = service.importAll()
        assertEquals(1, report.imported.size)
        assertEquals(1, count("feed"))
        assertEquals("READY", status("mini.zip"))
    }
}
