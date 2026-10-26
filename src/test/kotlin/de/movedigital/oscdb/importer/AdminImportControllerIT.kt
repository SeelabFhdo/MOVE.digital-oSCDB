package de.movedigital.oscdb.importer

import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest(properties = ["oscdb.import.admin-endpoint-enabled=true", "oscdb.import.data-dir=/nonexistent-oscdb-dir"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminImportControllerEnabledIT {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `post returns report`() {
        mvc.post("/api/v1/admin/imports").andExpect {
            status { isOk() }
            jsonPath("$.skipped") { value(0) }
            jsonPath("$.imported") { isEmpty() }
        }
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminImportControllerDisabledIT {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `endpoint absent by default`() {
        mvc.post("/api/v1/admin/imports").andExpect { status { isNotFound() } }
    }
}
