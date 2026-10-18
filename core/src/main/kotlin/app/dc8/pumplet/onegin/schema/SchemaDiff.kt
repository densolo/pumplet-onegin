package app.dc8.pumplet.onegin.schema

import app.dc8.pumplet.onegin.config.ChunkingConfig
import app.dc8.pumplet.onegin.config.TableFilter
import java.time.Instant

/** ERROR blocks the job (`run` stops before `disable`); WARNING and INFO don't. */
enum class Severity { ERROR, WARNING, INFO }

data class Finding(
    val severity: Severity,
    /** `schema.table`, or a schema name for schema-level findings. */
    val table: String,
    /** Column, constraint or index the finding is about; null if it's about the table itself. */
    val subject: String?,
    val message: String,
)

enum class TableAction { COPY, SKIP, BLOCKED }

data class ColumnPlan(
    val name: String,
    val sourceType: String,
    val targetType: String,
    /** Target column is GENERATED ALWAYS AS IDENTITY; source values are written as they are. */
    val identityAlways: Boolean,
    /** Types differ: the column must go through text format, not binary. */
    val asText: Boolean,
    /** Large-object reference (`oid`): the object is copied too. */
    val largeObject: Boolean,
)

/** What `copy` will do with one source table. */
data class TablePlan(
    val table: String,
    val action: TableAction,
    /** Why the table is skipped or blocked. */
    val reason: String?,
    /** Columns copied, in source order. */
    val columns: List<ColumnPlan>,
    val primaryKey: List<String>,
    /** Source types of [primaryKey], for casting chunk bounds. */
    val primaryKeyTypes: List<String>,
    val estRows: Long,
    /** Number of chunks, or null if copied in one piece. */
    val chunks: Long?,
) {
    /** Binary COPY works only when every column has the same type on both sides. */
    val binary get() = columns.none { it.asText }
    val largeObjects get() = columns.any { it.largeObject }
}

/** SAME: identical (maybe with a note); COMPATIBLE: differs, but the copy handles it; BLOCKER: must be fixed first. */
enum class Verdict { SAME, COMPATIBLE, BLOCKER }

/**
 * One compared object: a schema, table, column, constraint or index, as the source and
 * the target have it (null where it doesn't exist). [note] carries the findings about it.
 */
data class Comparison(
    val table: String,
    val kind: String,
    val name: String,
    val source: String?,
    val target: String?,
    val verdict: Verdict,
    val note: String? = null,
) {
    /** `schema.table` for tables and schemas, `schema.table.object` for the rest. */
    val objectName get() = if (kind == "table" || kind == "schema") name else "$table.$name"
}

data class DiffReport(
    val createdAt: Instant,
    val configHash: String,
    val source: String,
    val target: String,
    val tables: List<TablePlan>,
    /** Target tables in the copied schemas with no source counterpart; left alone. */
    val targetOnly: List<String>,
    val findings: List<Finding>,
    /** Every compared object, identical ones included. */
    val comparisons: List<Comparison> = emptyList(),
) {
    val errors get() = findings.count { it.severity == Severity.ERROR }
    val warnings get() = findings.count { it.severity == Severity.WARNING }
    val blocking get() = errors > 0
    fun table(name: String) = tables.firstOrNull { it.table == name }
    val differences get() = comparisons.filter { it.verdict != Verdict.SAME }

    /** "BLOCKED: 1 blocker, 5 compatible difference(s)", "OK: 3 compatible difference(s)" or "IDENTICAL". */
    val overall: String
        get() {
            val blockers = comparisons.count { it.verdict == Verdict.BLOCKER }
            val compatible = comparisons.count { it.verdict == Verdict.COMPATIBLE }
            return when {
                blocking -> "BLOCKED: ${maxOf(blockers, errors)} blocker(s), $compatible compatible difference(s)"
                compatible > 0 -> "OK: $compatible compatible difference(s)"
                else -> "IDENTICAL"
            }
        }
}

/**
 * Compares the selected source tables with the target and decides, per table and
 * column, what can be copied. Pure: works on two [Catalog]s.
 */
