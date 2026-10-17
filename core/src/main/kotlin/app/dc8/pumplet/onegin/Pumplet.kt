package app.dc8.pumplet.onegin

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.copy.EngineResult
import app.dc8.pumplet.onegin.copy.RowTransformer
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.schema.Differ
import app.dc8.pumplet.onegin.stages.CopyStage
import app.dc8.pumplet.onegin.stages.DisableStage
import app.dc8.pumplet.onegin.stages.EnableStage
import app.dc8.pumplet.onegin.stages.Event
import app.dc8.pumplet.onegin.stages.EventSink
import app.dc8.pumplet.onegin.stages.FetchTest
import app.dc8.pumplet.onegin.stages.LoggingEventSink
import app.dc8.pumplet.onegin.stages.RestoreRunner
import app.dc8.pumplet.onegin.stages.RunStage
import app.dc8.pumplet.onegin.stages.StageException
import app.dc8.pumplet.onegin.state.ActiveSession
import app.dc8.pumplet.onegin.state.SessionChoice
import app.dc8.pumplet.onegin.state.SessionManager
import app.dc8.pumplet.onegin.state.StateException
import app.dc8.pumplet.onegin.state.StatusReader
import app.dc8.pumplet.onegin.state.StatusReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The library API: one method per stage, for a front end other than the CLI.
 *
 * ```
 * val run = Pumplet(config).copy()
 * run.events.collect { event -> show(event) }   // runs the copy; returns when it ends
 * val result = run.result.await()               // or throws what the stage threw
 * ```
 *
 * Stages that write state open a run in this job's session (as the CLI commands
 * do) and record its outcome. Every event is also written to the log.
 */
class Pumplet(private val config: JobConfig) {
    /** Compare schemas. Read-only; no session. */
    fun diff(): StageRun<DiffReport> = StageRun { _, _ -> Differ(config).run() }

    fun disable(): StageRun<DisableStage.Result> =
        inSession("disable", SessionChoice.StartOrResume) { s, events, _ -> DisableStage(config, events).run(s) }

    fun copy(transformer: RowTransformer = RowTransformer.NONE): StageRun<CopyStage.Result> =
        inSession("copy", SessionChoice.StartOrResume) { s, events, cancel -> CopyStage(config, events, cancel, transformer).run(s) }

    fun enable(skipPending: Boolean = false): StageRun<EnableStage.Result> =
        inSession("enable", SessionChoice.ResumeOnly) { s, events, _ -> EnableStage(config, events).run(s, skipPending) }

    /** Restore what a session left dropped: this job's open session, or [sessionId] (any job's). */
    fun recover(sessionId: String? = null): StageRun<RestoreRunner.Result> =
        inSession("recover", sessionId?.let { SessionChoice.Existing(it) } ?: SessionChoice.ResumeOnly) { s, events, _ ->
            RestoreRunner(config, events).run(s, skipPending = false)
        }

    /** diff, disable, copy, enable. */
    fun run(transformer: RowTransformer = RowTransformer.NONE): StageRun<RunStage.Result> =
        inSession("run", SessionChoice.StartOrResume) { s, events, cancel -> RunStage(config, events, cancel, transformer).run(s) }

    /** Source read speed test; never touches the target. */
    fun fetchTest(): StageRun<EngineResult> = StageRun { events, cancel -> FetchTest(config, events, cancel).run() }

    /** Read the state table. Blocking and quick; no events. */
    fun status(sessionId: String? = null, all: Boolean = false, historyLimit: Int = 10): StatusReport =
        StatusReader(config).read(sessionId, all, historyLimit)

    /**
     * Runs [body] in a run of [command]: SUCCEEDED when it returns, FAILED with the
     * message when it throws, INTERRUPTED when it was cancelled.
     */
    private fun <T> inSession(
        command: String,
        choice: SessionChoice,
        body: (ActiveSession, EventSink, AtomicBoolean) -> T,
    ): StageRun<T> = StageRun { events, cancel ->
        SessionManager(config).begin(command, choice).use { session ->
            try {
                body(session, events, cancel).also { if (!cancel.get()) session.succeed() }
            } catch (e: Exception) {
                if (!cancel.get()) {
                    session.fail(
                        when (e) {
                            is StageException, is StateException -> e.message ?: e.javaClass.simpleName
                            else -> config.redactor.scrub(e.toString())
                        }
                    )
                }
                throw e
            }
        }
    }
}

/**
 * A stage, ready to run. Collecting [events] runs it (once) on [Dispatchers.IO] and
 * emits its events as they happen; the flow completes when the stage ends, and
 * [result] then holds its outcome. Cancelling the collector asks the stage to stop:
 * a copy rolls back its in-flight chunks first, so the flow completes only once the
 * state is consistent.
 */
class StageRun<T> internal constructor(private val body: (EventSink, AtomicBoolean) -> T) {
    private val started = AtomicBoolean()

    /** The stage's outcome: its result, or the exception it failed with. Completes when [events] does. */
    val result = CompletableDeferred<T>()

    val events: Flow<Event> = channelFlow {
        check(started.compareAndSet(false, true)) { "a StageRun runs once; ask Pumplet for a new one" }
        val cancel = AtomicBoolean()
        // Cancelling the collector cancels this scope; the watcher turns that into the stage's stop flag.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                cancel.set(true)
            }
        }
        val sink = LoggingEventSink { trySendBlocking(it) }
        try {
            // Blocking JDBC work; withContext returns only after it has finished (or rolled back).
            val r = withContext(Dispatchers.IO) { body(sink, cancel) }
            result.complete(r)
        } catch (e: Throwable) {
            result.completeExceptionally(e)
            // A stop the collector asked for is in [result]; it isn't the collector's error.
            if (!cancel.get()) throw e
        } finally {
            watcher.cancel()
        }
    }.buffer(Channel.UNLIMITED)
}
