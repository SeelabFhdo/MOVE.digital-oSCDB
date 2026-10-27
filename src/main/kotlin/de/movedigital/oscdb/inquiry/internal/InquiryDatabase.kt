package de.movedigital.oscdb.inquiry.internal

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

/**
 * Connection pool logged in as the restricted role `oscdb_inquiry` (created by V5__inquiry.sql).
 * Deliberately not a `DataSource` bean: a second one would switch off Boot's auto-configured main DataSource.
 */
@Component
class InquiryDatabase(
    private val props: InquiryProperties,
    private val mainDataSource: DataSource,
    private val jdbc: JdbcClient,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(InquiryDatabase::class.java)

    @Volatile private var pool: HikariDataSource? = null

    fun <T> withConnection(block: (Connection) -> T): T = pool().connection.use(block)

    private fun pool(): HikariDataSource = pool ?: synchronized(this) {
        pool ?: create().also { pool = it }
    }

    private fun create(): HikariDataSource {
        setPassword()
        val ds = props.datasource
        val config = HikariConfig().apply {
            poolName = "inquiry"
            jdbcUrl = ds.url ?: mainDataSource.unwrap(HikariDataSource::class.java).jdbcUrl
            username = ds.username
            password = ds.password
            maximumPoolSize = 2
            minimumIdle = 0
            isReadOnly = true
        }
        return HikariDataSource(config)
    }

    /** Best effort: in a managed database the password may be set by an administrator instead. */
    private fun setPassword() {
        runCatching {
            val literal = jdbc.sql("select quote_literal(:p)").param("p", props.datasource.password).query(String::class.java).single()
            jdbc.sql("alter role oscdb_inquiry password $literal").update()
        }.onFailure { log.warn("Could not set the password of role oscdb_inquiry: {}", it.message) }
    }

    override fun destroy() {
        pool?.close()
    }
}
