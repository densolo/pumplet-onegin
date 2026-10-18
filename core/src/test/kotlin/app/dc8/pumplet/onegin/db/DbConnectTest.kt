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
