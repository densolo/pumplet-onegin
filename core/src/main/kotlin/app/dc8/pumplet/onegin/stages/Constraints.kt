package app.dc8.pumplet.onegin.stages

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.db.SqlError
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.schema.Differ
import app.dc8.pumplet.onegin.schema.TableAction
import app.dc8.pumplet.onegin.state.ActiveSession
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.ObjectKind
import app.dc8.pumplet.onegin.state.ObjectStatus
import app.dc8.pumplet.onegin.state.SavedObject
import app.dc8.pumplet.onegin.state.Stage
import app.dc8.pumplet.onegin.state.StageStatus
import app.dc8.pumplet.onegin.state.StageInfo
import app.dc8.pumplet.onegin.state.list
import app.dc8.pumplet.onegin.state.get

/** A stage refused to run or failed as a whole; the message says why and what to do. */
class StageException(message: String) : RuntimeException(message)

/** Counts per kind, e.g. "14 foreign keys, 3 triggers". */
fun describeObjects(objects: List<SavedObject>): String {
    if (objects.isEmpty()) return "nothing"
    return ObjectKind.entries.mapNotNull { k ->
        val n = objects.count { it.kind == k }
        if (n == 0) null else "$n " + when (k) {
            ObjectKind.FOREIGN_KEY -> if (n == 1) "foreign key" else "foreign keys"
            ObjectKind.INDEX -> if (n == 1) "index" else "indexes"
            ObjectKind.TRIGGER -> if (n == 1) "trigger" else "triggers"
        }
    }.joinToString(", ")
}

/** The diff stage inside a session: compares, records the result, refuses on blocking errors. */
fun diffStage(config: JobConfig, session: ActiveSession, events: EventSink): DiffReport {
    events.emit(Event.StageStarted(Stage.DIFF))
    val report = session.stage(Stage.DIFF, summary = { r: DiffReport -> diffSummary(r) }) {
        val r = withoutOwnDrops(config, Differ(config).run(), session)
        if (r.blocking) {
            throw StageException("the diff has ${r.errors} blocking error(s); run `diff` to see them and fix them first")
        }
        r
    }
    events.emit(Event.StageFinished(Stage.DIFF, diffSummary(report)))
    return report
}

/** Inside a session, the foreign keys and indexes `disable` itself dropped aren't differences worth a warning. */
private fun withoutOwnDrops(config: JobConfig, r: DiffReport, session: ActiveSession): DiffReport {
    val own = Db.connect(config.target, Role.TARGET).use { session.store.list<SavedObject>(it, session.id, Kind.OBJECT) }
        .filter { it.status.outstanding && it.kind != ObjectKind.TRIGGER }
        .map { it.table to it.name }
        .toSet()
    if (own.isEmpty()) return r
    return r.copy(findings = r.findings.filterNot { (it.table to it.subject) in own && it.message.contains("missing in target") })
}

private fun diffSummary(r: DiffReport) =
    "${r.tables.count { it.action == TableAction.COPY }} table(s) to copy, ${r.warnings} warning(s)"

/**
 * `disable`: diff, then drop foreign keys / secondary indexes and disable user
 * triggers on the target tables that will be copied, as configured.
 */
class DisableStage(private val config: JobConfig, private val events: EventSink = EventSink.NONE) {
    data class Result(val dropped: List<SavedObject>, val alreadyDropped: Int, val diff: DiffReport)

    /** @param diff a diff already made in this run (by `run`); otherwise the diff stage runs first. */
    fun run(session: ActiveSession, diff: DiffReport? = null): Result {
        val report = diff ?: diffStage(config, session, events)
        val tables = report.tables.filter { it.action == TableAction.COPY }.map { it.table }
        Db.connect(config.target, Role.TARGET).use { c ->
            if (!copyStarted(c, session)) refuseNonEmpty(c, tables)
            events.emit(Event.StageStarted(Stage.DISABLE))
            val result = session.stage(Stage.DISABLE, summary = { r: Result -> summary(r) }) {
                val objects = TargetObjects(c, session.store, session.id, config.redactor, events)
                val k = config.constraints
                val found = objects.find(tables, k.dropForeignKeys, k.dropSecondaryIndexes, k.disableUserTriggers)
                found.forEach { objects.drop(it) }
                val earlier = session.store.list<SavedObject>(c, session.id, Kind.OBJECT).count { it.status == ObjectStatus.DROPPED } - found.size
                c.commit()
                Result(found, earlier.coerceAtLeast(0), report)
            }
            events.emit(Event.StageFinished(Stage.DISABLE, summary(result)))
            return result
        }
    }

