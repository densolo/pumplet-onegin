package app.dc8.pumplet.onegin.copy

import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.state.ChunkDone
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.StateStore
import app.dc8.pumplet.onegin.state.TableProgress
import app.dc8.pumplet.onegin.state.TableStatus
import com.zaxxer.hikari.HikariDataSource
import org.postgresql.PGConnection
import org.postgresql.largeobject.LargeObjectManager
import java.io.InputStream
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Changes rows on the row-level path before they are written, e.g. to mask data.
 * Values are in PostgreSQL text form, keyed by column name; set one to null for NULL.
 */
fun interface RowTransformer {
    fun transform(table: String, row: MutableMap<String, String?>)

    companion object {
        val NONE = RowTransformer { _, _ -> }
    }
}

/**
 * Writes chunks to the target. Each chunk's rows and its [ChunkDone] state row are
 * committed in one transaction: a chunk is either fully there and recorded, or
 * neither, so a resumed copy redoes exactly the chunks that are missing.
 */
class TargetSink(
    private val pool: HikariDataSource,
    private val store: StateStore,
    private val session: String,
    private val runSeq: Int,
    private val transformer: RowTransformer = RowTransformer.NONE,
) : ChunkSink {
    override val rowPathForLargeObjects = true

    override fun write(task: ChunkTask, input: InputStream): Long {
        val job = task.job
        val format = if (job.plan.binary) "binary" else "text"
        val t0 = System.nanoTime()
        return inTransaction { c ->
            if (job.truncateFirst) c.createStatement().use { it.execute("truncate ${Sql.table(job.table)}") }
            val counting = CountingInput(input)
            val rows = c.unwrap(PGConnection::class.java).copyAPI
                .copyIn("copy ${Sql.table(job.table)} (${job.columnList}) from stdin (format $format)", counting, 64 * 1024)
            store.put(c, session, Kind.CHUNK, ChunkDone.chunkKey(job.table, task.index),
                ChunkDone(job.table, task.index, rows, counting.count.get(), elapsedMs(t0), runSeq))
            rows
        }
    }

    override fun writeRows(task: ChunkTask, source: Connection): ChunkStats {
        val job = task.job
        val cols = job.plan.columns
        val where = ChunkPlanner.predicate(job.chunks, job.plan.primaryKeyTypes, task.index)?.let { " where $it" } ?: ""
        val select = "select ${cols.joinToString(", ") { "${Sql.ident(it.name)}::text" }} from ${Sql.table(job.table)}$where"
        val overriding = if (cols.any { it.identityAlways }) " overriding system value" else ""
        val insert = "insert into ${Sql.table(job.table)} (${job.columnList})$overriding values (" +
            cols.joinToString(", ") { "cast(? as ${it.targetType})" } + ")"
        val t0 = System.nanoTime()
        var rows = 0L
        var bytes = 0L
        inTransaction { c ->
            if (job.truncateFirst) c.createStatement().use { it.execute("truncate ${Sql.table(job.table)}") }
            val srcLo = source.unwrap(PGConnection::class.java).largeObjectAPI
            val dstLo = c.unwrap(PGConnection::class.java).largeObjectAPI
            val buf = ByteArray(64 * 1024)
            source.prepareStatement(select).use { q ->
                q.fetchSize = 1000
                c.prepareStatement(insert).use { ins ->
                    q.executeQuery().use { rs ->
                        while (rs.next()) {
                            val row = linkedMapOf<String, String?>()
                            cols.forEachIndexed { i, col -> row[col.name] = rs.getString(i + 1) }
                            transformer.transform(job.table, row)
                            for (col in cols) {
                                val v = row[col.name] ?: continue
                                bytes += v.length
                                if (col.largeObject) bytes += copyLargeObject(v.toLong(), c, srcLo, dstLo, buf)
                            }
                            cols.forEachIndexed { i, col -> ins.setString(i + 1, row[col.name]) }
                            ins.addBatch()
                            if (++rows % 500 == 0L) ins.executeBatch()
                        }
                    }
                    ins.executeBatch()
                }
            }
            store.put(c, session, Kind.CHUNK, ChunkDone.chunkKey(job.table, task.index),
                ChunkDone(job.table, task.index, rows, bytes, elapsedMs(t0), runSeq))
        }
        return ChunkStats(rows, bytes)
    }

    /** Creates the large object on the target with the same OID, unless another row already brought it. */
    private fun copyLargeObject(oid: Long, c: Connection, src: LargeObjectManager, dst: LargeObjectManager, buf: ByteArray): Long {
        val exists = c.prepareStatement("select exists (select 1 from pg_largeobject_metadata where oid = cast(? as oid))").use { ps ->
            ps.setLong(1, oid)
            ps.executeQuery().use { it.next(); it.getBoolean(1) }
        }
        if (exists) return 0
        c.prepareStatement("select lo_create(cast(? as oid))").use { ps -> ps.setLong(1, oid); ps.executeQuery().close() }
        var n = 0L
        src.open(oid, LargeObjectManager.READ).use { from ->
            dst.open(oid, LargeObjectManager.WRITE).use { to ->
                val input = from.inputStream
                val output = to.outputStream
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    output.write(buf, 0, r)
                    n += r
                }
                output.flush()
            }
        }
        return n
    }

    /** Resets sequences owned by the table's columns to its data, analyzes it, and records it DONE. */
    override fun tableDone(job: TableJob) {
        pool.connection.use { c ->
            c.autoCommit = true
            val owned = c.prepareStatement(
                """
                select a.attname, pg_get_serial_sequence(?, a.attname)
                from pg_attribute a
                where a.attrelid = cast(? as regclass) and a.attnum > 0 and not a.attisdropped
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, Sql.table(job.table))
                ps.setString(2, Sql.table(job.table))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) rs.getString(2)?.let { add(rs.getString(1) to it) } } }
            }
            for ((col, seq) in owned) {
                c.prepareStatement(
                    "select setval(cast(? as regclass), m, true) from (select max(${Sql.ident(col)}) m from ${Sql.table(job.table)}) x where m is not null"
                ).use { ps -> ps.setString(1, seq); ps.executeQuery().close() }
            }
            c.createStatement().use { it.execute("analyze ${Sql.table(job.table)}") }
            val chunks = store.list(c, session, Kind.CHUNK, ChunkDone::class.java).filter { it.table == job.table }
            store.put(c, session, Kind.TABLE, job.table, TableProgress(
                table = job.table, status = TableStatus.DONE, estRows = job.plan.estRows,
                rows = chunks.sumOf { it.rows }, bytes = chunks.sumOf { it.bytes },
                chunksTotal = job.chunks.chunks, chunksDone = chunks.size, endedAt = Instant.now(),
            ))
        }
    }

    private fun <T> inTransaction(block: (Connection) -> T): T {
        val c = pool.connection
        var ok = false
        try {
            c.autoCommit = false
            return block(c).also { c.commit(); ok = true }
        } finally {
            if (!ok) runCatching { c.rollback() }.onFailure { pool.evictConnection(c) }
            runCatching { c.autoCommit = true }
            c.close()
        }
    }

    private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private class CountingInput(private val inner: InputStream) : InputStream() {
        val count = AtomicLong()
        override fun read(): Int = inner.read().also { if (it >= 0) count.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count.addAndGet(it.toLong()) }
    }
}

/** fetch-test: reads chunks and throws them away. */
object DiscardSink : ChunkSink {
    override val rowPathForLargeObjects = false

    override fun write(task: ChunkTask, input: InputStream): Long? {
        val buf = ByteArray(64 * 1024)
        while (input.read(buf) >= 0) Unit
        return null
    }

    override fun writeRows(task: ChunkTask, source: Connection) = error("not used")

    override fun tableDone(job: TableJob) = Unit
}
