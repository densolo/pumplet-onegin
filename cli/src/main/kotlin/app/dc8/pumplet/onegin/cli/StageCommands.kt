package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.stages.DisableStage
import app.dc8.pumplet.onegin.stages.EnableStage
import app.dc8.pumplet.onegin.stages.Event
import app.dc8.pumplet.onegin.stages.EventSink
import app.dc8.pumplet.onegin.stages.LoggingEventSink
import app.dc8.pumplet.onegin.stages.RestoreRunner
import app.dc8.pumplet.onegin.stages.StageException
import app.dc8.pumplet.onegin.stages.TargetObjects
import app.dc8.pumplet.onegin.stages.describeObjects
import app.dc8.pumplet.onegin.state.ActiveSession
import app.dc8.pumplet.onegin.state.ObjectKind
import app.dc8.pumplet.onegin.state.ObjectStatus
import app.dc8.pumplet.onegin.state.SavedObject
import app.dc8.pumplet.onegin.state.SessionChoice
import app.dc8.pumplet.onegin.state.SessionManager
import app.dc8.pumplet.onegin.state.StateException
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/** A command that runs inside a session: opens a run, prints progress, records the outcome. */
abstract class SessionCommand(name: String, io: Io, loader: ConfigLoader) : JobCommand(name, io, loader) {
    protected open fun choice(): SessionChoice = SessionChoice.StartOrResume

    /** Set by commands that copy, to show table progress. */
    protected var progress: ProgressView? = null

    /** Does the work; returns the run's error message, or null if it succeeded. */
    protected abstract fun work(config: JobConfig, session: ActiveSession, events: EventSink): String?

    final override fun execute(config: JobConfig): Int {
        SessionManager(config).begin(commandName, choice()).use { session ->
            val how = if (session.resumed) "continuing session" else "new session"
            io.out.println("$how ${session.id.take(8)}, run #${session.run.seq} ($commandName)")
            log.info("{} {}, run #{}", how, session.id, session.run.seq)
            val error = try {
                work(config, session, LoggingEventSink(::print))
            } catch (e: StageException) {
                e.message
            } catch (e: StateException) {
                e.message
            } catch (e: java.sql.SQLException) {
                log.error("$commandName failed", e)
                config.redactor.scrub(e.message)
            } catch (e: Exception) {
                log.error("$commandName failed", e)
                config.redactor.scrub(e.toString())
            }
            error?.let { log.error("{} failed: {}", commandName, it) }
            runCatching { progress?.close() }
            if (io.cancel.get()) {
                // Closing without an outcome records the run as INTERRUPTED.
                io.err.println("stopped; run the same command again to continue")
                return Exit.INTERRUPTED
            }
            if (error == null) {
                session.succeed()
                return Exit.OK
            }
            session.fail(error)
            io.err.println("error: $error")
            return Exit.FAILED
        }
    }

    private fun print(e: Event) {
        val out = io.out
        when (e) {
            is Event.StageStarted -> out.println("${e.stage.label}...")
            is Event.StageFinished -> out.println("${e.stage.label}: ${e.summary ?: "done"}")
            is Event.ObjectChanged -> out.println("  ${verb(e.obj)} ${kind(e.obj.kind).padEnd(11)} ${e.obj.table}.${e.obj.name}${e.obj.error?.let { ": $it" } ?: ""}")
            is Event.Info -> out.println(e.message)
            is Event.Warning -> io.err.println("warning: ${e.message}")
            is Event.TableStarted, is Event.ChunkProgress, is Event.ChunkDone,
            is Event.ChunkFailed, is Event.TableFinished -> progress?.onEvent(e)
        }
    }

    private fun verb(o: SavedObject) = when (o.status) {
        ObjectStatus.DROPPED -> if (o.kind == ObjectKind.TRIGGER) "disabled" else "dropped "
        ObjectStatus.RESTORED -> "restored"
        ObjectStatus.PENDING -> "PENDING "
        ObjectStatus.SKIPPED -> "skipped "
    }

    private fun kind(k: ObjectKind) = when (k) {
        ObjectKind.FOREIGN_KEY -> "foreign key"
        ObjectKind.INDEX -> "index"
        ObjectKind.TRIGGER -> "trigger"
    }

    /** Explains pending objects and how to deal with them; returns the run's error message. */
    protected fun reportPending(pending: List<SavedObject>): String {
        io.err.println()
        io.err.println("${describeObjects(pending)} could not be restored:")
        pending.forEach { io.err.println("  ${it.table}.${it.name}: ${it.error}") }
        io.err.println("fix the data and run `enable` again (it retries only these), or give up on them with `enable --skip-pending`")
        return "${pending.size} object(s) pending"
    }
}

class DisableCommand(io: Io, loader: ConfigLoader) : SessionCommand("disable", io, loader) {
    override fun help(context: Context) =
        "Diff, then save and drop foreign keys / secondary indexes and disable user triggers on the target " +
            "tables, as set under 'constraints:'. Starts or continues this job's session."

    override fun work(config: JobConfig, session: ActiveSession, events: EventSink): String? {
        DisableStage(config, events).run(session)
        return null
    }
}

class EnableCommand(io: Io, loader: ConfigLoader) : SessionCommand("enable", io, loader) {
    override fun help(context: Context) =
        "Restore everything `disable` dropped or disabled: indexes, then foreign keys (added NOT VALID, then " +
            "validated), then triggers. Retries only what is still pending."

    private val skipPending by option(
        "--skip-pending", help = "give up on objects that failed to restore before; prints the SQL to restore them by hand",
    ).flag()

    override fun choice() = SessionChoice.ResumeOnly

    override fun work(config: JobConfig, session: ActiveSession, events: EventSink): String? {
        val r = EnableStage(config, events).run(session, skipPending)
        printSkipped(r.restore.skipped)
        if (r.restore.pending.isNotEmpty()) return reportPending(r.restore.pending)
        if (r.sessionCompleted) io.out.println("session ${session.id.take(8)} completed")
        return null
    }

    private fun printSkipped(skipped: List<SavedObject>) {
        if (skipped.isEmpty()) return
        io.out.println()
        io.out.println("skipped; to restore them by hand once the data allows:")
        skipped.forEach { io.out.println("  ${TargetObjects.manualRestoreSql(it)}") }
    }
}

class RecoverCommand(io: Io, loader: ConfigLoader) : SessionCommand("recover", io, loader) {
    override fun help(context: Context) =
        "After a crash or a failed run: restore every constraint, index and trigger a session left dropped or " +
            "disabled, without copying. Works on this job's open session, or any session with --session."

    private val session by option("--session", metavar = "<id>", help = "session to recover (id or unique prefix), e.g. another job's")

    override fun choice(): SessionChoice = session?.let { SessionChoice.Existing(it) } ?: SessionChoice.ResumeOnly

    override fun work(config: JobConfig, session: ActiveSession, events: EventSink): String? {
        val runner = RestoreRunner(config, events)
        val r = runner.run(session, skipPending = false)
        io.out.println("recover: ${runner.summary(r)}")
        if (r.pending.isNotEmpty()) return reportPending(r.pending)
        return null
    }
}
