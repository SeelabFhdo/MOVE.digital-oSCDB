package de.movedigital.oscdb.inquiry.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlGuardTest {
    @Test
    fun `trailing semicolons are dropped`() {
        assertEquals("select 1", SqlGuard.check("  select 1 ;; \n"))
    }

    @Test
    fun `with queries are allowed`() {
        assertEquals("WITH x as (select 1) select * from x", SqlGuard.check("WITH x as (select 1) select * from x"))
    }

    @Test
    fun `non-select statements are rejected`() {
        assertFailsWith<InvalidSqlException> { SqlGuard.check("delete from v_stop") }
    }

    @Test
    fun `multiple statements are rejected`() {
        assertFailsWith<InvalidSqlException> { SqlGuard.check("select 1; drop table feed") }
    }

    @Test
    fun `blank sql is rejected`() {
        assertFailsWith<InvalidSqlException> { SqlGuard.check("  ") }
    }

    @Test
    fun `selectivity keyword prefix is not enough`() {
        assertFailsWith<InvalidSqlException> { SqlGuard.check("selectx from v_stop") }
    }
}
