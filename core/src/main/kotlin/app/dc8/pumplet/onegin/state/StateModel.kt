package app.dc8.pumplet.onegin.state

import java.time.Instant

/*
 * What the state table holds. Each record is one row (session_id, kind, key, payload jsonb).
 * A session spans every command that works on the same job (same config hash) until the
 * job is finished; each command invocation within it is a run.
 */

/** The `kind` column. */
object Kind {
    const val SESSION = "session"
    const val RUN = "run"
    const val STAGE = "stage"
    const val TABLE = "table"
    const val CHUNK = "chunk"
    const val CHUNK_PLAN = "chunkplan"
    const val OBJECT = "object"
    const val REPORT = "report"
}

enum class Stage(val label: String) {
    DIFF("diff"), DISABLE("disable"), COPY("copy"), ENABLE("enable");

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label }
    }
}

enum class SessionStatus { OPEN, COMPLETED }

/** Stored run status. A RUNNING run whose process is gone is reported as [RunStatus.INTERRUPTED]. */
enum class RunStatus { RUNNING, SUCCEEDED, FAILED, INTERRUPTED }

/** INTERRUPTED is never stored: `status` shows a RUNNING stage of a killed run that way. */
enum class StageStatus { RUNNING, DONE, FAILED, INTERRUPTED }

enum class TableStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

enum class ObjectKind { FOREIGN_KEY, INDEX, TRIGGER }

/**
 * A dropped/disabled object's lifecycle: saved and DROPPED, then RESTORED; PENDING after a
 * failed restore (retried by the next `enable`); SKIPPED if the user gave up on restoring it.
 */
enum class ObjectStatus { DROPPED, RESTORED, PENDING, SKIPPED;

    /** Not in place on the target. */
    val outstanding get() = this == DROPPED || this == PENDING
}

data class SessionInfo(
    val id: String,
    val configHash: String,
    val jobFile: String,
    val status: SessionStatus,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    /** The furthest stage started so far. */
    val stage: String? = null,
)

data class RunInfo(
    val seq: Int,
    val command: String,
    val host: String,
    val pid: Long,
    val status: RunStatus,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val error: String? = null,
) {
    val key get() = runKey(seq)

    companion object {
        fun runKey(seq: Int) = "%06d".format(seq)
    }
}

data class StageInfo(
    val stage: String,
    val status: StageStatus,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val error: String? = null,
    /** Short result, e.g. "14 foreign keys dropped". */
    val summary: String? = null,
)

data class TableProgress(
    val table: String,
    val status: TableStatus,
    val estRows: Long? = null,
    val rows: Long = 0,
    val bytes: Long = 0,
    val chunksTotal: Int = 0,
    val chunksDone: Int = 0,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val error: String? = null,
)

/**
 * How a table is split, fixed on the first copy so a resumed copy uses the same chunks.
 * [bounds] holds the primary-key values between chunks (each a list of column values as
 * text), so there are `bounds.size + 1` chunks; empty means one chunk.
 */
data class ChunkPlan(val table: String, val primaryKey: List<String>, val bounds: List<List<String>>) {
    val chunks get() = bounds.size + 1
}

/** A chunk committed to the target; written in the same transaction as its rows. */
data class ChunkDone(
    val table: String,
    val index: Int,
    val rows: Long,
    val bytes: Long,
    val durationMs: Long,
    val runSeq: Int,
) {
    val key get() = chunkKey(table, index)

    companion object {
        fun chunkKey(table: String, index: Int) = "$table#%06d".format(index)
    }
}

data class SavedObject(
    val kind: ObjectKind,
    val table: String,
    val name: String,
    /** Foreign key: pg_get_constraintdef; index: pg_get_indexdef; trigger: pg_get_triggerdef. */
    val definition: String,
    val status: ObjectStatus,
    val error: String? = null,
    /** Trigger only: `pg_trigger.tgenabled` before it was disabled (O, R or A). */
    val enabledMode: String? = null,
) {
    val key get() = "${kind.name.lowercase()}:$table:$name"
}

data class TableTiming(val table: String, val rows: Long, val bytes: Long, val durationMs: Long)

/** Final report of a copy, stored once it finishes so `status` can show it later. */
data class RunReport(
    val runSeq: Int,
    val tables: List<TableTiming>,
    val stageDurationsMs: Map<String, Long>,
    val totalRows: Long,
    val totalBytes: Long,
    val durationMs: Long,
)
