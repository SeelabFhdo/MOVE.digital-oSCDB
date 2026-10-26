package de.movedigital.oscdb

import de.movedigital.oscdb.shared.gtfs.GtfsSource
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object Fixtures {
    private val files = listOf(
        "FEED_INFO.TXT", "AGENCY.TXT", "ROUTES.TXT", "STOPS.TXT",
        "CALENDAR.TXT", "CALENDAR_DATES.TXT", "TRIPS.TXT", "STOP_TIMES.TXT",
    )

    /** Zips the `mini` fixture feed into `dir/mini.zip`. */
    fun miniZip(dir: Path): Path {
        val zip = dir.resolve("mini.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            for (name in files) {
                out.putNextEntry(ZipEntry(name))
                val bytes = Fixtures::class.java.getResourceAsStream("/gtfs/mini/$name")!!.use { it.readBytes() }
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }
}

/** Test-only [GtfsSource] over a zip file with case-insensitive entry lookup. */
class ZipTestSource(path: Path) : GtfsSource, AutoCloseable {
    private val zip = ZipFile(path.toFile())
    private val entries = zip.entries().asSequence().associateBy { it.name.lowercase() }

    override fun open(fileName: String): InputStream? = entries[fileName.lowercase()]?.let { zip.getInputStream(it) }

    override fun close() = zip.close()
}
