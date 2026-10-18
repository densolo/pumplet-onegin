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

    private fun report(vararg rows: Comparison, errors: Int = 0) = DiffReport(
        java.time.Instant.EPOCH, "h", "src", "dst", emptyList(), emptyList(),
        List(errors) { Finding(Severity.ERROR, "s.t", "c", "x") }, rows.toList(),
    )

    @Test
    fun `text table shows differences only unless asked, aligned, long cells cut`() {
        val r = report(
            Comparison("s.t", "table", "s.t", "table, ~10 rows", "table", Verdict.SAME),
            Comparison("s.t", "column", "title", "character varying(20)", "character varying(60)", Verdict.COMPATIBLE),
            Comparison("s.t", "column", "v", "text", "integer", Verdict.BLOCKER),
            Comparison("s.t", "index", "i", "CREATE INDEX ON s.t USING btree (a, b, c, d, e, f, g, h)", null, Verdict.COMPATIBLE),
            errors = 1,
        )
        val text = DiffTable.format(r, cellWidth = 30)
        val lines = text.lines()
        assertEquals("overall: BLOCKED: 1 blocker(s), 2 compatible difference(s)", lines[0])
        assertEquals(listOf("Object", "Kind", "Source", "Target", "Status"), lines[1].split(Regex(" {2,}")))
        assertEquals(1 + 2 + 3, lines.size, "overall, header, rule and the 3 differences (identical table left out)")
        assertTrue(lines.any { it.startsWith("s.t.v ") && it.endsWith("BLOCKER") })
        assertTrue(lines.any { it.contains("CREATE INDEX ON s.t USING btr…") && it.contains(" - ") })
        // Columns line up: every "Kind" cell starts where the header's does.
        val kindAt = lines[1].indexOf("Kind")
        assertTrue(lines.drop(3).all { it[kindAt - 1] == ' ' && it[kindAt] != ' ' })

        assertEquals(4 + 2 + 1, DiffTable.format(r, all = true).lines().size, "all rows with all = true")
        assertEquals("overall: IDENTICAL\n(no differences)", DiffTable.format(report(Comparison("s.t", "table", "s.t", "t", "t", Verdict.SAME))))
    }
}
