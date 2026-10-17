package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.copy.EngineResult
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.stages.Event
import app.dc8.pumplet.onegin.stages.CopyStage
import app.dc8.pumplet.onegin.stages.EventSink
import app.dc8.pumplet.onegin.stages.FetchTest
import app.dc8.pumplet.onegin.stages.LoggingEventSink
import app.dc8.pumplet.onegin.stages.RunStage
import app.dc8.pumplet.onegin.stages.StageException
import app.dc8.pumplet.onegin.state.ActiveSession
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.RunReport
import com.github.ajalt.clikt.core.Context

/** Runs a stage that may fail as a whole; returns its error, so the report can still be printed. */
private fun stageError(block: () -> Unit): String? = try {
    block()
    null
} catch (e: StageException) {
    e.message
}

class CopyCommand(io: Io, loader: ConfigLoader) : SessionCommand("copy", io, loader) {
    override fun help(context: Context) =
        "Copy the data of every table the diff marks COPY. Resumes this job's session: only chunks that " +
            "aren't committed yet are copied. Run `disable` first (or use `run`)."

    override fun work(config: JobConfig, session: ActiveSession, events: EventSink): String? {
        progress = ProgressView.create(io)
        var result: CopyStage.Result? = null
        val error = stageError { result = CopyStage(config, events, io.cancel).run(session) }
        progress?.close()
        printReport(config, session, result?.engine)
        if (error == null) io.out.println("next: `enable` restores constraints, indexes and triggers")
        return error
    }

    /** The engine result when the stage returned; otherwise the report the stage stored before failing. */
    private fun printReport(config: JobConfig, session: ActiveSession, engine: EngineResult?) {
        if (engine != null) return printEngineReport(io, "copy report (this run):", engine)
        val stored = Db.connect(config.target, Role.TARGET).use {
            session.store.get(it, session.id, Kind.REPORT, "", RunReport::class.java)
        }
        if (stored != null && stored.runSeq == session.run.seq) printStoredReport(io, stored)
    }
}

class RunCommand(io: Io, loader: ConfigLoader) : SessionCommand("run", io, loader) {
    override fun help(context: Context) =
        "diff, disable, copy and enable in one go. Stops at the first stage that fails; run it again to " +
            "resume from where it stopped."

    override fun work(config: JobConfig, session: ActiveSession, events: EventSink): String? {
        progress = ProgressView.create(io)
        var result: RunStage.Result? = null
        val error = stageError { result = RunStage(config, events, io.cancel).run(session) }
        progress?.close()
        result?.let { r ->
            printEngineReport(io, "copy report (this run):", r.copy.engine)
            if (r.enable.restore.pending.isNotEmpty()) return reportPending(r.enable.restore.pending)
            if (r.enable.sessionCompleted) io.out.println("session ${session.id.take(8)} completed")
        }
        return error
    }
}

class FetchTestCommand(io: Io, loader: ConfigLoader) : JobCommand("fetch-test", io, loader) {
    override fun help(context: Context) =
        "Read-only speed test: read the selected tables from the source with the same chunking and readers " +
            "as `copy`, discard the data, and report rows, MB and rates. Never touches the target."

    override fun execute(config: JobConfig): Int {
        io.out.println("fetch-test: reading from ${Db.describe(config.source)} " +
            "with ${config.threads.readers} reader(s); nothing is written anywhere")
        val view = ProgressView.create(io)
        val result = try {
            FetchTest(config, LoggingEventSink { e ->
                when (e) {
                    is Event.Warning -> io.err.println("warning: ${e.message}")
                    else -> view.onEvent(e)
                }
            }, io.cancel).run()
        } finally {
            view.close()
        }
        printEngineReport(io, "fetch-test report:", result)
        if (io.cancel.get()) return Exit.INTERRUPTED
        return if (result.failed.isEmpty()) Exit.OK else Exit.FAILED
    }
}

/** A report read back from the state table (e.g. after a failed copy). */
fun printStoredReport(io: Io, r: RunReport) {
    io.out.println()
    io.out.println("copy report (this run): %,d rows, %s in %s".format(r.totalRows, StatusCommand.mb(r.totalBytes), StatusCommand.ms(r.durationMs)))
    val w = r.tables.maxOfOrNull { it.table.length } ?: 0
    for (t in r.tables) {
        io.out.println("  ${t.table.padEnd(w)}  %,12d rows  %10s  %8s".format(t.rows, StatusCommand.mb(t.bytes), StatusCommand.ms(t.durationMs)))
    }
}
