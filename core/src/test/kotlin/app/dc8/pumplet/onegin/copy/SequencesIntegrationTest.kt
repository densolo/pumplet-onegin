package app.dc8.pumplet.onegin.copy

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Several schemas with different owners in one target database, as when copying
 * ecr_owner, cita_owner and assessio_owner: the user copying one schema has no USAGE
 * on the others. Skipped when the docker test target isn't up.
 */
class SequencesIntegrationTest {
    private val n = System.nanoTime()
    private val mine = "seq_mine_$n"
    private val other = "seq_other_$n"
    private val user = "seq_user_$n"

    private fun admin(sql: String) =
        DriverManager.getConnection("jdbc:postgresql://localhost:55433/app", "postgres", "dst-pw").use { c -> c.createStatement().use { it.execute(sql) } }

    @BeforeEach
    fun setUp() {
        assumeTrue(runCatching { Socket().use { it.connect(InetSocketAddress("localhost", 55433), 500) } }.isSuccess, "docker test target not running")
        admin(
            """
            create role $user login password 'pw';
            create schema $mine authorization $user;
            create sequence $mine.ids_seq;
            alter sequence $mine.ids_seq owner to $user;
            create schema $other;                       -- owned by postgres, no USAGE for $user
            create sequence $other.foreign_seq;
            """.trimIndent()
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { admin("drop schema if exists $mine cascade; drop schema if exists $other cascade; drop role if exists $user") }
    }

    @Test
    fun `raising a sequence needs no access to other schemas`() {
        DriverManager.getConnection("jdbc:postgresql://localhost:55433/app", user, "pw").use { c ->
            val qualified = "\"$mine\".\"ids_seq\""
            assertEquals(true to null, Sequences.raise(c, qualified, 5000), "unused sequence: raised")
            assertEquals(false to 5000L, Sequences.raise(c, qualified, 100), "already ahead: kept")
            assertEquals(true to 5000L, Sequences.raise(c, qualified, 7000))
            val next = c.createStatement().use { st -> st.executeQuery("select nextval('$qualified')").use { it.next(); it.getLong(1) } }
            assertEquals(7001, next)
        }
    }
}
