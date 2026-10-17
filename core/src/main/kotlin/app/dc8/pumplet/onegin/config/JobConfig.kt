package app.dc8.pumplet.onegin.config

import app.dc8.pumplet.onegin.db.Sql
import java.nio.file.Path
import java.security.MessageDigest

/** A configuration problem (or several). Messages never contain secret values. */
class ConfigException(val problems: List<String>) : RuntimeException(problems.joinToString("\n")) {
    constructor(problem: String) : this(listOf(problem))
}

/**
 * A password resolved from the secrets file. Deliberately not a data class:
 * [toString] shows only the reference, so the value can't leak through logging
 * or string templates. Use [reveal] only when handing it to the JDBC driver.
 */
class Secret(val name: String, private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "\${secret:$name}"
}

data class DbConfig(
    val url: String,
    val user: String?,
    val password: Secret?,
    /** Only meaningful for the source; empty for the target. */
    val schemas: List<String>,
)

data class TablesConfig(val include: List<String>, val exclude: List<String>)

data class ThreadsConfig(val readers: Int, val writers: Int)

data class ChunkingConfig(val rowsPerChunk: Long, val minRowsToSplit: Long)

data class BufferConfig(val maxMemoryMb: Int)

data class ConstraintsConfig(
    val dropForeignKeys: Boolean,
    val dropSecondaryIndexes: Boolean,
    val disableUserTriggers: Boolean,
)

/** A `schema.table` name. [parse] accepts only plain lower-case identifiers; other names come from the database. */
data class TableName(val schema: String, val table: String) {
    val qualified get() = "$schema.$table"
    /** Quoted for use in SQL. */
    val sql get() = "${Sql.ident(schema)}.${Sql.ident(table)}"
    override fun toString() = qualified

    companion object {
        private val IDENT = Regex("[a-z_][a-z0-9_]{0,62}")

        fun parse(text: String): TableName? {
            val parts = text.split('.')
            if (parts.size != 2 || parts.any { !IDENT.matches(it) }) return null
            return TableName(parts[0], parts[1])
        }
    }
}

data class DiffConfig(
    /** Copy columns whose types differ (beyond safe widening) instead of blocking the table; they go as text. */
    val allowTypeDifferences: Boolean,
)

/** Where session state lives on the target. */
data class StateConfig(val table: TableName) {
    companion object {
        const val DEFAULT_NAME = "pumplet_onegin_state"

        /** By default the state table sits in the (first) schema being copied, next to the data it describes. */
        fun default(sourceSchemas: List<String>) = StateConfig(TableName(sourceSchemas.first(), DEFAULT_NAME))
    }
}

data class JobConfig(
    val jobFile: Path,
    val secretsFile: Path?,
    val source: DbConfig,
    val target: DbConfig,
    val tables: TablesConfig,
    val threads: ThreadsConfig,
    val chunking: ChunkingConfig,
    val buffer: BufferConfig,
    val constraints: ConstraintsConfig,
    val diff: DiffConfig = DiffConfig(allowTypeDifferences = false),
    val state: StateConfig,
    /** Scrubs every value from the secrets file; use on anything that may reach output. */
    val redactor: Redactor,
) {
    /**
     * Identifies the job for session matching. Excludes passwords, the secrets
     * file location and the state table location, so rotating a password or running with `--secrets` on another
     * machine doesn't break resume.
     */
    fun configHash(): String {
        val canonical = buildString {
            fun line(k: String, v: Any?) = append(k).append('=').append(v).append('\n')
            line("source.url", source.url)
            line("source.user", source.user)
            line("source.schemas", source.schemas.joinToString(","))
            line("target.url", target.url)
            line("target.user", target.user)
            line("tables.include", tables.include.joinToString(","))
            line("tables.exclude", tables.exclude.joinToString(","))
            line("threads.readers", threads.readers)
            line("threads.writers", threads.writers)
            line("chunking.rowsPerChunk", chunking.rowsPerChunk)
            line("chunking.minRowsToSplit", chunking.minRowsToSplit)
            line("buffer.maxMemoryMb", buffer.maxMemoryMb)
            line("constraints.dropForeignKeys", constraints.dropForeignKeys)
            line("constraints.dropSecondaryIndexes", constraints.dropSecondaryIndexes)
            line("constraints.disableUserTriggers", constraints.disableUserTriggers)
            line("diff.allowTypeDifferences", diff.allowTypeDifferences)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String =
        "JobConfig(jobFile=$jobFile, source=${source.copy(url = Redactor.safeJdbcUrl(source.url))}, " +
            "target=${target.copy(url = Redactor.safeJdbcUrl(target.url))}, tables=$tables, threads=$threads, " +
            "chunking=$chunking, buffer=$buffer, constraints=$constraints, diff=$diff, state=$state)"
}
