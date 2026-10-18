package app.dc8.pumplet.onegin.schema

/**
 * Prints a diff's comparison rows as a plain-text table, for the console and the log:
 *
 * ```
 * overall: BLOCKED: 1 blocker(s), 2 compatible difference(s)
 * Object             Kind    Source                 Target                 Status
 * shop.emp.title     column  character varying(20)  character varying(60)  compatible
 * shop.mismatch.v    column  text                   integer                BLOCKER
 * ```
 */
object DiffTable {
    /** @param all include identical objects; @param cellWidth longer cells are cut with "…". */
    fun format(report: DiffReport, all: Boolean = false, cellWidth: Int = 40): String {
        val rows = if (all) report.comparisons else report.differences
        val header = listOf("Object", "Kind", "Source", "Target", "Status")
        val cells = rows.map { c ->
            listOf(c.objectName, c.kind, c.source ?: "-", c.target ?: "-", status(c.verdict)).map { cut(it, cellWidth) }
        }
        val widths = header.indices.map { i -> (cells.map { it[i].length } + header[i].length).max() }
        fun line(values: List<String>) = values.mapIndexed { i, v -> if (i == values.lastIndex) v else v.padEnd(widths[i]) }.joinToString("  ")
        return buildString {
            appendLine("overall: ${report.overall}")
            if (rows.isEmpty()) {
                append(if (all) "(nothing compared)" else "(no differences)")
                return@buildString
            }
            appendLine(line(header))
            appendLine(line(widths.map { "-".repeat(it) }))
            cells.forEachIndexed { i, row ->
                append(line(row))
                if (i < cells.lastIndex) appendLine()
            }
        }
    }

    fun status(v: Verdict) = when (v) {
        Verdict.SAME -> "same"
        Verdict.COMPATIBLE -> "compatible"
        Verdict.BLOCKER -> "BLOCKER"
    }

    private fun cut(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
