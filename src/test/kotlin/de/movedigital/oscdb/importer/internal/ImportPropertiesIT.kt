package de.movedigital.oscdb.importer.internal

import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.nio.file.Path
import kotlin.test.assertEquals

/** With a real servlet container, a relative data dir must bind as a file system path. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ImportPropertiesIT {
    @Autowired lateinit var props: ImportProperties

    @Test
    fun `default relative data dir binds under embedded server`() {
        assertEquals(Path.of("../Daten"), props.dataDir)
    }
}
