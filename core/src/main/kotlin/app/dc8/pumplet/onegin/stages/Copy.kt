package app.dc8.pumplet.onegin.stages

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.config.LargeObjectMode
import app.dc8.pumplet.onegin.config.TableFilter
import app.dc8.pumplet.onegin.copy.ChunkPlanner
import app.dc8.pumplet.onegin.copy.CopyEngine
import app.dc8.pumplet.onegin.copy.DiscardSink
import app.dc8.pumplet.onegin.copy.EngineResult
import app.dc8.pumplet.onegin.copy.RowTransformer
import app.dc8.pumplet.onegin.copy.SequenceSyncResult
import app.dc8.pumplet.onegin.copy.Sequences
import app.dc8.pumplet.onegin.copy.TableJob
import app.dc8.pumplet.onegin.copy.TableOutcome
import app.dc8.pumplet.onegin.copy.TargetSink
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.schema.CatalogReader
import app.dc8.pumplet.onegin.schema.ColumnPlan
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.schema.TableAction
import app.dc8.pumplet.onegin.schema.TablePlan
import app.dc8.pumplet.onegin.state.ActiveSession
import app.dc8.pumplet.onegin.state.ChunkDone
import app.dc8.pumplet.onegin.state.ChunkPlan
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.RunReport
import app.dc8.pumplet.onegin.state.Stage
import app.dc8.pumplet.onegin.state.TableProgress
import app.dc8.pumplet.onegin.state.TableStatus
import app.dc8.pumplet.onegin.state.TableTiming
import app.dc8.pumplet.onegin.state.get
import app.dc8.pumplet.onegin.state.list
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An open source transaction whose snapshot every reader imports, so all chunks of
 * all tables see the source at one moment. Stays open until [close].
 */
class SourceSnapshot(config: JobConfig) : AutoCloseable {
    val connection: Connection = Db.connect(config.source, Role.SOURCE)
    val id: String

    init {
        connection.autoCommit = true
        connection.createStatement().use {
            it.execute("begin isolation level repeatable read read only")
            id = it.executeQuery("select pg_export_snapshot()").use { rs -> rs.next(); rs.getString(1) }
        }
    }

    override fun close() {
        runCatching { connection.createStatement().use { it.execute("commit") } }
        connection.close()
    }
}

