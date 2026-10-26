package de.movedigital.oscdb.importer.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * `data-dir` is bound as a String on purpose: Spring's String-to-Path conversion goes through the resource
 * loader, which under an embedded servlet container resolves `../Daten` as a servlet-context resource and fails.
 */
@ConfigurationProperties("oscdb.import")
class ImportProperties(
    dataDir: String,
    val onStartup: Boolean,
    val adminEndpointEnabled: Boolean,
) {
    /** File system path, relative paths resolved against the working directory. */
    val dataDir: Path = Path.of(dataDir)
}
