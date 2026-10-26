package de.movedigital.oscdb.importer

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("oscdb.import")
data class ImportProperties(
    val dataDir: Path,
    val onStartup: Boolean,
    val adminEndpointEnabled: Boolean,
)
