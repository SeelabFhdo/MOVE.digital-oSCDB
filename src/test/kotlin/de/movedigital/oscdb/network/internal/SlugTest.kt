package de.movedigital.oscdb.network.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class SlugTest {
    @Test fun `umlauts and eszett`() = assertEquals("adelenstrasse", slug("Adelenstraße"))

    @Test fun `separators collapse`() = assertEquals("dortmund-hbf-koenigswall", slug("Dortmund Hbf / Königswall"))

    @Test fun `trimmed`() = assertEquals("ue-bahn", slug("  Ü-Bahn  "))
}
