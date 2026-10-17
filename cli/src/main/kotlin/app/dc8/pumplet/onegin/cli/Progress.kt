package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.copy.EngineResult
import app.dc8.pumplet.onegin.copy.TableOutcome
import app.dc8.pumplet.onegin.stages.Event
import com.github.ajalt.mordant.animation.progress.MultiProgressBarAnimation
import com.github.ajalt.mordant.animation.progress.ProgressTask
import com.github.ajalt.mordant.animation.progress.ThreadProgressAnimator
import com.github.ajalt.mordant.animation.progress.addTask
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.speed
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeRemaining
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Follows copy events and shows progress; [close] stops it before the final report is printed. */
abstract class ProgressView : AutoCloseable {
    protected class TableState(val name: String, val estRows: Long, val chunks: Int) {
        /** Exact rows of committed chunks (including earlier runs). */
        val doneRows = AtomicLong()
        /** Approximate rows streamed by chunks still in flight, per chunk. */
        val inFlight = ConcurrentHashMap<Int, Long>()
        val bytes = AtomicLong()
        @Volatile var chunksDone = 0
        @Volatile var finished: Boolean? = null
        val rows get() = doneRows.get() + inFlight.values.sum()
    }

    protected val tables = ConcurrentHashMap<String, TableState>()
    protected val startNanos = System.nanoTime()

    /** Updates the counters; subclasses then render. */
    open fun onEvent(e: Event) {
        when (e) {
            is Event.TableStarted -> tables[e.table] = TableState(e.table, e.estRows, e.chunks).also {
                it.doneRows.set(e.rowsDone)
                it.chunksDone = e.chunksDone
            }
            is Event.ChunkProgress -> tables[e.table]?.let { t ->
                t.inFlight.merge(e.index, e.rows, Long::plus)
                t.bytes.addAndGet(e.bytes)
            }
            is Event.ChunkDone -> tables[e.table]?.let { t ->
                t.inFlight.remove(e.index)
                t.doneRows.addAndGet(e.rows)
                t.chunksDone++
            }
            is Event.ChunkFailed -> tables[e.table]?.inFlight?.remove(e.index)
            is Event.TableFinished -> tables[e.table]?.finished = e.ok
            else -> Unit
        }
    }

    protected val totalRows get() = tables.values.sumOf { it.rows }
    protected val totalEst get() = tables.values.sumOf { maxOf(it.estRows, it.rows) }
    protected val totalBytes get() = tables.values.sumOf { it.bytes.get() }
    protected val elapsedSeconds get() = (System.nanoTime() - startNanos) / 1e9

    companion object {
        fun create(io: Io): ProgressView = if (io.interactive) BarsView() else LinesView(io)
    }
}

/** Interactive terminal: a bar per active table plus an overall bar. */
private class BarsView : ProgressView() {
    data class Ctx(val label: String, val mb: Double, val note: String)

    private val terminal = Terminal()
    private val layout = progressBarContextLayout<Ctx>(alignColumns = true) {
        text(align = TextAlign.LEFT) { context.label }
        percentage()
        progressBar()
        text { "%,d/~%,d rows".format(completed, total ?: 0) }
        text { "%.1f MB".format(context.mb) }
        speed(" rows/s")
        timeRemaining()
        text(align = TextAlign.LEFT) { context.note }
    }
    private val animation: ThreadProgressAnimator = MultiProgressBarAnimation(terminal).animateOnThread()
    private val future = animation.execute()
    private val tasks = ConcurrentHashMap<String, ProgressTask<Ctx>>()
    private val overall = animation.addTask(layout, Ctx("all tables", 0.0, ""), total = 1)