class SchemaDiff(
    private val schemas: List<String>,
    private val filter: TableFilter,
    private val chunking: ChunkingConfig,
    private val allowTypeDifferences: Boolean,
    /** `schema.table` of the tool's own state table; never compared or copied. */
    private val stateTable: String,
) {
    fun compare(source: Catalog, target: Catalog, configHash: String, sourceName: String, targetName: String): DiffReport {
        val findings = mutableListOf<Finding>()
        fun schemaError(schema: String, msg: String) = findings.add(Finding(Severity.ERROR, schema, null, msg))

        val comparisons = mutableListOf<Comparison>()
        for (schema in schemas) {
            val inSource = schema in source.schemas
            val inTarget = schema in target.schemas
            when {
                !inSource -> schemaError(schema, "schema does not exist in source")
                !inTarget -> schemaError(schema, "schema does not exist in target (has the target schema been created, e.g. by Liquibase?)")
            }
            comparisons += Comparison(
                schema, "schema", schema, if (inSource) "schema" else null, if (inTarget) "schema" else null,
                if (inSource && inTarget) Verdict.SAME else Verdict.BLOCKER,
                findings.filter { it.table == schema }.joinToString("; ") { it.message }.ifEmpty { null },
            )
        }

        fun selected(c: Catalog) = c.tables.values
            .filter { it.qualified != stateTable && filter.matches(it.schema, it.name) }
            .sortedBy { it.qualified }

        val plans = selected(source).map { src ->
            val tableFindings = mutableListOf<Finding>()
            val plan = planTable(src, target, tableFindings)
            findings += tableFindings
            comparisons += ComparisonRows(src, target.tables[src.qualified], tableFindings).rows()
            plan
        }
        val sourceNames = source.tables.keys
        val targetOnly = selected(target).map { it.qualified }.filter { it !in sourceNames && it.substringBefore('.') in schemas }
        targetOnly.forEach {
            val note = "only in target: left as it is"
            findings += Finding(Severity.INFO, it, null, note)
            comparisons += Comparison(it, "table", it, null, "table", Verdict.COMPATIBLE, note)
        }

        return DiffReport(Instant.now(), configHash, sourceName, targetName, plans, targetOnly, findings, comparisons)
    }

    private fun planTable(src: Table, target: Catalog, out: MutableList<Finding>): TablePlan {
        val t = src.qualified
        fun add(sev: Severity, subject: String?, msg: String) = out.add(Finding(sev, t, subject, msg))
        fun plan(action: TableAction, reason: String?, columns: List<ColumnPlan> = emptyList(), chunks: Long? = null) =
            TablePlan(t, action, reason, columns, src.primaryKey, src.primaryKey.map { src.column(it)!!.type }, src.estRows, chunks)

        if (!src.readable) {
            add(Severity.ERROR, null, "source user has no SELECT privilege")
            return plan(TableAction.BLOCKED, "no SELECT privilege on source")
        }
        val dst = target.tables[t]
        if (dst == null) {
            if (src.schema !in target.schemas) return plan(TableAction.SKIP, "schema missing in target")
            add(Severity.WARNING, null, "table missing in target: not copied")
            return plan(TableAction.SKIP, "missing in target")
        }

        val columns = compareColumns(src, dst, ::add)
        compareConstraints(src, dst, ::add)
        compareIndexes(src, dst, ::add)

        if (columns.isEmpty()) {
            add(Severity.WARNING, null, "no columns in common: not copied")
            return plan(TableAction.SKIP, "no columns in common")
        }
        if (src.primaryKey.isEmpty()) {
            add(Severity.INFO, null, "no primary key: copied in one piece; an interrupted copy of it starts over")
        }
        columns.filter { it.largeObject }.takeIf { it.isNotEmpty() }?.let { lo ->
            add(Severity.INFO, null, "large-object column(s) ${lo.joinToString(", ") { it.name }}: rows and objects copied one by one")
        }
        if (out.any { it.severity == Severity.ERROR }) {
            return plan(TableAction.BLOCKED, out.first { it.severity == Severity.ERROR }.let { e -> e.subject?.let { "$it: ${e.message}" } ?: e.message }, columns)
        }
        return plan(TableAction.COPY, null, columns, chunkCount(src))
    }

    private fun compareColumns(src: Table, dst: Table, add: (Severity, String?, String) -> Unit): List<ColumnPlan> {
        val plans = mutableListOf<ColumnPlan>()
        for (s in src.columns) {
            val d = dst.column(s.name)
            if (d == null) {
                add(Severity.WARNING, s.name, "column missing in target: its data is not copied")
                continue
            }
            if (d.generated != null) {
                if (s.generated == null) add(Severity.INFO, s.name, "generated in target: computed there, not copied")
                continue
            }
            var asText = false
            if (s.type != d.type) {
                asText = true
                when {
                    isWidening(s.type, d.type) ->
                        add(Severity.WARNING, s.name, "type ${s.type} in source, ${d.type} in target: safe widening, copied as text")
                    allowTypeDifferences ->
                        add(Severity.WARNING, s.name, "type ${s.type} in source, ${d.type} in target: copied as text (diff.allowTypeDifferences)")
                    else ->
                        add(Severity.ERROR, s.name, "type ${s.type} in source, ${d.type} in target; set diff.allowTypeDifferences: true to copy it as text anyway")
                }
            }
            if (!s.notNull && d.notNull) {
                add(Severity.WARNING, s.name, "nullable in source but NOT NULL in target: rows with NULL here will fail")
            }
            if (s.default != d.default && s.identity == null && d.identity == null) {
                add(Severity.INFO, s.name, "default differs: ${s.default ?: "none"} in source, ${d.default ?: "none"} in target")
            }
            plans += ColumnPlan(s.name, s.type, d.type, d.identity == 'a', asText, s.typeName == "oid" && d.typeName == "oid")
        }
        for (d in dst.columns) {
            if (src.column(d.name) != null) continue
            if (d.notNull && d.default == null && d.identity == null && d.generated == null) {
                add(Severity.ERROR, d.name, "extra NOT NULL column without default in target: every insert would fail")
            } else {
                add(Severity.INFO, d.name, "only in target: gets its default")
            }
        }
        return plans
    }

    private fun compareConstraints(src: Table, dst: Table, add: (Severity, String?, String) -> Unit) =
        compareNamed(src.constraints, dst.constraints, { it.name }, { "${it.type}:${it.definition}" }) { kind, s, d ->
            val what = (s ?: d)!!.typeName
            when (kind) {
                Match.DIFFERENT -> add(Severity.WARNING, s!!.name, "$what differs: ${s.definition} in source, ${d!!.definition} in target")
                Match.RENAMED -> add(Severity.INFO, d!!.name, "$what is named ${s!!.name} in source")
                Match.MISSING -> add(Severity.WARNING, s!!.name, "$what missing in target: ${s.definition}")
                Match.EXTRA -> add(Severity.WARNING, d!!.name, "$what only in target: ${d.definition}")
            }
        }

    private fun compareIndexes(src: Table, dst: Table, add: (Severity, String?, String) -> Unit) =
        compareNamed(src.indexes, dst.indexes, { it.name }, { it.definition }) { kind, s, d ->
            when (kind) {
                Match.DIFFERENT -> add(Severity.WARNING, s!!.name, "index differs: ${s.definition} in source, ${d!!.definition} in target")
                Match.RENAMED -> add(Severity.INFO, d!!.name, "index is named ${s!!.name} in source")
                Match.MISSING -> add(Severity.WARNING, s!!.name, "index missing in target: ${s.definition}")
                Match.EXTRA -> add(Severity.INFO, d!!.name, "index only in target: ${d.definition}")
            }
        }

    private enum class Match { DIFFERENT, RENAMED, MISSING, EXTRA }

    /**
     * Pairs objects by name; same name with another definition is DIFFERENT. Unpaired
     * objects with equal definitions are RENAMED (Liquibase and hand-made schemas often
     * name things differently); the rest are MISSING in or EXTRA on the target.
     */
    private fun <T> compareNamed(src: List<T>, dst: List<T>, name: (T) -> String, def: (T) -> String, report: (Match, T?, T?) -> Unit) {
        val dstByName = dst.associateBy(name)
        val unmatchedSrc = mutableListOf<T>()
        for (s in src) {
            val d = dstByName[name(s)]
            when {
                d == null -> unmatchedSrc += s
                def(s) != def(d) -> report(Match.DIFFERENT, s, d)
            }
        }
        val srcNames = src.map(name).toSet()
        val unmatchedDst = dst.filter { name(it) !in srcNames }.toMutableList()
        for (s in unmatchedSrc) {
            val d = unmatchedDst.firstOrNull { def(it) == def(s) }
            if (d != null) {
                unmatchedDst.remove(d)
                report(Match.RENAMED, s, d)
            } else {
                report(Match.MISSING, s, null)
            }
        }
        unmatchedDst.forEach { report(Match.EXTRA, null, it) }
    }

    private fun chunkCount(t: Table): Long? {
        if (t.primaryKey.isEmpty() || t.estRows < chunking.minRowsToSplit) return null
        return (t.estRows + chunking.rowsPerChunk - 1) / chunking.rowsPerChunk
    }

    companion object {
        private val VARCHAR = Regex("""character varying\((\d+)\)""")
        private val CHAR = Regex("""character\((\d+)\)""")
        private val NUMERIC = Regex("""numeric\((\d+),(\d+)\)""")
        private val INTEGERS = listOf("smallint", "integer", "bigint")

        /** True if every value of [from] fits [to] unchanged, so a text-format copy can't fail or alter it. */
        fun isWidening(from: String, to: String): Boolean {
            if (from == to) return true
            if (to == "text" && (VARCHAR.matches(from) || from == "character varying")) return true
            if (to == "character varying" && VARCHAR.matches(from)) return true
            VARCHAR.matchEntire(from)?.let { f ->
                VARCHAR.matchEntire(to)?.let { t -> return t.groupValues[1].toInt() >= f.groupValues[1].toInt() }
            }
            if (CHAR.matches(from) && (to == "text" || to == "character varying")) return false // padding would be kept
            val fi = INTEGERS.indexOf(from)
            val ti = INTEGERS.indexOf(to)
            if (fi >= 0 && ti >= 0) return ti >= fi
            if (fi >= 0 && to == "numeric") return true
            if (from == "real" && to == "double precision") return true
            NUMERIC.matchEntire(from)?.let { f ->
                if (to == "numeric") return true
                NUMERIC.matchEntire(to)?.let { t ->
                    val (fp, fs) = f.destructured
                    val (tp, ts) = t.destructured
                    return ts.toInt() >= fs.toInt() && tp.toInt() - ts.toInt() >= fp.toInt() - fs.toInt()
                }
            }
            return false
        }
    }
}

