package de.movedigital.oscdb.importer

import de.movedigital.oscdb.shared.gtfs.GtfsSource
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/** [GtfsSource] over a zip file; entry names are matched case-insensitively. */
class ZipGtfsSource(zip: Path) : GtfsSource, AutoCloseable {
    private val file = ZipFile(zip.toFile())
    private val entries = file.entries().asSequence()
        .filter { !it.isDirectory }
        .associateBy { it.name.substringAfterLast('/').lowercase() }

    override fun open(fileName: String): InputStream? = entries[fileName.lowercase()]?.let { file.getInputStream(it) }

    override fun close() = file.close()
}