/** `copy`: copies every table the diff marks COPY, resuming whatever earlier runs of the session left. */
class CopyStage(
    private val config: JobConfig,
    private val events: EventSink = EventSink.NONE,
    private val cancel: AtomicBoolean = AtomicBoolean(),
    private val transformer: RowTransformer = RowTransformer.NONE,
) {
    /** [sequences] is null when the option is off or the copy didn't finish. */
    data class Result(val engine: EngineResult, val report: RunReport, val sequences: SequenceSyncResult? = null)

    /** @param diff a diff already made in this run (by `run`); otherwise the diff stage runs first. */
    fun run(session: ActiveSession, diff: DiffReport? = null): Result {
        val report = diff ?: diffStage(config, session, events)
        val plans = report.tables.filter { it.action == TableAction.COPY }
        val resuming = Db.connect(config.target, Role.TARGET).use { c ->
            val started = copyStarted(c, session)
            if (!started) refuseNonEmpty(c, plans.map { it.table })
            checkForeignKeys(c, plans.map { it.table })
            started
        }
        if (resuming) {
            events.emit(Event.Warning(
                "resuming the copy: this run reads a new snapshot of the source, so chunks copied by earlier runs " +
                    "may reflect an earlier state of the source than the rest"
            ))
        }
        events.emit(Event.StageStarted(Stage.COPY))
        val result = session.stage(Stage.COPY, summary = { r: Result -> summary(r) }) {
            copy(session, plans, resuming).also { r ->
                if (r.engine.cancelled) throw StageException("the copy was stopped before finishing; run `copy` again to resume it")
                if (r.engine.failed.isNotEmpty()) {
                    throw StageException(
                        "${r.engine.failed.size} table(s) failed: ${r.engine.failed.joinToString("; ") { "${it.table}: ${it.error}" }}; " +
                            "run `copy` again to retry what is missing"
                    )
                }
            }
        }
        events.emit(Event.StageFinished(Stage.COPY, summary(result)))
        return result
    }

    private fun copy(session: ActiveSession, plans: List<TablePlan>, resuming: Boolean): Result {
        val t = config.threads
        SourceSnapshot(config).use { snap ->
            Db.pool(config.source, Role.SOURCE, t.readers, "source", "SET standard_conforming_strings = on").use { sourcePool ->
                Db.pool(config.target, Role.TARGET, t.writers, "target", "SET synchronous_commit = off").use { targetPool ->
                    val jobs = jobs(session, snap.connection, targetPool, plans, resuming)
                    val sink = TargetSink(targetPool, session.store, session.id, session.run.seq, transformer)
                    val engine = CopyEngine(
                        sourcePool, snap.id, t.readers, t.writers, config.buffer.maxMemoryMb,
                        sink, config.redactor, recordingTables(session), cancel,
                    ).run(jobs)
                    val runReport = RunReport(
                        runSeq = session.run.seq,
                        tables = engine.tables.filter { it.rows > 0 || it.outcome == TableOutcome.DONE }
                            .map { TableTiming(it.table, it.rows, it.bytes, it.durationMs) },
                        stageDurationsMs = mapOf(Stage.COPY.label to engine.durationMs),
                        totalRows = engine.tables.sumOf { it.rows },
                        totalBytes = engine.tables.sumOf { it.bytes },
                        durationMs = engine.durationMs,
                    )
                    session.put(Kind.REPORT, "", runReport)
                    val sequences = if (config.sequences.raiseToSource && engine.failed.isEmpty() && !engine.cancelled) {
                        targetPool.connection.use { syncSequences(snap.connection, it) }
                    } else null
                    return Result(engine, runReport, sequences)
                }
            }
        }
    }

    /** Chunk plans are made once per session and reused on resume, so chunk N always means the same rows. */
    private fun jobs(
        session: ActiveSession,
        snapshot: Connection,
        targetPool: javax.sql.DataSource,
        plans: List<TablePlan>,
        resuming: Boolean,
    ): List<TableJob> {
        val planner = ChunkPlanner(snapshot, config.chunking)
        val (savedPlans, done) = targetPool.connection.use { c ->
            session.store.list<ChunkPlan>(c, session.id, Kind.CHUNK_PLAN).associateBy { it.table } to
                session.store.list<ChunkDone>(c, session.id, Kind.CHUNK).groupBy { it.table }
        }
        return plans.map { plan ->
            val chunks = savedPlans[plan.table] ?: planner.plan(plan.table, plan.primaryKey, plan.primaryKeyTypes, plan.estRows)
                .also { session.put(Kind.CHUNK_PLAN, plan.table, it) }
            val doneHere = done[plan.table].orEmpty().associateBy { it.index }
            TableJob(
                plan, chunks, doneHere,
                truncateFirst = resuming && plan.primaryKey.isEmpty() && doneHere.isEmpty(),
                // A row transformer sees rows one by one, so it keeps the row path.
                largeObjectsInBulk = config.largeObjects.mode == LargeObjectMode.BULK && transformer === RowTransformer.NONE,
            )
        }
    }

    /** Raises target sequences that are behind the source (never lowers them), and reports what it did. */
    private fun syncSequences(source: Connection, target: Connection): SequenceSyncResult {
        target.autoCommit = true
        val r = Sequences.raiseToSource(source, target, config.source.schemas)
        r.raised.forEach { (name, v) -> events.emit(Event.Info("  sequence $name raised to ${v.second} (was ${v.first ?: "unused"})")) }
        val unreadable = r.skipped.filterValues { it.startsWith("no SELECT") }.keys
        if (unreadable.isNotEmpty()) {
            val schemas = unreadable.map { it.substringBefore('.') }.distinct()
            events.emit(Event.Warning(
                "can't read ${unreadable.size} source sequence(s) (${unreadable.joinToString(", ")}), so they weren't raised; " +
                    "the source user needs: " + schemas.joinToString(" ") { "grant select on all sequences in schema ${Sql.ident(it)} to <user>;" }
            ))
        }
        events.emit(Event.Info("sequences: ${r.summary}"))
        return r
    }

    /** Keeps the table rows of the state current (RUNNING / FAILED) as the engine reports progress. */
    private fun recordingTables(session: ActiveSession) = EventSink { e ->
        when (e) {
            is Event.TableStarted -> session.put(Kind.TABLE, e.table, TableProgress(
                e.table, TableStatus.RUNNING, e.estRows, e.rowsDone, 0, e.chunks, e.chunksDone, startedAt = Instant.now(),
            ))
            is Event.TableFinished -> if (!e.ok) session.put(Kind.TABLE, e.table, TableProgress(
                e.table, TableStatus.FAILED, rows = e.rows, bytes = e.bytes, endedAt = Instant.now(), error = e.error,
            ))
            else -> Unit
        }
        events.emit(e)
    }

    /** Copying with foreign keys in place fails as soon as a child row arrives before its parent. */
    private fun checkForeignKeys(c: Connection, tables: List<String>) {
        val withFks = c.prepareStatement(
            """
            select distinct n.nspname || '.' || c.relname
            from pg_constraint con join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace
            where con.contype = 'f' and n.nspname || '.' || c.relname = any(?)
            order by 1
            """.trimIndent()
        ).use { ps ->
            ps.setArray(1, c.createArrayOf("text", tables.toTypedArray()))
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        if (withFks.isEmpty()) return
        if (config.constraints.dropForeignKeys) {
            throw StageException("foreign keys are still in place on ${withFks.joinToString(", ")}; run `disable` first (or use `run`)")
        }
        events.emit(Event.Warning(
            "foreign keys stay in place (constraints.dropForeignKeys: false) on ${withFks.joinToString(", ")}: " +
                "rows that arrive before the rows they reference will fail"
        ))
    }

    private fun summary(r: Result): String {
        val done = r.engine.tables.count { it.outcome == TableOutcome.DONE }
        return "$done of ${r.engine.tables.size} table(s) done, %,d rows, %.1f MB in this run".format(
            r.report.totalRows, r.report.totalBytes / 1024.0 / 1024.0,
        ) + (r.sequences?.let { "; ${it.raised.size} sequence(s) raised" } ?: "")
    }
}

/** `run`: diff, disable, copy and enable in one go. Stops at the first stage that fails. */
class RunStage(
    private val config: JobConfig,
    private val events: EventSink = EventSink.NONE,
    private val cancel: AtomicBoolean = AtomicBoolean(),
    private val transformer: RowTransformer = RowTransformer.NONE,
) {
    data class Result(val copy: CopyStage.Result, val enable: EnableStage.Result)

    fun run(session: ActiveSession): Result {
        val diff = diffStage(config, session, events)
        DisableStage(config, events).run(session, diff)
        val copy = CopyStage(config, events, cancel, transformer).run(session, diff)
        val enable = EnableStage(config, events).run(session, skipPending = false)
        return Result(copy, enable)
    }
}

/**
 * `fetch-test`: reads the selected tables from the source with the same chunking and
 * reader pool as `copy`, throws the data away, and reports the speed. Never connects
 * to the target and writes no state.
 */
class FetchTest(
    private val config: JobConfig,
    private val events: EventSink = EventSink.NONE,
    private val cancel: AtomicBoolean = AtomicBoolean(),
) {
    fun run(): EngineResult {
        val t = config.threads
        SourceSnapshot(config).use { snap ->
            val catalog = CatalogReader.read(snap.connection, config.source.schemas)
            config.source.schemas.filter { it !in catalog.schemas }.forEach { events.emit(Event.Warning("schema $it does not exist in source")) }
            val filter = TableFilter(config.tables)
            val tables = catalog.tables.values
                .filter { it.qualified != config.state.table.qualified && filter.matches(it.schema, it.name) }
                .sortedBy { it.qualified }
            tables.filter { !it.readable }.forEach { events.emit(Event.Warning("${it.qualified}: no SELECT privilege, skipped")) }
            val planner = ChunkPlanner(snap.connection, config.chunking)
            val jobs = tables.filter { it.readable }.map { tbl ->
                val plan = TablePlan(
                    table = tbl.qualified, action = TableAction.COPY, reason = null,
                    columns = tbl.columns.map { ColumnPlan(it.name, it.type, it.type, false, false, false) },
                    primaryKey = tbl.primaryKey, primaryKeyTypes = tbl.primaryKey.map { tbl.column(it)!!.type },
                    estRows = tbl.estRows, chunks = null,
                )
                TableJob(plan, planner.plan(plan.table, plan.primaryKey, plan.primaryKeyTypes, plan.estRows))
            }
            Db.pool(config.source, Role.SOURCE, t.readers, "source", "SET standard_conforming_strings = on").use { pool ->
                return CopyEngine(pool, snap.id, t.readers, t.readers, config.buffer.maxMemoryMb, DiscardSink, config.redactor, events, cancel).run(jobs)
            }
        }
    }
}
