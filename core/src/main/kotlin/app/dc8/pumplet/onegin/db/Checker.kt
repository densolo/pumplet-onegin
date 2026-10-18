package app.dc8.pumplet.onegin.db

import app.dc8.pumplet.onegin.config.DbConfig
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.config.TableFilter
import java.sql.Connection
import java.sql.SQLException

/** Result of [Checker.run]. Every message is already scrubbed of secrets. */
data class CheckReport(val source: SourceCheck, val target: TargetCheck) {
    val problems: List<String> get() = source.problems + target.problems
    val ok: Boolean get() = problems.isEmpty()
}

/** How a connection attempt went: the server version, or why it failed. */
data class Connected(val description: String, val serverVersion: String?, val error: String?)

data class SourceTable(val schema: String, val name: String, val estRows: Long, val readable: Boolean, val chunks: Long?) {
    val qualified get() = "$schema.$name"
}

data class SourceCheck(
    val connection: Connected,
    val schemas: List<String>,
    val missingSchemas: List<String> = emptyList(),
    val totalTables: Int = 0,
    val selected: List<SourceTable> = emptyList(),
    val excluded: List<String> = emptyList(),
) {
    val noMatch: Boolean get() = connection.error == null && selected.isEmpty() && missingSchemas.size < schemas.size

    val problems: List<String>
        get() = buildList {
            connection.error?.let { add(it) }
            missingSchemas.forEach { add("source schema '$it' does not exist") }
            selected.filter { !it.readable }.forEach { add("source user has no SELECT privilege on ${it.qualified}") }
            if (noMatch) add("no source tables match tables.include/exclude")
        }
}

data class TargetCheck(val connection: Connected, val alreadyPresent: Int = 0, val selectedCount: Int = 0) {
    val problems: List<String>
        get() = listOfNotNull(connection.error)
}

/**
 * Connects to both databases and reports what the job would copy: which source
 * tables the filters select, their estimated size and chunking, whether the job
 * user can read them, and whether they already exist on the target.
 */
class Checker(private val config: JobConfig) {
    private val redactor = config.redactor

    fun run(): CheckReport {
        val source = checkSource()
        val target = checkTarget(source.selected)
        return CheckReport(source, target)
    }

    private fun <T> connect(db: DbConfig, role: Role, block: (Connection) -> T): Pair<Connected, T?> = try {
        Db.connect(db, role).use { c ->
            val version = "${c.metaData.databaseProductName} ${c.metaData.databaseProductVersion}"
            Connected(Db.describe(db), version, null) to block(c)
        }
    } catch (e: DbConnectException) {
        Connected(Db.describe(db), null, redactor.scrub(e.message)) to null
    } catch (e: SQLException) {
        Connected(Db.describe(db), null, redactor.scrub("query on the ${role.label} database failed: ${e.message}")) to null
    }

    private fun checkSource(): SourceCheck {
        val schemas = config.source.schemas
        val (conn, check) = connect(config.source, Role.SOURCE) { c -> inspectSource(c, schemas) }
        return check?.copy(connection = conn) ?: SourceCheck(conn, schemas)
    }

    private fun inspectSource(c: Connection, schemas: List<String>): SourceCheck {
        val existing = c.prepareStatement("select nspname from pg_namespace where nspname = any(?)").use { ps ->
            ps.setArray(1, c.createArrayOf("text", schemas.toTypedArray()))
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        val all = c.prepareStatement(
            """
            select n.nspname, c.relname, c.reltuples::bigint, has_table_privilege(c.oid, 'SELECT')
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where c.relkind in ('r', 'p') and not c.relispartition and n.nspname = any(?)
            order by 1, 2
            """.trimIndent()
        ).use { ps ->
            ps.setArray(1, c.createArrayOf("text", schemas.toTypedArray()))
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val rows = rs.getLong(3)
                        add(SourceTable(rs.getString(1), rs.getString(2), rows, rs.getBoolean(4), chunkCount(rows)))
                    }
                }
            }
        }
        val filter = TableFilter(config.tables)
        // The tool's own state table is never copied, even if a source has one.
        val (selected, excluded) = all.filter { it.qualified != config.state.table.qualified }
            .partition { filter.matches(it.schema, it.name) }
        return SourceCheck(
            connection = Connected("", null, null),
            schemas = schemas,
            missingSchemas = schemas.filter { it !in existing },
            totalTables = all.size,
            selected = selected,
            excluded = excluded.map { it.qualified },
        )
    }

    private fun checkTarget(selected: List<SourceTable>): TargetCheck {
        val (conn, present) = connect(config.target, Role.TARGET) { c ->
            c.prepareStatement(
                """
                select n.nspname || '.' || c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where c.relkind in ('r', 'p') and n.nspname = any(?)
                """.trimIndent()
            ).use { ps ->
                ps.setArray(1, c.createArrayOf("text", config.source.schemas.toTypedArray()))
                ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        return TargetCheck(conn, selected.count { present != null && it.qualified in present }, selected.size)
    }

    private fun chunkCount(estRows: Long): Long? {
        val ch = config.chunking
        if (estRows < ch.minRowsToSplit) return null
        return (estRows + ch.rowsPerChunk - 1) / ch.rowsPerChunk
    }
}
