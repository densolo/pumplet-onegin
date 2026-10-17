package app.dc8.pumplet.onegin.state

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import java.sql.Connection

/** Everything recorded about one session. Runs left RUNNING by a dead process show as INTERRUPTED. */
data class SessionDetail(
    val session: SessionInfo,
    val sameJob: Boolean,
    val runs: List<RunInfo>,
    val stages: List<StageInfo>,
    val tables: List<TableProgress>,
    val objects: List<SavedObject>,
    val report: RunReport?,
)

data class SessionSummary(val session: SessionInfo, val sameJob: Boolean, val lastRun: RunInfo?)

data class StatusReport(
    val stateTable: String,
    val target: String,
    val tableExists: Boolean,
    /** A pumplet-onegin process currently holds the target lock. */
    val processRunning: Boolean,
    /** The requested session, or this job's latest; null if there is none. */
    val detail: SessionDetail?,
    /** Earlier sessions: this job's, or every job's with `all`. */
    val history: List<SessionSummary>,
)

/** Reads the state table without writing anything to the target. */
class StatusReader(private val config: JobConfig) {
    private val store = StateStore(config.state.table, config.redactor)

    /**
     * @param sessionId a session id or unique prefix; default is this job's latest session.
     * @param all include sessions of other jobs (other config hashes) in the history.
     */
    fun read(sessionId: String? = null, all: Boolean = false, historyLimit: Int = 10): StatusReport =
        Db.connect(config.target, Role.TARGET).use { c ->
            c.isReadOnly = true
            val base = StatusReport(store.table.qualified, Db.describe(config.target), false, false, null, emptyList())
            if (!store.exists(c)) {
                if (sessionId != null) throw StateException("no session '$sessionId': the state table ${store.table} does not exist yet")
                return base
            }
            val live = TargetLock.heldElsewhere(c, store.table)
            val hash = config.configHash()
            val chosen = if (sessionId != null) {
                store.findSession(c, sessionId) ?: throw StateException("no session '$sessionId' in ${store.table}")
            } else {
                store.sessions(c, hash, limit = 1).firstOrNull()
            }
            val history = store.sessions(c, if (all) null else hash, limit = historyLimit + 1)
                .filter { it.id != chosen?.id }
                .take(historyLimit)
                .map { s -> SessionSummary(s, s.configHash == hash, runs(c, s.id, live).lastOrNull()) }
            base.copy(
                tableExists = true,
                processRunning = live,
                detail = chosen?.let { detail(c, it, it.configHash == hash, live) },
                history = history,
            )
        }

    private fun detail(c: Connection, s: SessionInfo, sameJob: Boolean, live: Boolean): SessionDetail {
        val runs = runs(c, s.id, live)
        val interrupted = runs.lastOrNull()?.status == RunStatus.INTERRUPTED
        // Committed chunks are the truth for tables still in progress (the table row is written at start and end).
        val chunks = store.list<ChunkDone>(c, s.id, Kind.CHUNK).groupBy { it.table }
        val plans = store.list<ChunkPlan>(c, s.id, Kind.CHUNK_PLAN).associateBy { it.table }
        val tables = store.list<TableProgress>(c, s.id, Kind.TABLE).map { t ->
            if (t.status == TableStatus.DONE) return@map t
            val done = chunks[t.table].orEmpty()
            t.copy(
                status = if (t.status == TableStatus.RUNNING && interrupted) TableStatus.PENDING else t.status,
                rows = done.sumOf { it.rows }, bytes = done.sumOf { it.bytes }, chunksDone = done.size,
                chunksTotal = plans[t.table]?.chunks ?: t.chunksTotal,
            )
        }
        return SessionDetail(
            session = s,
            sameJob = sameJob,
            runs = runs,
            stages = store.list<StageInfo>(c, s.id, Kind.STAGE)
                .map { if (it.status == StageStatus.RUNNING && interrupted) it.copy(status = StageStatus.INTERRUPTED) else it }
                .sortedBy { Stage.of(it.stage)?.ordinal ?: Int.MAX_VALUE },
            tables = tables,
            objects = store.list(c, s.id, Kind.OBJECT),
            report = store.get(c, s.id, Kind.REPORT, ""),
        )
    }

    private fun runs(c: Connection, session: String, live: Boolean): List<RunInfo> =
        store.list<RunInfo>(c, session, Kind.RUN).map {
            if (it.status == RunStatus.RUNNING && !live) {
                it.copy(status = RunStatus.INTERRUPTED, error = SessionManager.INTERRUPTED_MESSAGE)
            } else it
        }
}