/**
 * The comparison rows of one source table (and its target counterpart, if any). Verdicts
 * come from the table's findings, so the rows and the findings always agree: an ERROR
 * makes a BLOCKER, a different value or a WARNING makes COMPATIBLE, anything else SAME.
 */
private class ComparisonRows(private val src: Table, private val dst: Table?, private val findings: List<Finding>) {
    private val t = src.qualified

    fun rows(): List<Comparison> = buildList {
        // A table row is about existence; its size is just shown.
        add(row("table", t, null, "table, ~%,d rows".format(src.estRows.coerceAtLeast(0)), dst?.let { "table" }, same = dst != null))
        if (dst == null) return@buildList
        for (name in (src.columns.map { it.name } + dst.columns.map { it.name }).distinct()) {
            add(row("column", name, name, src.column(name)?.let(::describe), dst.column(name)?.let(::describe)))
        }
        val srcCons = src.constraints.associateBy { it.name }
        val dstCons = dst.constraints.associateBy { it.name }
        for (name in (srcCons.keys + dstCons.keys).distinct().sorted()) {
            val s = srcCons[name]
            val d = dstCons[name]
            add(row((s ?: d)!!.typeName, name, name, s?.definition, d?.definition))
        }
        val srcIdx = src.indexes.associateBy { it.name }
        val dstIdx = dst.indexes.associateBy { it.name }
        for (name in (srcIdx.keys + dstIdx.keys).distinct().sorted()) {
            add(row("index", name, name, srcIdx[name]?.definition, dstIdx[name]?.definition))
        }
    }

    private fun row(kind: String, name: String, subject: String?, source: String?, target: String?, same: Boolean = source == target): Comparison {
        val about = findings.filter { it.subject == subject }
        val verdict = when {
            about.any { it.severity == Severity.ERROR } -> Verdict.BLOCKER
            !same || about.any { it.severity == Severity.WARNING } -> Verdict.COMPATIBLE
            else -> Verdict.SAME
        }
        val note = about.joinToString("; ") { it.message }.ifEmpty { null }
            ?: if (source != null && target == null && kind != "column" && kind != "table") "not in target under this name" else null
        return Comparison(t, kind, name, source, target, verdict, note)
    }

    /** "character varying(20) not null default 'x'", "integer generated always as identity", … */
    private fun describe(c: Column) = buildString {
        append(c.type)
        if (c.notNull) append(" not null")
        c.default?.let { append(" default $it") }
        when (c.identity) {
            'a' -> append(" generated always as identity")
            'd' -> append(" generated by default as identity")
        }
        c.generated?.let { append(" generated always as ($it) stored") }
    }
}
