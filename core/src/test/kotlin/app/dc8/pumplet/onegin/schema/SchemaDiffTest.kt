package app.dc8.pumplet.onegin.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaDiffTest {
    @Test
    fun `safe widenings`() {
        val safe = listOf(
            "character varying(10)" to "character varying(20)",
            "character varying(10)" to "text",
            "character varying(10)" to "character varying",
            "character varying" to "text",
            "smallint" to "integer",
            "integer" to "bigint",
            "smallint" to "bigint",
            "integer" to "numeric",
            "real" to "double precision",
            "numeric(10,2)" to "numeric(12,2)",
            "numeric(10,2)" to "numeric(12,4)",
            "numeric(10,2)" to "numeric",
        )
        for ((from, to) in safe) assertTrue(SchemaDiff.isWidening(from, to), "$from -> $to")
    }

    @Test
    fun `narrowing or changing types is not safe`() {
        val unsafe = listOf(
            "character varying(20)" to "character varying(10)",
            "text" to "character varying(100)",
            "bigint" to "integer",
            "numeric(12,2)" to "numeric(10,2)",
            "numeric(10,4)" to "numeric(10,2)",
            "numeric(10,2)" to "numeric(10,4)", // fewer integer digits
            "numeric" to "numeric(10,2)",
            "double precision" to "real",
            "character(3)" to "text",
            "timestamp without time zone" to "timestamp with time zone",
            "text" to "integer",
            "integer" to "text",
        )
        for ((from, to) in unsafe) assertFalse(SchemaDiff.isWidening(from, to), "$from -> $to")
    }

    @Test
    fun `index names are removed so definitions compare across databases`() {
        assertEquals(
            "CREATE INDEX ON app.orders USING btree (customer_id)",
            CatalogReader.withoutIndexName("CREATE INDEX orders_customer_idx ON app.orders USING btree (customer_id)"),
        )
        assertEquals(
            "CREATE UNIQUE INDEX ON app.\"Odd\" USING btree (\"Id\")",
            CatalogReader.withoutIndexName("CREATE UNIQUE INDEX \"My \"\"Idx\" ON app.\"Odd\" USING btree (\"Id\")"),
        )
    }
}
