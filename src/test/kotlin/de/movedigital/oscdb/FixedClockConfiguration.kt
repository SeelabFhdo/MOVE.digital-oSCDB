package de.movedigital.oscdb

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/** Overrides the Clock bean: today is 2026-10-26 (Europe/Berlin). */
@TestConfiguration(proxyBeanMethods = false)
class FixedClockConfiguration {
    @Bean
    @Primary
    fun fixedClock(): Clock {
        val zone = ZoneId.of("Europe/Berlin")
        return Clock.fixed(ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, zone).toInstant(), zone)
    }
}
