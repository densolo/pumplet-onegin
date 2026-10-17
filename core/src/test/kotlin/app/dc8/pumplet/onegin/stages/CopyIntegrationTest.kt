package app.dc8.pumplet.onegin.stages

import app.dc8.pumplet.onegin.Pumplet
import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.copy.TableOutcome
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.state.ChunkDone
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.RunStatus
import app.dc8.pumplet.onegin.state.SessionManager
import app.dc8.pumplet.onegin.state.SessionStatus
import app.dc8.pumplet.onegin.state.StateStore
import app.dc8.pumplet.onegin.state.StatusReader
import app.dc8.pumplet.onegin.state.TableStatus
import app.dc8.pumplet.onegin.state.list
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * copy / run / fetch-test against a throwaway schema on both docker DBs, compared by
 * checksum. Skipped when test-env isn't up.
 */
class CopyIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val s = "cp_" + System.nanoTime()

    /** Table -> ORDER BY for its checksum. */
    private val tables = mapOf(
        "parent" to "id", "child" to "a, b", "skew" to "id", "nopk" to "x, y", "ident" to "id",
        "ser" to "id", "gen" to "id", "wide" to "id", "lob" to "id",
    )

    private val schema = """
        create schema $s;
        create table $s.parent (id bigint primary key, name text);
        create table $s.child (a bigint, b text, v numeric, parent_id bigint references $s.parent (id), primary key (a, b));
        create table $s.skew (id bigint primary key, v int);
        create table $s.nopk (x int, y text);
        create table $s.ident (id int generated always as identity primary key, v text);
        create table $s.ser (id serial primary key, v text);
        create table $s.lob (id int primary key, data oid);
        create sequence $s.global_seq;
        create sequence $s.ahead_seq;
        create sequence $s.secret_seq;
    """.trimIndent()

    private val sourceOnly = """
        create table $s.gen (id int primary key, a int, b int);
        create table $s.wide (id int primary key, v varchar(10));
        insert into $s.parent select g, 'p' || g from generate_series(1, 30000) g;
        insert into $s.child select g % 9000 + 1, (g % 13)::text || 'k', g * 1.5, g % 30000 + 1 from generate_series(1, 117000) g
            on conflict do nothing;
        insert into $s.skew select g, g from generate_series(1, 2000) g;
        insert into $s.skew select 1000000000 + g, g from generate_series(1, 20000) g;
        insert into $s.nopk select g % 100, 'y' || g from generate_series(1, 3000) g;
        insert into $s.ident (v) select 'i' || g from generate_series(1, 500) g;
        insert into $s.ser (v) select 's' || g from generate_series(1, 700) g;
        insert into $s.gen select g, g, g from generate_series(1, 100) g;
        insert into $s.wide select g, 'w' || g from generate_series(1, 100) g;
        insert into $s.lob select g, lo_from_bytea(0, convert_to(repeat('lo-' || g, 5000), 'UTF8')) from generate_series(1, 20) g;
        insert into $s.lob values (21, null);
        grant usage on schema $s to app_ro;
        grant select on all tables in schema $s to app_ro;
        select setval('$s.ser_id_seq', 5000);
        select setval('$s.global_seq', 12345);
        select setval('$s.ahead_seq', 10);
        select setval('$s.secret_seq', 777);
        grant select on all sequences in schema $s to app_ro;
        revoke select on sequence $s.secret_seq from app_ro;
        do ${'$'}${'$'} declare o oid; begin
            for o in select data from $s.lob where data is not null loop
                execute format('grant select on large object %s to app_ro', o);
            end loop;
        end ${'$'}${'$'};
        analyze;
    """.trimIndent()

    private val targetOnly = """
        select setval('$s.ahead_seq', 99999);
        create table $s.gen (id int primary key, a int, b int generated always as (a * 2) stored);
        create table $s.wide (id int primary key, v varchar(50));
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        assumeTrue(reachable(55432) && reachable(55433), "docker test DBs not running (see test-env/docker-compose.yml)")
        src(schema + "\n" + sourceOnly)
        dst(schema + "\n" + targetOnly)
    }

    @AfterEach
    fun tearDown() {
        if (!reachable(55432)) return
        val dropLobs = "select lo_unlink(data) from $s.lob where data is not null"
        runCatching { src(dropLobs) }
        runCatching { dst(dropLobs) }
        src("drop schema if exists $s cascade")
        dst("drop schema if exists $s cascade")
    }

    private fun reachable(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 500); true }
    } catch (e: Exception) {
        false
    }

    private fun src(sql: String) = admin(55432, "src-admin-pw", sql)
    private fun dst(sql: String) = admin(55433, "dst-pw", sql)

    private fun admin(port: Int, password: String, sql: String) {
        DriverManager.getConnection("jdbc:postgresql://localhost:$port/app", "postgres", password).use { c ->
            c.createStatement().use { it.execute(sql) }
        }
    }

    private fun <T> query(port: Int, password: String, sql: String, row: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection("jdbc:postgresql://localhost:$port/app", "postgres", password).use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); row(rs) } }
        }

    /**
     * Row count and md5 of every row of every table, plus large-object contents.
     * `gen.b` is generated on the target (a * 2), so only the copied columns count there.
     */
    private fun checksums(port: Int, password: String): Map<String, String> = tables.mapValues { (t, order) ->
        val row = if (t == "gen") "row(x.id, x.a)::text" else "x::text"
        query(port, password, "select count(*) || ':' || coalesce(md5(string_agg($row, '|' order by $order)), '') from $s.$t x") { it.getString(1) }
    } + ("lob contents" to query(port, password, "select md5(string_agg(coalesce(md5(lo_get(data)), '-'), '|' order by id)) from $s.lob") { it.getString(1) })

    private fun targetRows(): Long = tables.keys.sumOf { t -> query(55433, "dst-pw", "select count(*) from $s.$t") { it.getLong(1) } }

    private fun config(extra: String = ""): JobConfig {
        val secrets = dir.resolve("s.yaml")
        Files.writeString(secrets, "src: \"app-ro-pw\"\ndst: \"dst-pw\"\n")
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"))
        val job = dir.resolve("job.yaml")
        Files.writeString(
            job,
            """
            secrets: s.yaml
            source:
              url: jdbc:postgresql://localhost:55432/app
              user: app_ro
              password: ${'$'}{secret:src}
              schemas: [$s]
            target:
              url: jdbc:postgresql://localhost:55433/app
              user: postgres
              password: ${'$'}{secret:dst}
            threads:
              readers: 3
              writers: 2
            chunking:
              rowsPerChunk: 2000
              minRowsToSplit: 2000
            buffer:
              maxMemoryMb: 16
            """.trimIndent() + "\n" + extra,
        )
        return ConfigLoader(cwd = dir).load(job).config
    }

    private fun chunks(cfg: JobConfig): List<ChunkDone> {
        val session = StatusReader(cfg).read().detail!!.session.id
        return Db.connect(cfg.target, Role.TARGET).use { StateStore(cfg.state.table, cfg.redactor).list(it, session, Kind.CHUNK) }
    }

    @Test
    fun `run copies every table exactly, including large objects, and finishes the session`() {
        val cfg = config()
        val r = SessionManager(cfg).begin("run").use { session -> RunStage(cfg).run(session).also { session.succeed() } }

        assertTrue(r.copy.engine.tables.all { it.outcome == TableOutcome.DONE }, r.copy.engine.toString())
        assertTrue(r.enable.sessionCompleted)
        assertEquals(checksums(55432, "src-admin-pw"), checksums(55433, "dst-pw"))

        val byTable = chunks(cfg).groupBy { it.table.substringAfter('.') }
        assertEquals(15, byTable.getValue("parent").size)
        assertTrue(byTable.getValue("child").size > 10, "composite text key is chunked")
        assertTrue(byTable.getValue("skew").size > 5)
        // Bounds follow the data, not min..max: no chunk of the skewed table gets most of it.
        assertTrue(byTable.getValue("skew").maxOf { it.rows } < 22000 / 2, byTable.getValue("skew").map { it.rows }.toString())
        assertEquals(1, byTable.getValue("nopk").size)

        // Sequences follow the copied data; generated columns are computed by the target.
        dst("insert into $s.ident (v) values ('new'); insert into $s.ser (v) values ('new')")
        assertEquals(501, query(55433, "dst-pw", "select max(id) from $s.ident") { it.getInt(1) })
        // The source's sequence is at 5000 (ahead of its 700 rows), so the target continues after it.
        assertEquals(5001, query(55433, "dst-pw", "select max(id) from $s.ser") { it.getInt(1) })
        assertEquals(200, query(55433, "dst-pw", "select b from $s.gen where id = 100") { it.getInt(1) })
        assertEquals(SessionStatus.COMPLETED, StatusReader(cfg).read().history.firstOrNull()?.session?.status ?: StatusReader(cfg).read().detail!!.session.status)
    }

    @Test
    fun `a stopped copy resumes exactly once`() {
        val cfg = config()
        SessionManager(cfg).begin("disable").use { DisableStage(cfg).run(it); it.succeed() }

        val cancel = AtomicBoolean()
        val done = AtomicInteger()
        val stopAfterSome = EventSink { e -> if (e is Event.ChunkDone && done.incrementAndGet() >= 12) cancel.set(true) }
        SessionManager(cfg).begin("copy").use { session ->
            val e = assertFailsWith<StageException> { CopyStage(cfg, stopAfterSome, cancel).run(session) }
            assertContains(e.message!!, "stopped before finishing")
        }
        // What is committed is exactly what the state says: no partial chunks, nothing unrecorded.
        val committed = chunks(cfg)
        assertTrue(committed.size >= 12)
        assertEquals(committed.sumOf { it.rows }, targetRows())
        assertTrue(targetRows() < 150_000)

        SessionManager(cfg).begin("copy").use { session -> CopyStage(cfg).run(session); session.succeed() }
        SessionManager(cfg).begin("enable").use { session -> EnableStage(cfg).run(session, false); session.succeed() }
        assertEquals(checksums(55432, "src-admin-pw"), checksums(55433, "dst-pw"))
        val all = chunks(cfg)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertEquals(all.sumOf { it.rows }, targetRows())
    }

    @Test
    fun `a chunk that keeps failing fails its table, the rest finish, and a rerun redoes only what is missing`() {
        val cfg = config()
        dst("alter table $s.parent add constraint no_big check (id < 25000)")
        SessionManager(cfg).begin("disable").use { DisableStage(cfg).run(it); it.succeed() }
        val failures = AtomicInteger()
        SessionManager(cfg).begin("copy").use { session ->
            val e = assertFailsWith<StageException> {
                CopyStage(cfg, EventSink { if (it is Event.ChunkFailed) failures.incrementAndGet() }).run(session)
            }
            assertContains(e.message!!, "1 table(s) failed: $s.parent")
            assertContains(e.message!!, "no_big")
        }
        assertEquals(3 * 3, failures.get(), "3 chunks over the limit, each tried 3 times")
        val status = StatusReader(cfg).read().detail!!
        assertEquals(TableStatus.FAILED, status.tables.single { it.table == "$s.parent" }.status)
        assertEquals(12, status.tables.single { it.table == "$s.parent" }.chunksDone)
        assertTrue(status.tables.filter { it.table != "$s.parent" }.all { it.status == TableStatus.DONE })

        dst("alter table $s.parent drop constraint no_big")
        val again = SessionManager(cfg).begin("copy").use { session -> CopyStage(cfg).run(session).also { session.succeed() } }
        assertEquals(6000, again.engine.tables.single { it.table == "$s.parent" }.rows, "only the 3 failed chunks")
        assertEquals(0, again.engine.tables.single { it.table == "$s.child" }.rows)
        SessionManager(cfg).begin("enable").use { session -> EnableStage(cfg).run(session, false); session.succeed() }
        assertEquals(checksums(55432, "src-admin-pw"), checksums(55433, "dst-pw"))
    }

    @Test
    fun `copy refuses while foreign keys are in place`() {
        val cfg = config()
        SessionManager(cfg).begin("copy").use { session ->
            val e = assertFailsWith<StageException> { CopyStage(cfg).run(session) }
            assertContains(e.message!!, "foreign keys are still in place on $s.child; run `disable` first")
        }
        assertEquals(0, targetRows())
    }

    @Test
    fun `a new session refuses non-empty targets and says how to empty them`() {
        dst("insert into $s.ser (v) values ('mine')")
        val e = assertFailsWith<StageException> {
            SessionManager(config()).begin("run").use { RunStage(config()).run(it) }
        }
        assertContains(e.message!!, "these target tables already have rows: $s.ser")
        assertContains(e.message!!, "truncate \"$s\".\"ser\";")
        assertEquals(1, targetRows(), "nothing was emptied or copied")
    }

    @Test
    fun `fetch-test reads everything and touches nothing on the target`() {
        val cfg = config()
        val r = FetchTest(cfg).run()
        val expected = mapOf("parent" to 30000L, "skew" to 22000L, "nopk" to 3000L, "lob" to 21L)
        for ((t, n) in expected) assertEquals(n, r.tables.single { it.table == "$s.$t" }.rows, t)
        assertTrue(r.tables.single { it.table == "$s.child" }.rows > 100_000 - 20_000)
        assertEquals(0, targetRows())
        assertFalse(Db.connect(cfg.target, Role.TARGET).use { StateStore(cfg.state.table, cfg.redactor).exists(it) })
    }

    @Test
    fun `flow API - events stream while it runs, and cancelling the collector stops the copy cleanly`() {
        val cfg = config()
        val api = Pumplet(cfg)
        runBlocking { api.disable().events.collect() }

        val copy = api.copy()
        var chunks = 0
        runBlocking {
            // Stop collecting after a few chunks: the flow is cancelled, the copy rolls back what is in flight.
            copy.events.takeWhile { e -> if (e is Event.ChunkDone) chunks++; chunks < 5 }.collect()
        }
        assertTrue(copy.result.isCompleted)
        assertTrue(runCatching { runBlocking { copy.result.await() } }.isFailure)
        val detail = api.status().detail!!
        assertEquals(RunStatus.INTERRUPTED, detail.runs.last().status)
        assertEquals(chunks(cfg).sumOf { it.rows }, targetRows(), "nothing half-done was committed")

        val second = api.copy()
        val seen = mutableListOf<Event>()
        runBlocking { second.events.collect { seen += it } }
        assertTrue(seen.any { it is Event.TableFinished && it.ok })
        assertTrue(runBlocking { second.result.await() }.engine.failed.isEmpty())
        runBlocking { api.enable().events.collect() }
        assertEquals(checksums(55432, "src-admin-pw"), checksums(55433, "dst-pw"))
        assertTrue(api.status().detail!!.session.status == SessionStatus.COMPLETED)
    }

    private fun lastValue(seq: String): Long? =
        query(55433, "dst-pw", "select last_value from pg_sequences where schemaname = '$s' and sequencename = '$seq'") { it.getLong(1).takeUnless { _ -> it.wasNull() } }

    @Test
    fun `sequences are raised to the source's values, never lowered`() {
        val cfg = config()
        val events = mutableListOf<Event>()
        val r = SessionManager(cfg).begin("run").use { session -> RunStage(cfg, EventSink { events += it }).run(session).also { session.succeed() } }

        assertEquals(5000, lastValue("ser_id_seq"), "the source's sequence was ahead of its data")
        assertEquals(12345, lastValue("global_seq"), "a sequence no column owns")
        assertEquals(99999, lastValue("ahead_seq"), "the target was ahead: kept")
        assertEquals(null, lastValue("secret_seq"), "unreadable on the source: left alone")
        assertEquals(500, lastValue("ident_id_seq"), "identity: the source's value equals its data")
        val seq = r.copy.sequences!!
        assertEquals(setOf("$s.ser_id_seq", "$s.global_seq"), seq.raised.keys)
        assertTrue("$s.ahead_seq" in seq.kept)
        assertTrue(events.any { it is Event.Warning && it.message.contains("can't read 1 source sequence(s) ($s.secret_seq)") && it.message.contains("grant select on all sequences in schema") }, events.toString())
        dst("insert into $s.ser (v) values ('new')")
        assertEquals(5001, query(55433, "dst-pw", "select max(id) from $s.ser") { it.getInt(1) })
    }

    @Test
    fun `with raiseToSource off, sequences only follow the copied data`() {
        val cfg = config("sequences:\n  raiseToSource: false\n")
        val r = SessionManager(cfg).begin("run").use { session -> RunStage(cfg).run(session).also { session.succeed() } }
        assertEquals(null, r.copy.sequences)
        assertEquals(700, lastValue("ser_id_seq"))
        assertEquals(null, lastValue("global_seq"))
        assertEquals(99999, lastValue("ahead_seq"))
    }
}