    override fun onEvent(e: Event) {
        super.onEvent(e)
        val table = when (e) {
            is Event.TableStarted -> e.table
            is Event.ChunkProgress -> e.table
            is Event.ChunkDone -> e.table
            is Event.ChunkFailed -> e.table
            is Event.TableFinished -> e.table
            else -> return
        }
        val t = tables[table] ?: return
        val task = tasks.computeIfAbsent(table) { animation.addTask(layout, Ctx(table, 0.0, ""), total = maxOf(t.estRows, 1)) }
        val note = when {
            t.finished == true -> "done"
            t.finished == false -> "FAILED"
            e is Event.ChunkFailed -> "retrying chunk ${e.index + 1}"
            t.chunks > 1 -> "chunk ${t.chunksDone}/${t.chunks}"
            else -> ""
        }
        task.update {
            completed = t.rows
            total = maxOf(t.estRows, t.rows, 1)
            context = Ctx(table, t.bytes.get() / MB, note)
        }
        overall.update {
            completed = totalRows
            total = maxOf(totalEst, 1)
            context = Ctx("all tables", totalBytes / MB, "${tables.values.count { it.finished == true }}/${tables.size} tables done")
        }
    }

    override fun close() {
        animation.refresh(refreshAll = true)
        animation.stop()
        future.cancel(false)
    }
}

/** Not a terminal (piped, CI, log file): a line per finished table and a summary every [everySeconds]. */
private class LinesView(private val io: Io, private val everySeconds: Long = 10) : ProgressView() {
    @Volatile private var lastLine = System.nanoTime()

    override fun onEvent(e: Event) {
        super.onEvent(e)
        when (e) {
            is Event.TableFinished -> io.out.println(
                if (e.ok) "  done    ${e.table}: %,d rows, %s in %s".format(e.rows, StatusCommand.mb(e.bytes), StatusCommand.ms(e.durationMs))
                else "  FAILED  ${e.table}: ${e.error}"
            )
            is Event.ChunkFailed -> io.err.println(
                "warning: ${e.table} chunk ${e.index + 1}, attempt ${e.attempt}: ${e.error}${if (e.retrying) " (retrying)" else ""}"
            )
            else -> Unit
        }
        val now = System.nanoTime()
        if (now - lastLine >= everySeconds * 1_000_000_000) {
            synchronized(this) {
                if (now - lastLine < everySeconds * 1_000_000_000) return
                lastLine = now
            }
            val done = tables.values.count { it.finished == true }
            val pct = if (totalEst > 0) totalRows * 100 / totalEst else 0
            io.out.println(
                "  progress: $done/${tables.size} tables done, %,d/~%,d rows (%d%%), %s, %,.0f rows/s".format(
                    totalRows, totalEst, pct, StatusCommand.mb(totalBytes), totalRows / elapsedSeconds.coerceAtLeast(0.001),
                )
            )
        }
    }

    override fun close() = Unit
}

private const val MB = 1024.0 * 1024.0

/** The per-table timing report printed after `copy`, `run` and `fetch-test`. */
fun printEngineReport(io: Io, title: String, r: EngineResult) {
    val out = io.out
    out.println()
    out.println(title)
    val w = r.tables.maxOfOrNull { it.table.length } ?: 0
    for (t in r.tables) {
        val secs = t.durationMs.coerceAtLeast(1) / 1000.0
        val state = when (t.outcome) {
            TableOutcome.DONE -> ""
            TableOutcome.FAILED -> "  FAILED: ${t.error}"
            TableOutcome.CANCELLED -> "  stopped (${t.chunksDone}/${t.chunksTotal} chunks committed)"
        }
        out.println(
            "  ${t.table.padEnd(w)}  %,12d rows  %10s  %8s  %,10.0f rows/s  %6.1f MB/s$state".format(
                t.rows, StatusCommand.mb(t.bytes), StatusCommand.ms(t.durationMs), t.rows / secs, t.bytes / secs / MB,
            )
        )
    }
    val rows = r.tables.sumOf { it.rows }
    val bytes = r.tables.sumOf { it.bytes }
    val secs = r.durationMs.coerceAtLeast(1) / 1000.0
    out.println(
        "  ${"total".padEnd(w)}  %,12d rows  %10s  %8s  %,10.0f rows/s  %6.1f MB/s".format(
            rows, StatusCommand.mb(bytes), StatusCommand.ms(r.durationMs), rows / secs, bytes / secs / MB,
        )
    )
}
