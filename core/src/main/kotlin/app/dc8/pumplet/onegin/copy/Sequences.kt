package app.dc8.pumplet.onegin.copy

import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.SqlError
import java.sql.Connection

/** A sequence's position: [lastValue] is null if it was never used (or can't be read). */
data class SequenceState(val name: String, val lastValue: Long?, val increment: Long, val readable: Boolean)

data class SequenceSyncResult(
    /** name -> (old target value or null, new value) */
    val raised: Map<String, Pair<Long?, Long>>,
    /** Already at or past the source. */
    val kept: List<String>,
    /** Can't read on the source (no privilege), or descending; left alone. */
    val skipped: Map<String, String>,
) {
    val summary
        get() = buildString {
            append("${raised.size} sequence(s) raised to the source's value, ${kept.size} already ahead")
            if (skipped.isNotEmpty()) append(", ${skipped.size} skipped")
        }
}

/**
 * Keeps target sequences from handing out ids the source has already used. Never
 * lowers a sequence: a target that is ahead stays ahead.
 */
object Sequences {
    /** Sequences of [schemas] with their position (`pg_sequences.last_value`), keyed by `schema.name`. */
    fun read(c: Connection, schemas: List<String>, role: Role): Map<String, SequenceState> =
        SqlError.on(c, role, "reading the sequences of schemas ${schemas.joinToString(", ")}", "select … from pg_sequences …") { c.prepareStatement(
            """
            select n.nspname || '.' || c.relname, s.last_value, q.seqincrement,
                   has_sequence_privilege(c.oid, 'SELECT') or has_sequence_privilege(c.oid, 'USAGE')
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            join pg_sequence q on q.seqrelid = c.oid
            left join pg_sequences s on s.schemaname = n.nspname and s.sequencename = c.relname
            where c.relkind = 'S' and n.nspname = any(?)
            """.trimIndent()
        ).use { ps ->
            ps.setArray(1, c.createArrayOf("text", schemas.toTypedArray()))
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val name = rs.getString(1)
                        put(name, SequenceState(name, rs.getLong(2).takeUnless { rs.wasNull() }, rs.getLong(3), rs.getBoolean(4)))
                    }
                }
            }
        } }

    /**
     * Moves sequence [qualified] (a quoted, qualified name) on the target so that its next
     * value is after [value], unless it already is. Returns the old position if it moved.
     */
    fun raise(c: Connection, qualified: String, value: Long): Pair<Boolean, Long?> {
        // Reads just this sequence (null if never used). Needs privileges on it only: a query over
        // pg_sequences would resolve every sequence's name, and fail on schemas the user can't use.
        val current = c.prepareStatement("select pg_sequence_last_value(cast(? as regclass))").use { ps ->
            ps.setString(1, qualified)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
        }
        if (current != null && current >= value) return false to current
        c.prepareStatement("select setval(cast(? as regclass), ?, true)").use { ps ->
            ps.setString(1, qualified)
            ps.setLong(2, value)
            ps.executeQuery().close()
        }
        return true to current
    }

    /** Raises every target sequence of [schemas] that is behind its source counterpart. */
    fun raiseToSource(source: Connection, target: Connection, schemas: List<String>): SequenceSyncResult {
        val src = read(source, schemas, Role.SOURCE)
        val dst = read(target, schemas, Role.TARGET)
        val raised = linkedMapOf<String, Pair<Long?, Long>>()
        val kept = mutableListOf<String>()
        val skipped = linkedMapOf<String, String>()
        for (name in dst.keys.sorted()) {
            val s = src[name] ?: continue
            when {
                !s.readable -> skipped[name] = "no SELECT privilege on the source sequence"
                s.increment < 0 || dst.getValue(name).increment < 0 -> skipped[name] = "descending sequence"
                s.lastValue == null -> kept += name
                else -> {
                    val (moved, old) = SqlError.on(target, Role.TARGET, "sequence $name", "select setval('${quoted(name)}', ${s.lastValue}, true)") {
                        raise(target, quoted(name), s.lastValue)
                    }
                    if (moved) raised[name] = old to s.lastValue else kept += name
                }
            }
        }
        return SequenceSyncResult(raised, kept, skipped)
    }

    private fun quoted(name: String) = app.dc8.pumplet.onegin.db.Sql.table(name)
}
