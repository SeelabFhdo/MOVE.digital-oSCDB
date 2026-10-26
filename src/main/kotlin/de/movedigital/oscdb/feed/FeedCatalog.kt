package de.movedigital.oscdb.feed

import de.movedigital.oscdb.shared.web.PageRequest

interface FeedCatalog {
    /** All feeds regardless of status, ordered by id. */
    fun findAll(page: PageRequest): List<Feed>
    fun count(): Long
}
