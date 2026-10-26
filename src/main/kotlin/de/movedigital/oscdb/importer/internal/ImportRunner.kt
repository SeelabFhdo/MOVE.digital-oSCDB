package de.movedigital.oscdb.importer.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty("oscdb.import.on-startup", havingValue = "true")
class ImportRunner(private val service: ImportService) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(ImportRunner::class.java)

    override fun run(args: ApplicationArguments) {
        try {
            service.importAll()
        } catch (e: Exception) {
            log.error("Startup import failed", e)
        }
    }
}
