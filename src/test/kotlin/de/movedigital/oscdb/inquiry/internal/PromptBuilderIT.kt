package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.MiniFeedLoader
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.inquiry.OutputFormat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertContains
import kotlin.test.assertFalse

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class, MiniFeedLoader::class)
class PromptBuilderIT {
    @Autowired lateinit var mini: MiniFeedLoader
    @Autowired lateinit var prompts: PromptBuilder

    private var feedId = 0

    @BeforeEach
    fun load() {
        mini.reset()
        feedId = mini.load()
    }

    @Test
    fun `prompt lists views, columns, helper, today and feeds`() {
        val p = prompts.systemPrompt(OutputFormat.CSV)
        assertContains(p, "v_departure")
        assertContains(p, "departure_at")
        assertContains(p, "Real departure date and time")
        assertContains(p, "distance_m(")
        assertContains(p, "2026-10-26")
        assertContains(p, "feed $feedId: 2026-01-01 to 2026-01-31")
        assertContains(p, "fallback")
        assertFalse("{{" in p, "unreplaced placeholder")
    }

    @Test
    fun `geojson prompt asks for coordinates`() {
        assertContains(prompts.systemPrompt(OutputFormat.GEOJSON), "lat and lon")
        assertFalse("lat and lon" in prompts.systemPrompt(OutputFormat.CSV))
    }
}
