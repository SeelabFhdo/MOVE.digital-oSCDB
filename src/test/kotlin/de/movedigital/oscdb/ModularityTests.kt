package de.movedigital.oscdb

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {
    @Test
    fun verifiesModules() {
        ApplicationModules.of(OscdbApplication::class.java).verify()
    }
}
