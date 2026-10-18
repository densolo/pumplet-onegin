package app.dc8.pumplet.onegin.stages

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.ObjectKind
import app.dc8.pumplet.onegin.state.ObjectStatus
import app.dc8.pumplet.onegin.state.SavedObject
import app.dc8.pumplet.onegin.state.SessionChoice
import app.dc8.pumplet.onegin.state.SessionManager
import app.dc8.pumplet.onegin.state.SessionStatus
import app.dc8.pumplet.onegin.state.StateException
import app.dc8.pumplet.onegin.state.StateStore
import app.dc8.pumplet.onegin.state.StatusReader
import app.dc8.pumplet.onegin.state.list
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * disable / enable / recover against a throwaway schema on both docker DBs
 * (built as the admin user). Skipped when test-env isn't up.
 */
class ConstraintsIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val s = "ct_" + System.nanoTime()

    private val ddl = """
        create schema $s;
        create table $s.parent (id int primary key);
        create table $s.child (id int primary key, p int references $s.parent (id), code text);
        create index child_p_idx on $s.child (p);
        create unique index child_code_idx on $s.child (code);
        create function $s.touch() returns trigger language plpgsql as ${'$'}${'$'} begin return new; end ${'$'}${'$'};
        create trigger child_touch before insert on $s.child for each row execute function $s.touch();
        create trigger child_replica before insert on $s.child for each row execute function $s.touch();
        alter table $s.child enable replica trigger child_replica;
        create trigger child_off before insert on $s.child for each row execute function $s.touch();
        alter table $s.child disable trigger child_off;
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        assumeTrue(reachable(55432) && reachable(55433), "docker test DBs not running (see test-env/docker-compose.yml)")
        src(ddl + "\ngrant usage on schema $s to app_ro; grant select on all tables in schema $s to app_ro;")
        dst(ddl)
    }

    @AfterEach
    fun tearDown() {
        if (!reachable(55432)) return
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

    private fun <T> dstQuery(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:postgresql://localhost:55433/app", "postgres", "dst-pw").use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }
        }

    /** Everything disable touches, as the target has it now: definitions, validation and trigger modes. */
    private fun targetShape(): List<String> =
        dstQuery("select conname || ' ' || pg_get_constraintdef(oid) || ' ' || convalidated from pg_constraint where connamespace = '$s'::regnamespace and conrelid::regclass::text <> '$s.pumplet_onegin_state' order by 1") { it.getString(1) } +
            dstQuery("select indexrelid::regclass::text from pg_index where indrelid = '$s.child'::regclass order by 1") { it.getString(1) } +
            dstQuery("select tgname || ' ' || tgenabled::text from pg_trigger where tgrelid = '$s.child'::regclass and not tgisinternal order by 1") { it.getString(1) }

    private fun config(extra: String = "", indexes: Boolean = true): JobConfig {
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
            constraints:
              dropForeignKeys: true
              dropSecondaryIndexes: $indexes
              disableUserTriggers: true
            """.trimIndent() + "\n" + extra,
        )
        return ConfigLoader(cwd = dir).load(job).config
    }

    private fun disable(cfg: JobConfig) = SessionManager(cfg).begin("disable").use { session ->
        DisableStage(cfg).run(session).also { session.succeed() }
    }

    private fun enable(cfg: JobConfig, skipPending: Boolean = false) = SessionManager(cfg).begin("enable", SessionChoice.ResumeOnly).use { session ->
        EnableStage(cfg).run(session, skipPending).also { session.succeed() }
    }

    private fun saved(cfg: JobConfig): List<SavedObject> {
        val session = StatusReader(cfg).read().detail!!.session.id
        return Db.connect(cfg.target, Role.TARGET).use { StateStore(cfg.state.table, cfg.redactor).list(it, session, Kind.OBJECT) }
    }

    @Test
    fun `disable saves and drops, enable restores exactly what was there`() {
        val cfg = config()
        val before = targetShape()

        val r = disable(cfg)
        assertEquals(
            listOf(
                ObjectKind.FOREIGN_KEY to "child_p_fkey",
                ObjectKind.INDEX to "child_code_idx",
                ObjectKind.INDEX to "child_p_idx",
                ObjectKind.TRIGGER to "child_replica",
                ObjectKind.TRIGGER to "child_touch",
            ),
            r.dropped.map { it.kind to it.name },
        )
        // Already-disabled triggers and constraint-backed indexes (the primary keys) stay as they are.
        assertTrue(dstQuery("select 1 from pg_constraint where conname = 'child_p_fkey' and connamespace = '$s'::regnamespace") { 1 }.isEmpty())
        assertEquals(listOf("$s.child_pkey"), dstQuery("select indexrelid::regclass::text from pg_index where indrelid = '$s.child'::regclass") { it.getString(1) })
        assertEquals(setOf("child_off D", "child_replica D", "child_touch D"), dstQuery("select tgname || ' ' || tgenabled::text from pg_trigger where tgrelid = '$s.child'::regclass and not tgisinternal") { it.getString(1) }.toSet())
        assertTrue(saved(cfg).all { it.status == ObjectStatus.DROPPED })

        val e = enable(cfg)
        assertEquals(5, e.restore.restored.size)
        assertFalse(e.sessionCompleted, "copy hasn't run, so the session stays open")
        assertEquals(before, targetShape())
        assertTrue(saved(cfg).all { it.status == ObjectStatus.RESTORED })
    }

    @Test
    fun `indexes are left alone unless configured`() {
        val cfg = config(indexes = false)
        assertFalse(disable(cfg).dropped.any { it.kind == ObjectKind.INDEX })
        enable(cfg)
    }

    @Test
    fun `a second disable finds nothing more to drop`() {
        val cfg = config()
        disable(cfg)
        val again = disable(cfg)
        assertEquals(emptyList(), again.dropped)
        assertEquals(5, again.alreadyDropped)
        enable(cfg)
    }

    @Test
    fun `a violating foreign key stays pending until the data is fixed`() {
        val cfg = config()
        val before = targetShape()
        disable(cfg)
        dst("insert into $s.parent values (1); insert into $s.child values (1, 1, 'a'), (2, 99, 'b'), (3, 98, 'c'), (4, null, 'd')")

        val first = enable(cfg)
        assertEquals(listOf("child_p_fkey"), first.restore.pending.map { it.name })
        assertContains(first.restore.pending.single().error!!, "2 row(s) in $s.child have no matching row")
        assertEquals(4, first.restore.restored.size)
        // Not left half-added: a pending FK is simply absent.
        assertTrue(dstQuery("select 1 from pg_constraint where conname = 'child_p_fkey'") { 1 }.isEmpty())

        dst("delete from $s.child where p > 1")
        val second = enable(cfg)
        assertEquals(listOf("child_p_fkey"), second.restore.restored.map { it.name }, "only the pending one is retried")
        dst("truncate $s.child, $s.parent")
        assertEquals(before, targetShape())
    }

    @Test
    fun `a failing unique index is pending too`() {
        val cfg = config()
        disable(cfg)
        dst("insert into $s.parent values (1); insert into $s.child values (1, 1, 'dup'), (2, 1, 'dup')")
        val r = enable(cfg)
        assertEquals(listOf("child_code_idx"), r.restore.pending.map { it.name })
        assertContains(r.restore.pending.single().error!!, "could not create unique index")
    }

    @Test
    fun `skip-pending gives up on pending objects and unblocks new sessions`() {
        val cfg = config()
        disable(cfg)
        dst("insert into $s.child values (1, 42, 'x')")
        enable(cfg)
        val other = config("chunking:\n  rowsPerChunk: 1000\n")
        assertFailsWith<StateException> { SessionManager(other).begin("disable") }

        val r = enable(cfg, skipPending = true)
        assertEquals(listOf("child_p_fkey"), r.restore.skipped.map { it.name })
        assertEquals(ObjectStatus.SKIPPED, saved(cfg).single { it.name == "child_p_fkey" }.status)
        dst("truncate $s.child")
        SessionManager(other).begin("disable").use { it.succeed() }
    }

    @Test
    fun `a foreign key left NOT VALID by an interrupted enable is validated, not re-added`() {
        val cfg = config()
        disable(cfg)
        dst("alter table $s.child add constraint child_p_fkey foreign key (p) references $s.parent (id) not valid")
        val r = enable(cfg)
        assertTrue(r.restore.pending.isEmpty())
        assertEquals(listOf(true), dstQuery("select convalidated from pg_constraint where conname = 'child_p_fkey'") { it.getBoolean(1) })
    }

    @Test
    fun `a new session never starts on tables that already have rows`() {
        val cfg = config()
        dst("insert into $s.parent values (1)")
        val before = targetShape()
        repeat(2) {
            val e = assertFailsWith<StageException> { disable(cfg) }
            assertContains(e.message!!, "these target tables already have rows: $s.parent")
        }
        assertEquals(before, targetShape(), "nothing was dropped")
    }

    @Test
    fun `blocking diff errors stop disable before anything is dropped`() {
        dst("alter table $s.child add column must int not null")
        val cfg = config()
        val before = targetShape()
        val e = assertFailsWith<StageException> { disable(cfg) }
        assertContains(e.message!!, "blocking error")
        assertEquals(before, targetShape())
    }

    @Test
    fun `another job's dropped objects block new sessions until recovered`() {
        val first = config()
        val firstSession = SessionManager(first).begin("disable").use { DisableStage(first).run(it); it.succeed(); it.id }

        val second = config("chunking:\n  rowsPerChunk: 1000\n")
        val e = assertFailsWith<StateException> { SessionManager(second).begin("disable") }
        assertContains(e.message!!, "recover --config <job.yaml> --session ${firstSession.take(8)}")

        SessionManager(second).begin("recover", SessionChoice.Existing(firstSession.take(8))).use { session ->
            assertEquals(firstSession, session.id)
            val r = RestoreRunner(second).run(session, skipPending = false)
            assertEquals(5, r.restored.size)
            session.succeed()
        }
        assertEquals(SessionStatus.OPEN, StatusReader(first).read().detail!!.session.status, "recover doesn't finish the session")
        SessionManager(second).begin("disable").use { it.succeed() }
    }

    @Test
    fun `enable needs an open session`() {
        val e = assertFailsWith<StateException> { enable(config()) }
        assertContains(e.message!!, "no open session for this job")
    }

    @Test
    fun `a failed restore says where and shows the SQL, with a stack trace unless the data is the problem`() {
        val cfg = config()
        disable(cfg)
        dst("insert into $s.child values (1, 42, 'x')")        // orphan: a data problem
        dst("alter table $s.child drop column code cascade")   // the unique index's column is gone: not a data problem
        val details = mutableMapOf<String, String>()
        SessionManager(cfg).begin("enable", SessionChoice.ResumeOnly).use { session ->
            EnableStage(cfg, EventSink { e -> if (e is Event.ObjectChanged && e.detail != null) details[e.obj.name] = e.detail!! }).run(session, false)
            session.succeed()
        }
        val fk = details.getValue("child_p_fkey")
        assertContains(fk, "failed on the target database (jdbc:postgresql://localhost:55433/app as postgres), foreign key $s.child.child_p_fkey")
        assertContains(fk, "SQL:\n  alter table \"$s\".\"child\" validate constraint \"child_p_fkey\"")
        assertContains(fk, "(SQLSTATE 23503)")
        assertFalse(fk.contains("stack trace:"), "a violated key is about the data")

        val idx = details.getValue("child_code_idx")
        assertContains(idx, "index $s.child.child_code_idx")
        assertContains(idx, "CREATE UNIQUE INDEX child_code_idx ON $s.child USING btree (code)")
        assertContains(idx, "(SQLSTATE 42703)")
        assertContains(idx, "stack trace:")
    }
}
