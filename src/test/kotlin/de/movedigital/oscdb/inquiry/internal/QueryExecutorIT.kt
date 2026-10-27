package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.MiniFeedLoader
import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.SQLException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(properties = ["oscdb.inquiry.max-rows=3", "oscdb.inquiry.statement-timeout=1s"])
@Import(TestcontainersConfiguration::class, MiniFeedLoader::class)
class QueryExecutorIT {
    @Autowired lateinit var mini: MiniFeedLoader
    @Autowired lateinit var executor: QueryExecutor
    @Autowired lateinit var database: InquiryDatabase

    @BeforeEach
    fun load() {
        mini.reset()
        mini.load()
    }

    @Test
    fun `selects from views without schema prefix and detects truncation`() {
        val table = executor.run("select stop_id from v_stop order by stop_id")
        assertEquals(listOf("stop_id"), table.columns)
        assertEquals(listOf(listOf<Any?>("101"), listOf("102"), listOf("103")), table.rows)
        assertEquals(true, table.truncated)
    }

    @Test
    fun `small results are not truncated`() {
        val table = executor.run("select line from v_route order by line")
        assertEquals(2, table.rows.size)
        assertEquals(false, table.truncated)
    }

    @Test
    fun `base tables are not readable`() {
        val e = assertFailsWith<SQLException> { executor.run("select * from public.stop_time") }
        assertContains(e.message!!, "permission denied")
    }

    @Test
    fun `the role cannot create tables`() {
        assertFailsWith<SQLException> {
            database.withConnection { c -> c.createStatement().use { it.execute("create table x (i int)") } }
        }
    }

    @Test
    fun `the role cannot update views`() {
        assertFailsWith<SQLException> {
            database.withConnection { c -> c.createStatement().use { it.execute("update inquiry_api.v_stop set name = 'x'") } }
        }
    }

    @Test
    fun `row locks are rejected in the read-only transaction`() {
        assertFailsWith<SQLException> { executor.run("select * from v_stop for update") }
    }

    @Test
    fun `helper function is callable`() {
        assertEquals(listOf(listOf<Any?>(0.0)), executor.run("select distance_m(0, 0, 0, 0) as d").rows)
    }

    @Test
    fun `timeout cannot be disabled by a previous query`() {
        executor.run("select set_config('statement_timeout', '0', false)")
        val e = assertFailsWith<SQLException> { executor.run("select pg_sleep(3)") }
        assertContains(e.message!!, "statement timeout")
    }

    @Test
    fun `temp files and sort memory are capped for the role`() {
        assertEquals(listOf(listOf<Any?>("1GB", "64MB")), executor.run("select current_setting('temp_file_limit') as t, current_setting('work_mem') as w").rows)
    }

    @Test
    fun `timestamptz is rendered in Berlin time`() {
        val table = executor.run(
            "select departure_at, service_date from v_departure where trip_id = 'T3' and stop_id = '101' and service_date = date '2026-01-08'",
        )
        assertEquals(listOf(listOf<Any?>("2026-01-09T00:30+01:00", "2026-01-08")), table.rows)
    }

    @Test
    fun `duplicate column names get a suffix`() {
        assertEquals(listOf("name", "name_2"), executor.run("select s.name, t.name from v_stop s, v_station t limit 1").columns)
    }

    @Test
    fun `numbers and booleans keep their type`() {
        assertEquals(listOf(listOf<Any?>(2, 1.5, true, null)), executor.run("select 2 as i, 1.5::float8 as f, true as b, null::text as n").rows)
    }
}
