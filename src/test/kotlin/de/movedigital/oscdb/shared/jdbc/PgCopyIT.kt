package de.movedigital.oscdb.shared.jdbc

import de.movedigital.oscdb.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PgCopyIT {
    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun copiesRowsWithQuotingAndNulls() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("create temp table t(a text, b int)") }
            val n = PgCopy(conn).into("t", listOf("a", "b")) { w ->
                w.row("x,\"y", 1)
                w.row(null, 2)
            }
            assertEquals(2L, n)
            val got = mutableListOf<Pair<String?, Int>>()
            conn.createStatement().use { st ->
                st.executeQuery("select a, b from t order by b").use { rs ->
                    while (rs.next()) got += rs.getString(1) to rs.getInt(2)
                }
            }
            assertEquals(listOf<Pair<String?, Int>>("x,\"y" to 1, null to 2), got)
        }
    }

    @Test
    fun failingBlockCancelsCopyAndLeavesConnectionUsable() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("create temp table t2(a text, b int)") }
            assertThrows<IllegalStateException> {
                PgCopy(conn).into("t2", listOf("a", "b")) { w ->
                    w.row("x", 1)
                    throw IllegalStateException("boom")
                }
            }
            conn.createStatement().use { st ->
                st.executeQuery("select count(*) from t2").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun rejectsUnsafeIdentifiers() {
        dataSource.connection.use { conn ->
            assertThrows<IllegalArgumentException> { PgCopy(conn).into("t; drop table x", listOf("a")) {} }
            assertThrows<IllegalArgumentException> { PgCopy(conn).into("t", listOf("a) FROM")) {} }
        }
    }
}
