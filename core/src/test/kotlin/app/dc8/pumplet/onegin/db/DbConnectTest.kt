package app.dc8.pumplet.onegin.db

import app.dc8.pumplet.onegin.config.DbConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbConnectTest {
    @Test
    fun `a failed connection names the side, where it is, and what to check`() {
        // Nothing listens on port 1.
        val db = DbConfig("jdbc:postgresql://localhost:1/app?connectTimeout=2", "reader", null, emptyList())
        val e = assertFailsWith<DbConnectException> { Db.connect(db, Role.TARGET) }
        assertEquals(Role.TARGET, e.role)
        assertContains(e.message!!, "can't connect to the target database (jdbc:postgresql://localhost:1/app?connectTimeout=2 as reader)")
        assertContains(e.message!!, "is the target server running and reachable at that host and port?")
    }
}

class SqlErrorTest {
    @Test
    fun `message says where in one line, detail adds the SQL and the trace`() {
        val cause = java.sql.SQLException("ERROR: relation \"x\" does not exist", "42P01")
        val e = SqlError(Role.TARGET, "jdbc:postgresql://h/app as u", "table app.x", "select 1 from app.x", cause)
        assertEquals("target database (jdbc:postgresql://h/app as u), table app.x: ERROR: relation \"x\" does not exist", e.message)
        assertEquals("42P01", e.sqlState)
        val d = e.detail
        assertContains(d, "failed on the target database (jdbc:postgresql://h/app as u), table app.x\nSQL:\n  select 1 from app.x\nPostgreSQL: ERROR: relation \"x\" does not exist (SQLSTATE 42P01)\nstack trace:")
        assertEquals(false, e.detail(withStackTrace = false).contains("stack trace"))
        // Found behind wrappers; passes through on() unchanged.
        val wrapped = RuntimeException("pipe", java.util.concurrent.ExecutionException(e))
        assertEquals(e, SqlError.find(wrapped))
    }
}
