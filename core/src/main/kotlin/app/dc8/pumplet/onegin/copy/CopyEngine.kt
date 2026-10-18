package app.dc8.pumplet.onegin.copy

import app.dc8.pumplet.onegin.config.Redactor
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.db.SqlError
import app.dc8.pumplet.onegin.schema.TablePlan
import app.dc8.pumplet.onegin.stages.Event
import app.dc8.pumplet.onegin.stages.EventSink
import app.dc8.pumplet.onegin.state.ChunkDone
import app.dc8.pumplet.onegin.state.ChunkPlan
import com.zaxxer.hikari.HikariDataSource
import org.postgresql.PGConnection
import java.io.InputStream
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A table to copy: its plan, its chunks, and which chunks earlier runs already committed. */
data class TableJob(
    val plan: TablePlan,
    val chunks: ChunkPlan,
    val done: Map<Int, ChunkDone> = emptyMap(),
    /** Empty the table in the chunk's transaction first (a single-chunk table without a primary key being redone). */
    val truncateFirst: Boolean = false,
) {
    val table get() = plan.table
    val columnList get() = plan.columns.joinToString(", ") { Sql.ident(it.name) }
}

data class ChunkTask(val job: TableJob, val index: Int)

/** One chunk as it was moved: [rows] written (or read, when nothing is written) and bytes streamed. */
data class ChunkStats(val rows: Long, val bytes: Long)

enum class TableOutcome { DONE, FAILED, CANCELLED }

data class TableResult(
    val table: String,
    val outcome: TableOutcome,
    /** Rows and bytes moved by this run (not earlier ones). */
    val rows: Long,
    val bytes: Long,
    val durationMs: Long,
    val chunksTotal: Int,
    /** Committed overall, including earlier runs. */
    val chunksDone: Int,
    val error: String?,
)

data class EngineResult(val tables: List<TableResult>, val cancelled: Boolean, val durationMs: Long) {
    val failed get() = tables.filter { it.outcome == TableOutcome.FAILED }
}

/** Where chunks go: the target (copy) or nowhere (fetch-test). */
interface ChunkSink {
    /** Writes one chunk read from [input], committing it with its state; returns rows written, or null if not counted. */
    fun write(task: ChunkTask, input: InputStream): Long?

    /** Large-object tables: copies the chunk row by row, reading through [source], a connection inside the snapshot. */
    fun writeRows(task: ChunkTask, source: Connection): ChunkStats

    /** Every chunk of [job] is committed (now or earlier). */
    fun tableDone(job: TableJob)

    /** True if large-object tables go through [writeRows]; false streams them like any other. */
    val rowPathForLargeObjects: Boolean
}

/**
 * Moves chunks from the source to a [ChunkSink]. Readers stream `COPY (SELECT ...)
 * TO STDOUT` inside one exported snapshot; each chunk flows through its own
 * bounded [ChunkPipe] to a writer. At most `readers` chunks are in flight, each
 * holding at most `maxMemoryMb / readers` of data, so memory stays bounded.
 * Failed chunks are retried; a table whose chunk keeps failing is reported FAILED
 * while the other tables carry on.
 */
