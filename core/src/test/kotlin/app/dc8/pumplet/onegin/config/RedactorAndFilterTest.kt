package app.dc8.pumplet.onegin.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorAndFilterTest {
    @Test
    fun `safe JDBC URL strips credentials`() {
        assertEquals("jdbc:postgresql://h:5432/db", Redactor.safeJdbcUrl("jdbc:postgresql://u:p@h:5432/db"))
        assertEquals(
            "jdbc:postgresql://h/db?ssl=true&sslmode=require",
            Redactor.safeJdbcUrl("jdbc:postgresql://h/db?ssl=true&user=u&password=p&sslmode=require"),
        )
        assertEquals("jdbc:postgresql://h/db", Redactor.safeJdbcUrl("jdbc:postgresql://h/db?Password=p"))
    }

    @Test
    fun `detects passwords in URLs`() {
        assertTrue(Redactor.urlHasPassword("jdbc:postgresql://u:p@h/db"))
        assertTrue(Redactor.urlHasPassword("jdbc:postgresql://h/db?sslpassword=x"))
        assertFalse(Redactor.urlHasPassword("jdbc:postgresql://u@h/db?user=u"))
    }

    @Test
    fun `scrub replaces secret values but leaves very short ones`() {
        val r = Redactor(listOf("s3cr3t-value", "ab"))
        assertEquals("auth failed for **** (ab)", r.scrub("auth failed for s3cr3t-value (ab)"))
    }

    @Test
    fun `table filter globs`() {
        val f = TableFilter(listOf("app.order*", "app.customers"), listOf("app.order_audit"))
        assertTrue(f.matches("app", "orders"))
        assertTrue(f.matches("app", "order_items"))
        assertTrue(f.matches("app", "customers"))
        assertFalse(f.matches("app", "order_audit"))
        assertFalse(f.matches("app", "products"))
        assertFalse(f.matches("other", "orders"))

        val all = TableFilter(emptyList(), listOf("app.audit_?og", "*.tmp_*"))
        assertTrue(all.matches("app", "orders"))
        assertFalse(all.matches("app", "audit_log"))
        assertFalse(all.matches("x", "tmp_1"))
        assertTrue(all.matches("app", "Audit_log")) // case-sensitive
    }
}
