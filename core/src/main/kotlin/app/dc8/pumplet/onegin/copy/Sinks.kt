package app.dc8.pumplet.onegin.copy

import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.db.SqlError
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
        if (job.plan.largeObjects && job.largeObjectsInBulk) return writeWithLargeObjects(task, input)
        val format = if (job.plan.binary) "binary" else "text"
        val t0 = System.nanoTime()
        val subject = chunkSubject(task)
        return inTransaction { c ->
            truncateIfAsked(c, job, subject)
            val counting = CountingInput(input)
            val copy = "copy ${Sql.table(job.table)} (${job.columnList}) from stdin (format $format)"
            val rows = SqlError.on(c, Role.TARGET, subject, copy) {
                c.unwrap(PGConnection::class.java).copyAPI.copyIn(copy, counting, 64 * 1024)
            }
            recordChunk(c, task, ChunkDone(job.table, task.index, rows, counting.count.get(), elapsedMs(t0), runSeq))
            rows
        }
    }

    /**
     * Large-object tables in bulk: the chunk arrives with each object's contents next to its oid
     * (`lo_get` on the source). It goes into a temporary staging table in one COPY; then the
     * missing objects are created with their original oids and the rows inserted, set-wise,
     * all in the chunk's transaction. An object several rows share is created once.
     */
    private fun writeWithLargeObjects(task: ChunkTask, input: InputStream): Long {
        val job = task.job
        val cols = job.plan.columns
        val lobs = cols.filter { it.largeObject }
        val format = if (job.plan.binary) "binary" else "text"
        val subject = chunkSubject(task)
        val t0 = System.nanoTime()
        // Binary COPY needs the staging types to match what the source sends; text can be cast later.
        val stageColumns = cols.map { "${Sql.ident(it.name)} ${if (job.plan.binary) it.targetType else "text"}" } +
            lobs.map { "${Sql.ident(it.name + TableJob.LO_DATA_SUFFIX)} bytea" }
        val createStage = "create temp table pumplet_stage (${stageColumns.joinToString(", ")}) on commit drop"
        val copy = "copy pumplet_stage from stdin (format $format)"
        val overriding = if (cols.any { it.identityAlways }) " overriding system value" else ""
        val insert = "insert into ${Sql.table(job.table)} (${job.columnList})$overriding select " +
            cols.joinToString(", ") { if (job.plan.binary) Sql.ident(it.name) else "cast(${Sql.ident(it.name)} as ${it.targetType})" } +
            " from pumplet_stage"
        return inTransaction { c ->
            truncateIfAsked(c, job, subject)
            fun exec(sql: String) = SqlError.on(c, Role.TARGET, subject, sql) { c.createStatement().use { it.execute(sql) } }
            exec(createStage)
            val counting = CountingInput(input)
            val rows = SqlError.on(c, Role.TARGET, subject, copy) {
                c.unwrap(PGConnection::class.java).copyAPI.copyIn(copy, counting, 64 * 1024)
            }
            for (lob in lobs) {
                val oid = Sql.ident(lob.name)
                val data = Sql.ident(lob.name + TableJob.LO_DATA_SUFFIX)
                // lo_from_bytea(oid, null) creates nothing: a row pointing at an object the source doesn't have stays that way.
                exec(
                    """
                    select lo_from_bytea(s.id, s.data)
                    from (select distinct on ($oid) $oid as id, $data as data from pumplet_stage where $oid is not null) s
                    where not exists (select 1 from pg_largeobject_metadata m where m.oid = s.id)
                    """.trimIndent()
                )
            }
            exec(insert)
            recordChunk(c, task, ChunkDone(job.table, task.index, rows, counting.count.get(), elapsedMs(t0), runSeq))
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
        val subject = chunkSubject(task)
        inTransaction { c ->
            truncateIfAsked(c, job, subject)
            val srcLo = source.unwrap(PGConnection::class.java).largeObjectAPI
            val dstLo = c.unwrap(PGConnection::class.java).largeObjectAPI
            val buf = ByteArray(64 * 1024)
            // Reading is on the source; inserts and large objects (wrapped inside) on the target.
            SqlError.on(source, Role.SOURCE, subject, select) { source.prepareStatement(select).use { q ->
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
                                if (col.largeObject) {
                                    bytes += SqlError.on(c, Role.TARGET, "$subject, large object $v", "lo_create($v) + copy of its contents") {
                                        copyLargeObject(v.toLong(), c, srcLo, dstLo, buf)
                                    }
                                }
                            }
                            cols.forEachIndexed { i, col -> ins.setString(i + 1, row[col.name]) }
                            ins.addBatch()
                            if (++rows % 500 == 0L) SqlError.on(c, Role.TARGET, subject, insert) { ins.executeBatch() }
                        }
                    }
                    SqlError.on(c, Role.TARGET, subject, insert) { ins.executeBatch() }
                }
            } }
            recordChunk(c, task, ChunkDone(job.table, task.index, rows, bytes, elapsedMs(t0), runSeq))
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

    /** Moves sequences owned by the table's columns past its data (never back), analyzes it, and records it DONE. */
    override fun tableDone(job: TableJob) {
        pool.connection.use { c -> tableDone(c, job) }
    }

    private fun tableDone(c: Connection, job: TableJob) {
        val t = "table ${job.table}"
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
            val maxSql = "select max(${Sql.ident(col)})::bigint from ${Sql.table(job.table)}"
            val max = SqlError.on(c, Role.TARGET, "$t, column $col", maxSql) {
                c.createStatement().use { st ->
                    st.executeQuery(maxSql).use { rs -> rs.next(); rs.getLong(1).takeUnless { rs.wasNull() } }
                }
            } ?: continue
            SqlError.on(c, Role.TARGET, "sequence $seq of $t", "select setval('$seq', $max, true)") { Sequences.raise(c, seq, max) }
        }
        val analyze = "analyze ${Sql.table(job.table)}"
        SqlError.on(c, Role.TARGET, t, analyze) { c.createStatement().use { it.execute(analyze) } }
        SqlError.on(c, Role.TARGET, "state of $t", "insert into ${store.table} (… kind 'table' …)") {
            val chunks = store.list(c, session, Kind.CHUNK, ChunkDone::class.java).filter { it.table == job.table }
            store.put(c, session, Kind.TABLE, job.table, TableProgress(
                table = job.table, status = TableStatus.DONE, estRows = job.plan.estRows,
                rows = chunks.sumOf { it.rows }, bytes = chunks.sumOf { it.bytes },
                chunksTotal = job.chunks.chunks, chunksDone = chunks.size, endedAt = Instant.now(),
            ))
        }
    }

    private fun chunkSubject(task: ChunkTask) = "table ${task.job.table}, chunk ${task.index + 1}/${task.job.chunks.chunks}"

    private fun truncateIfAsked(c: Connection, job: TableJob, subject: String) {
        if (!job.truncateFirst) return
        val sql = "truncate ${Sql.table(job.table)}"
        SqlError.on(c, Role.TARGET, subject, sql) { c.createStatement().use { it.execute(sql) } }
    }

    /** The chunk's "done" row, in the same transaction as its data. */
    private fun recordChunk(c: Connection, task: ChunkTask, done: ChunkDone) =
        SqlError.on(c, Role.TARGET, "state of ${chunkSubject(task)}", "insert into ${store.table} (… kind 'chunk' …)") {
            store.put(c, session, Kind.CHUNK, ChunkDone.chunkKey(task.job.table, task.index), done)
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