class CopyEngine(
    private val sourcePool: HikariDataSource,
    private val snapshot: String,
    private val readers: Int,
    private val writers: Int,
    private val maxMemoryMb: Int,
    private val sink: ChunkSink,
    private val redactor: Redactor,
    private val events: EventSink,
    private val cancel: AtomicBoolean,
    private val attempts: Int = 3,
) {
    private val budget = maxMemoryMb.toLong() * 1024 * 1024 / readers

    private inner class Tracker(val job: TableJob) {
        val remaining = AtomicInteger(job.chunks.chunks - job.done.size)
        val chunksDone = AtomicInteger(job.done.size)
        val rows = AtomicLong()
        val bytes = AtomicLong()
        val started = AtomicBoolean()
        @Volatile var startNanos = 0L
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        @Volatile var result: TableResult? = null
    }

    fun run(jobs: List<TableJob>): EngineResult {
        val t0 = System.nanoTime()
        val trackers = jobs.associate { it.table to Tracker(it) }
        val readerPool = Executors.newFixedThreadPool(readers, daemon("pumplet-reader"))
        val writerPool = Executors.newFixedThreadPool(writers, daemon("pumplet-writer"))
        val inFlight = Semaphore(readers)
        val futures = mutableListOf<Future<*>>()
        try {
            // Tables whose chunks were all committed earlier only need finishing.
            trackers.values.filter { it.remaining.get() == 0 }.forEach { tr ->
                futures += writerPool.submit { start(tr); finishTable(tr) }
            }
            val tasks = jobs.sortedByDescending { it.plan.estRows }
                .flatMap { job -> (0 until job.chunks.chunks).filter { it !in job.done }.map { ChunkTask(job, it) } }
            for (task in tasks) {
                while (!cancel.get() && !inFlight.tryAcquire(200, TimeUnit.MILLISECONDS)) Unit
                if (cancel.get()) break
                futures += writerPool.submit {
                    try {
                        runChunk(task, trackers.getValue(task.job.table), readerPool)
                    } finally {
                        inFlight.release()
                    }
                }
            }
            futures.forEach { runCatching { it.get() } }
        } finally {
            writerPool.shutdownNow()
            readerPool.shutdownNow()
            writerPool.awaitTermination(30, TimeUnit.SECONDS)
            readerPool.awaitTermination(30, TimeUnit.SECONDS)
        }
        val results = jobs.map { job ->
            val tr = trackers.getValue(job.table)
            tr.result ?: TableResult(
                job.table, if (tr.error != null) TableOutcome.FAILED else TableOutcome.CANCELLED,
                tr.rows.get(), tr.bytes.get(), elapsed(tr), job.chunks.chunks, tr.chunksDone.get(), tr.error,
            )
        }
        return EngineResult(results, cancel.get(), (System.nanoTime() - t0) / 1_000_000)
    }

    private fun start(tr: Tracker) {
        if (tr.started.compareAndSet(false, true)) {
            tr.startNanos = System.nanoTime()
            events.emit(Event.TableStarted(tr.job.table, tr.job.plan.estRows, tr.job.chunks.chunks, tr.job.done.size, tr.job.done.values.sumOf { it.rows }))
        }
    }

    private fun runChunk(task: ChunkTask, tr: Tracker, readerPool: java.util.concurrent.ExecutorService) {
        start(tr)
        var lastError: String? = null
        for (attempt in 1..attempts) {
            if (cancel.get()) break
            try {
                val stats = if (task.job.plan.largeObjects && sink.rowPathForLargeObjects) {
                    rowChunk(task)
                } else {
                    streamChunk(task, readerPool)
                }
                tr.rows.addAndGet(stats.rows)
                tr.bytes.addAndGet(stats.bytes)
                tr.chunksDone.incrementAndGet()
                events.emit(Event.ChunkDone(task.job.table, task.index, stats.rows, stats.bytes))
                if (tr.remaining.decrementAndGet() == 0 && tr.error == null) finishTable(tr)
                return
            } catch (e: Exception) {
                if (cancel.get()) break
                lastError = redactor.scrub(SqlError.find(e)?.message ?: rootMessage(e))
                val retrying = attempt < attempts
                // The full detail (SQL, where, stack trace) once retrying has given up.
                val detail = if (retrying) null else SqlError.detailOf(e, redactor)
                events.emit(Event.ChunkFailed(task.job.table, task.index, attempt, lastError, retrying, detail))
                if (retrying) Thread.sleep(1000L * attempt)
            }
        }
        if (cancel.get()) {
            tr.cancelled = true
            return
        }
        if (tr.error == null) {
            tr.error = if (lastError?.contains(", chunk ") == true) lastError else "chunk ${task.index + 1}/${task.job.chunks.chunks}: $lastError"
            events.emit(Event.TableFinished(tr.job.table, false, tr.rows.get(), tr.bytes.get(), elapsed(tr), tr.error))
        }
    }

    /** Reader and writer joined by a pipe; either side's failure aborts both. */
    private fun streamChunk(task: ChunkTask, readerPool: java.util.concurrent.ExecutorService): ChunkStats {
        val pipe = ChunkPipe(budget)
        val read = readerPool.submit<ChunkStats> {
            try {
                read(task, pipe)
            } catch (e: Throwable) {
                pipe.fail(e)
                throw e
            }
        }
        val written = try {
            sink.write(task, pipe.input)
        } catch (e: Throwable) {
            pipe.fail(e)
            // If the source read failed first, the write only failed because its stream broke: report the source.
            val readFailure = runCatching { read.get() }.exceptionOrNull()
                ?.let { (it as? java.util.concurrent.ExecutionException)?.cause ?: it }
            if (readFailure != null && readFailure !is PipeClosedException) throw readFailure
            throw e
        }
        val readStats = try {
            read.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
        return ChunkStats(written ?: readStats.rows, readStats.bytes)
    }

    /** Streams one chunk out of the source in [ChunkPipe.BLOCK_SIZE] blocks. */
    private fun read(task: ChunkTask, pipe: ChunkPipe): ChunkStats {
        val job = task.job
        val where = ChunkPlanner.predicate(job.chunks, job.plan.primaryKeyTypes, task.index)?.let { " where $it" } ?: ""
        val format = if (job.plan.binary) "binary" else "text"
        val sql = "copy (select ${job.columnList} from ${Sql.table(job.table)}$where) to stdout (format $format)"
        sourcePool.connection.use { c ->
            var broken = false
            try {
                return SqlError.on(c, Role.SOURCE, "table ${job.table}, chunk ${task.index + 1}/${job.chunks.chunks}", sql) {
                    c.autoCommit = true
                    inSnapshot(c)
                    val out = c.unwrap(PGConnection::class.java).copyAPI.copyOut(sql)
                    var messages = 0L
                    var bytes = 0L
                    val block = java.io.ByteArrayOutputStream(ChunkPipe.BLOCK_SIZE + 64 * 1024)
                    var pendingRows = 0L
                    try {
                        while (true) {
                            if (cancel.get()) throw InterruptedException("copy cancelled")
                            val msg = out.readFromCopy() ?: break
                            messages++
                            pendingRows++
                            block.write(msg)
                            if (block.size() >= ChunkPipe.BLOCK_SIZE) {
                                bytes += block.size()
                                events.emit(Event.ChunkProgress(job.table, task.index, pendingRows, block.size().toLong()))
                                pendingRows = 0
                                pipe.put(block.toByteArray())
                                block.reset()
                            }
                        }
                    } catch (e: Throwable) {
                        if (out.isActive) runCatching { out.cancelCopy() }
                        throw e
                    }
                    if (block.size() > 0) {
                        bytes += block.size()
                        events.emit(Event.ChunkProgress(job.table, task.index, pendingRows, block.size().toLong()))
                        pipe.put(block.toByteArray())
                    }
                    pipe.finish()
                    c.createStatement().use { it.execute("commit") }
                    // Binary COPY sends the trailer as one more message.
                    val rows = if (job.plan.binary) (messages - 1).coerceAtLeast(0) else messages
                    ChunkStats(rows, bytes)
                }
            } catch (e: Throwable) {
                broken = true
                throw e
            } finally {
                if (broken) {
                    runCatching { c.createStatement().use { it.execute("rollback") } }.onFailure { sourcePool.evictConnection(c) }
                }
            }
        }
    }

    /** Row-by-row path for large-object tables: one source connection in the snapshot, handed to the sink. */
    private fun rowChunk(task: ChunkTask): ChunkStats =
        sourcePool.connection.use { c ->
            c.autoCommit = false
            c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            try {
                c.createStatement().use { it.execute("set transaction snapshot ${ChunkPlanner.literal(snapshot)}") }
                sink.writeRows(task, c).also { c.commit() }
            } catch (e: Throwable) {
                runCatching { c.rollback() }.onFailure { sourcePool.evictConnection(c) }
                throw e
            } finally {
                runCatching { c.autoCommit = true }
            }
        }

    private fun inSnapshot(c: Connection) {
        c.createStatement().use {
            it.execute("begin isolation level repeatable read read only")
            it.execute("set transaction snapshot ${ChunkPlanner.literal(snapshot)}")
        }
    }

    private fun finishTable(tr: Tracker) {
        try {
            sink.tableDone(tr.job)
            val res = TableResult(tr.job.table, TableOutcome.DONE, tr.rows.get(), tr.bytes.get(), elapsed(tr), tr.job.chunks.chunks, tr.chunksDone.get(), null)
            tr.result = res
            events.emit(Event.TableFinished(tr.job.table, true, res.rows, res.bytes, res.durationMs, null))
        } catch (e: Exception) {
            tr.error = "finishing the table: ${redactor.scrub(rootMessage(e))}"
            events.emit(Event.TableFinished(tr.job.table, false, tr.rows.get(), tr.bytes.get(), elapsed(tr), tr.error))
        }
    }

    private fun elapsed(tr: Tracker) = if (tr.startNanos == 0L) 0 else (System.nanoTime() - tr.startNanos) / 1_000_000

    companion object {
        fun rootMessage(e: Throwable): String {
            var t: Throwable = e
            while (t.cause != null && t.cause !== t && (t is PipeClosedException || t is java.util.concurrent.ExecutionException || t.message == null)) t = t.cause!!
            // PostgreSQL errors carry "Detail:" / "Where:" lines; keep them, on one line.
            return (t.message ?: t.javaClass.simpleName).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
        }

        private fun daemon(name: String) = java.util.concurrent.ThreadFactory { r ->
            Thread(r).apply {
                isDaemon = true
                this.name = "$name-${counter.incrementAndGet()}"
            }
        }

        private val counter = AtomicInteger()
    }
}