    private fun summary(r: Result) = buildString {
        append(if (r.dropped.isEmpty()) "nothing to drop" else describeObjects(r.dropped) + " dropped/disabled")
        if (r.alreadyDropped > 0) append(" (${r.alreadyDropped} already out of place from an earlier run)")
    }

}

/** True once this session's copy has started: from then on, rows in the target may be its own. */
fun copyStarted(c: java.sql.Connection, session: ActiveSession): Boolean =
    session.store.get<StageInfo>(c, session.id, Kind.STAGE, Stage.COPY.label) != null

/**
 * A new session never writes into tables that already hold data. The tool never empties
 * them itself: the user decides, and the message gives the statement to do it.
 */
fun refuseNonEmpty(c: java.sql.Connection, tables: List<String>) {
    val nonEmpty = tables.filter { t ->
        val sql = "select exists (select 1 from ${Sql.table(t)})"
        SqlError.on(c, Role.TARGET, "table $t (checking it is empty)", sql) {
            c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getBoolean(1) } }
        }
    }
    if (nonEmpty.isEmpty()) return
    throw StageException(
        "these target tables already have rows: ${nonEmpty.joinToString(", ")}; a new session only copies into " +
            "empty tables. Empty them yourself if that's intended, e.g.: truncate ${nonEmpty.joinToString(", ") { Sql.table(it) }};"
    )
}

/**
 * Restores what `disable` took away: indexes first, then foreign keys (added
 * NOT VALID, then validated), then triggers. Used by `enable` and `recover`.
 */
class RestoreRunner(private val config: JobConfig, private val events: EventSink = EventSink.NONE) {
    data class Result(val restored: List<SavedObject>, val pending: List<SavedObject>, val skipped: List<SavedObject>, val nothingToDo: Boolean)

    /** @param skipPending give up on objects that failed before (record them SKIPPED) instead of retrying them. */
    fun run(session: ActiveSession, skipPending: Boolean): Result =
        Db.connect(config.target, Role.TARGET).use { c ->
            val objects = TargetObjects(c, session.store, session.id, config.redactor, events)
            val saved = session.store.list<SavedObject>(c, session.id, Kind.OBJECT)
            c.commit()
            val order = listOf(ObjectKind.INDEX, ObjectKind.FOREIGN_KEY, ObjectKind.TRIGGER)
            val todo = saved.filter { it.status.outstanding }.sortedBy { order.indexOf(it.kind) }
            val skipped = if (skipPending) todo.filter { it.status == ObjectStatus.PENDING }.map { objects.skip(it) } else emptyList()
            val results = todo.filter { !(skipPending && it.status == ObjectStatus.PENDING) }.map { objects.restore(it) }
            Result(
                restored = results.filter { it.status == ObjectStatus.RESTORED },
                pending = results.filter { it.status == ObjectStatus.PENDING },
                skipped = skipped,
                nothingToDo = todo.isEmpty(),
            )
        }

    fun summary(r: Result) = buildString {
        append(if (r.nothingToDo) "nothing to restore" else describeObjects(r.restored) + " restored")
        if (r.pending.isNotEmpty()) append("; ${describeObjects(r.pending)} pending")
        if (r.skipped.isNotEmpty()) append("; ${describeObjects(r.skipped)} skipped")
    }
}

/** `enable`: restore everything, as a stage; completes the session once the copy is done and nothing is pending. */
class EnableStage(private val config: JobConfig, private val events: EventSink = EventSink.NONE) {
    data class Result(val restore: RestoreRunner.Result, val sessionCompleted: Boolean)

    fun run(session: ActiveSession, skipPending: Boolean): Result {
        val runner = RestoreRunner(config, events)
        events.emit(Event.StageStarted(Stage.ENABLE))
        val r = session.stage(Stage.ENABLE, summary = { r: RestoreRunner.Result -> runner.summary(r) }) {
            runner.run(session, skipPending)
        }
        events.emit(Event.StageFinished(Stage.ENABLE, runner.summary(r)))
        if (r.pending.isNotEmpty()) return Result(r, false)

        val copyDone = Db.connect(config.target, Role.TARGET).use { c ->
            session.store.get<StageInfo>(c, session.id, Kind.STAGE, Stage.COPY.label)?.status == StageStatus.DONE
        }
        if (copyDone) {
            session.completeSession()
        } else {
            events.emit(Event.Warning("the copy hasn't finished in this session, so it stays open; `copy` will resume it"))
        }
        return Result(r, copyDone)
    }
}
