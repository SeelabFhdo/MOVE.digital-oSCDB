package de.movedigital.oscdb.shared.web

import de.movedigital.oscdb.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, ProblemHandlingIT.BoomConfig::class)
class ProblemHandlingIT {
    @Autowired lateinit var mvc: MockMvc

    @TestConfiguration(proxyBeanMethods = false)
    class BoomConfig {
        @Bean fun boomController() = BoomController()
    }

    @RestController
    class BoomController {
        @GetMapping("/api/v1/test-boom")
        fun boom(): String = throw IllegalStateException("secret internal detail")
    }

    @Test
    fun `unknown path is 404 problem json`() {
        mvc.get("/api/v1/nope").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.status") { value(404) }
        }
    }

    @Test
    fun `not acceptable is 406 problem json`() {
        mvc.get("/api/v1/feeds") { accept = MediaType.TEXT_PLAIN }.andExpect {
            status { isNotAcceptable() }
            content { contentTypeCompatibleWith("application/problem+json") }
        }
    }

    @Test
    fun `unexpected exception is 500 problem json without leaking the message`() {
        mvc.get("/api/v1/test-boom").andExpect {
            status { isInternalServerError() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.status") { value(500) }
            content { string(not(containsString("secret"))) }
            content { string(not(containsString("IllegalStateException"))) }
        }
    }
}
