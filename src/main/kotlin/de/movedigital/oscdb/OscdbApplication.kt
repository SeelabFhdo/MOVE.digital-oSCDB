package de.movedigital.oscdb

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.ZoneId

@SpringBootApplication
class OscdbApplication {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Europe/Berlin"))
}

fun main(args: Array<String>) {
    runApplication<OscdbApplication>(*args)
}
