package app.dc8.pumplet.onegin.state

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.config.TableName
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import java.net.InetAddress
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * One process at a time works on a target. It holds a session-level advisory
 * lock, keyed by the state table, on its own connection for as long as it runs.
 * If the process dies, the server releases the lock with the connection, which is
 * how `status` tells a running run from an interrupted one.
 */
internal object TargetLock {
    private const val CLASS_ID = 0x50554d50 // "PUMP"

    fun tryLock(c: Connection, table: TableName): Boolean = call(c, "pg_try_advisory_lock", table)

    fun unlock(c: Connection, table: TableName) {
        call(c, "pg_advisory_unlock", table)
    }

    /** True if another connection holds the lock. Takes and immediately releases it if free. */
    fun heldElsewhere(c: Connection, table: TableName): Boolean {
        if (!tryLock(c, table)) return true
        unlock(c, table)
        return false
    }

    private fun call(c: Connection, fn: String, table: TableName): Boolean =
        c.prepareStatement("select $fn($CLASS_ID, hashtext(?))").use { ps ->
            ps.setString(1, table.qualified)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
}

/** Which session a command works on. */
sealed interface SessionChoice {
    /** This job's open session, or a new one. */
    data object StartOrResume : SessionChoice

    /** This job's open session; fails if there is none. */
    data object ResumeOnly : SessionChoice

    /** A specific session (any job), by id or unique prefix. */
    data class Existing(val id: String) : SessionChoice
}

/** Starts or resumes the session for a job and records each command as a run. */
class SessionManager(private val config: JobConfig, private val clock: Clock = Clock.systemUTC()) {
    private val store = StateStore(config.state.table, config.redactor)

    /**
     * Opens a run of [command] in the session picked by [choice]. Creates the
     * state table if needed.
     *
     * @throws StateException if another process is working on the target, if no
     *   session matches [choice], or if a new session would start while another
     *   session still has objects dropped.
     */
    fun begin(command: String, choice: SessionChoice = SessionChoice.StartOrResume): ActiveSession {
        val c = Db.connect(config.target, Role.TARGET)
        try {
            store.ensure(c)
            if (!TargetLock.tryLock(c, store.table)) {
                throw StateException(
                    "another pumplet-onegin process is working on this target (state table ${store.table}); " +
                        "wait for it to finish or stop it"
                )
            }
            markInterrupted(c)
            val hash = config.configHash()
            val previous = when (choice) {
                is SessionChoice.Existing -> store.findSession(c, choice.id) ?: throw StateException("no session '${choice.id}' in ${store.table}")
                else -> store.sessions(c, hash, limit = 1).firstOrNull()?.takeIf { it.status == SessionStatus.OPEN }
            }
            if (previous == null && choice == SessionChoice.ResumeOnly) {
                throw StateException("no open session for this job in ${store.table}; nothing to do (see `status`)")
            }
            val resumed = previous != null
            if (!resumed) refuseWhileObjectsDropped(c)
            val session = if (resumed) previous!! else SessionInfo(
                id = UUID.randomUUID().toString(),
                configHash = hash,
                jobFile = config.jobFile.toString(),
                status = SessionStatus.OPEN,
                createdAt = now(),
            )
            if (!resumed) store.put(c, session.id, Kind.SESSION, "", session)
            val seq = (store.list<RunInfo>(c, session.id, Kind.RUN).maxOfOrNull { it.seq } ?: 0) + 1
            val run = RunInfo(seq, command, hostName(), ProcessHandle.current().pid(), RunStatus.RUNNING, now())
            store.put(c, session.id, Kind.RUN, run.key, run)
            return ActiveSession(store, c, session, run, resumed, clock)
        } catch (e: Exception) {
            c.close()
            throw e
        }
    }

