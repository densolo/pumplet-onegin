package app.dc8.pumplet.onegin.copy

import app.dc8.pumplet.onegin.config.ChunkingConfig
import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.state.ChunkPlan
import java.math.BigInteger
import java.sql.Connection

/**
 * Splits large tables into primary-key ranges. Runs on the snapshot coordinator
 * connection, so the bounds come from the same snapshot the readers use.
 */
class ChunkPlanner(private val c: Connection, private val chunking: ChunkingConfig) {
    /** One chunk for small tables and tables without a primary key; otherwise about estRows / rowsPerChunk. */
    fun plan(table: String, primaryKey: List<String>, keyTypes: List<String>, estRows: Long): ChunkPlan {
        val one = ChunkPlan(table, primaryKey, emptyList())
        if (primaryKey.isEmpty() || estRows < chunking.minRowsToSplit) return one
        val n = ((estRows + chunking.rowsPerChunk - 1) / chunking.rowsPerChunk).toInt()
        if (n < 2) return one
        val sample = sample(table, primaryKey, estRows, n)
        val bounds = if (primaryKey.size == 1 && keyTypes.single() in INTEGER_TYPES) {
            integerBounds(table, primaryKey.single(), n, sample)
        } else {
            quantiles(sample, n)
        }
        val plan = ChunkPlan(table, primaryKey, bounds)
        verifyIncreasing(plan, keyTypes)
        return plan
    }

    /**
     * Chunks are correct only if the bounds are strictly increasing in the key's own
     * order: otherwise ranges overlap (duplicates) or leave gaps (lost rows). The
     * database checks it with the same comparison the chunk predicates use.
     */
    private fun verifyIncreasing(plan: ChunkPlan, keyTypes: List<String>) {
        if (plan.bounds.size < 2) return
        val checks = plan.bounds.zipWithNext { a, b -> "${row(a, keyTypes)} < ${row(b, keyTypes)}" }
        val ok = c.createStatement().use { st ->
            st.executeQuery("select ${checks.joinToString(" and ")}").use { it.next(); it.getBoolean(1) }
        }
        check(ok) { "chunk bounds for ${plan.table} are not in key order; refusing to copy with them" }
    }

    /**
     * Equal ranges between min and max, unless the sample shows the keys bunched up
     * (some range would get over twice its share), then sample quantiles instead.
     */
    private fun integerBounds(table: String, key: String, n: Int, sample: List<List<String>>): List<List<String>> {
        val (min, max) = c.createStatement().use { st ->
            st.executeQuery("select min(${Sql.ident(key)})::text, max(${Sql.ident(key)})::text from ${Sql.table(table)}").use { rs ->
                rs.next()
                (rs.getString(1) ?: return emptyList()).toBigInteger() to rs.getString(2).toBigInteger()
            }
        }
        val span = max - min + BigInteger.ONE
        if (span < BigInteger.valueOf(n.toLong())) return emptyList()
        val equal = (1 until n).map { i -> min + span * BigInteger.valueOf(i.toLong()) / BigInteger.valueOf(n.toLong()) - BigInteger.ONE }
        if (sample.size >= n * 10) {
            val keys = sample.map { it.single().toBigInteger() }
            val counts = IntArray(n)
            for (k in keys) counts[equal.indexOfFirst { k <= it }.let { if (it < 0) n - 1 else it }]++
            if (counts.max() > 2.0 * keys.size / n) return quantiles(sample, n)
        }
        return equal.map { listOf(it.toString()) }
    }

    /**
     * An ordered sample of primary keys as text. SYSTEM sampling picks whole pages, so
     * it asks for enough rows to span many pages (a handful of pages skews the bounds).
     */
    private fun sample(table: String, key: List<String>, estRows: Long, n: Int): List<List<String>> {
        val want = (n * 1_000L).coerceIn(20_000, 1_000_000)
        val percent = (want * 100.0 / estRows.coerceAtLeast(1)).coerceIn(0.0001, 100.0)
        val cols = key.joinToString(", ") { Sql.ident(it) }
        // Aliased: `order by "id"` must sort by the key, not by a text column also named "id".
        val select = key.indices.joinToString(", ") { "${Sql.ident(key[it])}::text as pumplet_k$it" }
        return c.createStatement().use { st ->
            st.executeQuery("select $select from ${Sql.table(table)} tablesample system ($percent) order by $cols").use { rs ->
                buildList { while (rs.next()) add(key.indices.map { rs.getString(it + 1) }) }
            }
        }
    }

    private fun quantiles(sample: List<List<String>>, n: Int): List<List<String>> {
        if (sample.size < n) return emptyList()
        return (1 until n).map { sample[it * sample.size / n] }.distinct()
    }

    companion object {
        private val INTEGER_TYPES = setOf("smallint", "integer", "bigint")

        /**
         * The WHERE clause for chunk [index] of [plan]: `key > lower and key <= upper`,
         * as a row comparison for composite keys. Bounds are inlined as literals
         * because COPY (SELECT ...) takes no parameters; null for a single-chunk table.
         */
        fun predicate(plan: ChunkPlan, keyTypes: List<String>, index: Int): String? {
            if (plan.bounds.isEmpty()) return null
            val cols = plan.primaryKey.map { Sql.ident(it) }
            val lhs = if (cols.size == 1) cols.single() else cols.joinToString(", ", "(", ")")
            fun value(bound: List<String>): String {
                val lits = bound.zip(keyTypes).map { (v, t) -> "cast(${literal(v)} as $t)" }
                return if (lits.size == 1) lits.single() else lits.joinToString(", ", "row(", ")")
            }
            val lower = plan.bounds.getOrNull(index - 1)?.let { "$lhs > ${value(it)}" }
            val upper = plan.bounds.getOrNull(index)?.let { "$lhs <= ${value(it)}" }
            return listOfNotNull(lower, upper).joinToString(" and ")
        }

        private fun row(bound: List<String>, keyTypes: List<String>) =
            bound.zip(keyTypes).joinToString(", ", "row(", ")") { (v, t) -> "cast(${literal(v)} as $t)" }

        /** A standard-conforming string literal (the readers set standard_conforming_strings = on). */
        fun literal(v: String) = "'" + v.replace("'", "''") + "'"
    }
}
