package de.movedigital.oscdb.network

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
internal class NetworkRepository(private val jdbc: JdbcClient) : NetworkLookup {
    override fun stopNames(feedId: Int): Map<String, String> {
        val result = HashMap<String, String>()
        jdbc.sql("select stop_id, name from stop where feed_id = :feed and name is not null")
            .param("feed", feedId)
            .query { rs -> result[rs.getString(1)] = rs.getString(2) }
        return result
    }
}
