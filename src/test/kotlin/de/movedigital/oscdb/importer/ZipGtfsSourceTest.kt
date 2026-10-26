package de.movedigital.oscdb.importer

import de.movedigital.oscdb.Fixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZipGtfsSourceTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `matches entry names case-insensitively`() {
        ZipGtfsSource(Fixtures.miniZip(tmp)).use { src ->
            assertNotNull(src.open("stop_times.txt")).close()
            assertNull(src.open("shapes.txt"))
        }
    }
}
