package de.movedigital.oscdb.importer

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/imports")
@ConditionalOnProperty("oscdb.import.admin-endpoint-enabled", havingValue = "true")
internal class AdminImportController(private val service: ImportService) {
    @PostMapping
    fun run(): ImportReport = service.importAll()
}