    /** A new session must not start while an earlier one has left constraints, indexes or triggers out of place. */
    private fun refuseWhileObjectsDropped(c: Connection) {
        val (session, count) = c.prepareStatement(
            """
            select session_id, count(*) from ${store.table.sql}
            where kind = '${Kind.OBJECT}' and payload->>'status' in ('DROPPED', 'PENDING')
            group by session_id order by min(updated_at) limit 1
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getLong(2) else return }
        }
        val job = store.get<SessionInfo>(c, session, Kind.SESSION, "")?.jobFile ?: "?"
        throw StateException(
            "session $session (job $job) still has $count constraint(s)/index(es)/trigger(s) dropped or disabled on " +
                "the target; restore them first: pumplet-onegin recover --config <job.yaml> --session ${session.take(8)}"
        )
    }

    /** Runs left RUNNING by a process that died. Only called while holding the lock, so none of them is live. */
    private fun markInterrupted(c: Connection) {
        c.prepareStatement(
            """
            update ${store.table.sql}
            set payload = payload || jsonb_build_object('status', 'INTERRUPTED', 'error', cast(? as text)),
                updated_at = now()
            where kind = '${Kind.RUN}' and payload->>'status' = 'RUNNING'
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, INTERRUPTED_MESSAGE)
            ps.executeUpdate()
        }
    }

    private fun now() = Instant.now(clock)

    companion object {
        const val INTERRUPTED_MESSAGE = "the process ended without finishing (killed, crashed or lost its connection)"

        private fun hostName(): String =
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: System.getenv("HOSTNAME") ?: "unknown"
    }
}

/**
 * A run in progress. Holds the target lock until [close]. Record the outcome
 * with [succeed] or [fail]; closing without either records the run as interrupted.
 */
class ActiveSession internal constructor(
    val store: StateStore,
    /** Dedicated connection that holds the lock; autocommit. Use it for state writes outside data transactions. */
    private val conn: Connection,
    session: SessionInfo,
    run: RunInfo,
    /** True if this run continues an unfinished session rather than starting one. */
    val resumed: Boolean,
    private val clock: Clock,
) : AutoCloseable {
    var session: SessionInfo = session
        private set
    var run: RunInfo = run
        private set

    val id: String get() = session.id

    /** Runs one stage and records its start, end and outcome. */
    fun <T> stage(stage: Stage, summary: (T) -> String? = { null }, block: () -> T): T {
        val started = StageInfo(stage.label, StageStatus.RUNNING, now())
        store.put(conn, id, Kind.STAGE, stage.label, started)
        val furthest = session.stage?.let { Stage.of(it) }
        if (furthest == null || stage.ordinal > furthest.ordinal) {
            session = session.copy(stage = stage.label)
            store.put(conn, id, Kind.SESSION, "", session)
        }
        val result = try {
            block()
        } catch (e: Exception) {
            store.put(conn, id, Kind.STAGE, stage.label, started.copy(status = StageStatus.FAILED, endedAt = now(), error = message(e)))
            throw e
        }
        store.put(conn, id, Kind.STAGE, stage.label, started.copy(status = StageStatus.DONE, endedAt = now(), summary = summary(result)))
        return result
    }

    /** Writes any state record through the lock connection. Safe to call from several threads. */
    @Synchronized
    fun put(kind: String, key: String, payload: Any) = store.put(conn, id, kind, key, payload)

    /** Marks the whole job finished; the next command for this job starts a new session. */
    fun completeSession() {
        session = session.copy(status = SessionStatus.COMPLETED, completedAt = now())
        store.put(conn, id, Kind.SESSION, "", session)
    }

    fun succeed() = finish(RunStatus.SUCCEEDED, null)

    fun fail(error: String) = finish(RunStatus.FAILED, error)

    override fun close() {
        try {
            if (run.status == RunStatus.RUNNING) finish(RunStatus.INTERRUPTED, "stopped before finishing")
            TargetLock.unlock(conn, store.table)
        } finally {
            conn.close()
        }
    }

    private fun finish(status: RunStatus, error: String?) {
        run = run.copy(status = status, endedAt = now(), error = error)
        store.put(conn, id, Kind.RUN, run.key, run)
    }

    private fun message(e: Exception) = e.message ?: e.javaClass.simpleName

    private fun now() = Instant.now(clock)
}
