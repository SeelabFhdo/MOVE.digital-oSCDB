package de.movedigital.oscdb.network.internal

private val nonSlug = Regex("[^a-z0-9]+")

/** URL-safe lowercase slug; German umlauts transliterated. */
fun slug(name: String): String =
    name.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(nonSlug, "-")
        .trim('-')
