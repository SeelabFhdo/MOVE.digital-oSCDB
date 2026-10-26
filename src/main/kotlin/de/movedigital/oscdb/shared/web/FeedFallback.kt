package de.movedigital.oscdb.shared.web

import org.springframework.http.ResponseEntity

const val FEED_FALLBACK_HEADER = "OSCDB-Feed-Fallback"

/** 200 response; adds `OSCDB-Feed-Fallback: true` only when [fallback]. */
fun <T : Any> okWithFallback(body: T, fallback: Boolean): ResponseEntity<T> {
    val builder = ResponseEntity.ok()
    if (fallback) builder.header(FEED_FALLBACK_HEADER, "true")
    return builder.body(body)
}
