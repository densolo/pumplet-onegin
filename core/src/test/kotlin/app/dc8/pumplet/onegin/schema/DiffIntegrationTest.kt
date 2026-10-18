package app.dc8.pumplet.onegin.schema

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.state.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds a throwaway schema on both docker DBs (as the admin user) with deliberate
 * differences, then diffs it. Skipped when test-env isn't up.
 */
class DiffIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val schema = "dt_" + System.nanoTime()

    private val sourceDdl = """
        create schema $schema;
        create table $schema.same (id int primary key, v text);
        create table $schema.missing_t (id int primary key);
        create table $schema.cols (
            id int primary key, gone text, widen varchar(10), narrow varchar(50), retype text,
            nn text, dflt int default 1, gen_t int, num numeric(10,2));
        create index cols_widen_idx on $schema.cols (widen);
        create index src_only_idx on $schema.cols (dflt);
        create table $schema.fk_parent (id int primary key);
        create table $schema.fk_child (id int primary key, p int references $schema.fk_parent (id));
        create table $schema.nopk (x int);
        create table $schema.lob (id int primary key, data oid);
        create table $schema.blocked (id int primary key, x int);
        create table $schema.secret (id int primary key);
        revoke all on $schema.secret from public;
        analyze;
    """.trimIndent()

    private val targetDdl = """
        create schema $schema;
        create table $schema.same (id int primary key, v text);
        create table $schema.cols (
            id int primary key, widen varchar(20), narrow varchar(10), retype integer,
            nn text not null, dflt int default 2, gen_t int generated always as (id * 2) stored,
            num numeric(12,2), extra_ok int default 0);
        create index cols_widen_renamed on $schema.cols (widen);
        create index tgt_only_idx on $schema.cols (num);
        create table $schema.fk_parent (id int primary key);
        create table $schema.fk_child (id int primary key, p int);
        create table $schema.nopk (x int);
        create table $schema.lob (id int primary key, data oid);
        create table $schema.blocked (id int primary key, x int, must int not null);
        create table $schema.secret (id int primary key);
        create table $schema.target_only (id int);
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        assumeTrue(reachable(55432) && reachable(55433), "docker test DBs not running (see test-env/docker-compose.yml)")
        admin(55432, "src-admin-pw", sourceDdl)
        admin(55433, "dst-pw", targetDdl)
    }

    @AfterEach
    fun tearDown() {
        if (!reachable(55432)) return
        admin(55432, "src-admin-pw", "drop schema if exists $schema cascade")
        admin(55433, "dst-pw", "drop schema if exists $schema cascade")
    }

    private fun reachable(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 500); true }
    } catch (e: Exception) {
        false
    }

    private fun admin(port: Int, password: String, sql: String) {
        java.sql.DriverManager.getConnection("jdbc:postgresql://localhost:$port/app", "postgres", password).use { c ->
            c.createStatement().use { it.execute(sql) }
        }
    }

    /** A job on the throwaway schema; the source user is app_ro, which can't read `secret`. */
    private fun config(extra: String = ""): JobConfig {
        val secrets = dir.resolve("s.yaml")
        Files.writeString(secrets, "src: \"app-ro-pw\"\ndst: \"dst-pw\"\n")
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"))
        admin(55432, "src-admin-pw", "grant usage on schema $schema to app_ro; grant select on all tables in schema $schema to app_ro; revoke select on $schema.secret from app_ro")
        val job = dir.resolve("job.yaml")
        Files.writeString(
            job,
            """
            secrets: s.yaml
            source:
              url: jdbc:postgresql://localhost:55432/app
              user: app_ro
              password: ${'$'}{secret:src}
              schemas: [$schema]
            target:
              url: jdbc:postgresql://localhost:55433/app
              user: postgres
              password: ${'$'}{secret:dst}
            chunking:
              rowsPerChunk: 10
              minRowsToSplit: 1
            """.trimIndent() + "\n" + extra,
        )
        return ConfigLoader(cwd = dir).load(job).config
    }

    private fun DiffReport.findings(table: String, subject: String? = null) =
        findings.filter { it.table == "$schema.$table" && (subject == null || it.subject == subject) }

    private fun DiffReport.one(table: String, subject: String?, severity: Severity, text: String) {
        val f = findings(table, subject)
        assertTrue(f.any { it.severity == severity && it.message.contains(text) }, "expected $severity '$text' on $table.$subject, got $f")
    }

    @Test
    fun `reports differences and decides per table`() {
        val r = Differ(config()).run()

        assertEquals(TableAction.COPY, r.table("$schema.same")!!.action)
        assertEquals(emptyList(), r.findings("same"))

        assertEquals(TableAction.SKIP, r.table("$schema.missing_t")!!.action)
        r.one("missing_t", null, Severity.WARNING, "table missing in target")

        val cols = r.table("$schema.cols")!!
        assertEquals(TableAction.BLOCKED, cols.action)
        r.one("cols", "gone", Severity.WARNING, "column missing in target")
        r.one("cols", "widen", Severity.WARNING, "safe widening, copied as text")
        r.one("cols", "num", Severity.WARNING, "safe widening")
        r.one("cols", "narrow", Severity.ERROR, "character varying(50) in source, character varying(10) in target")
        r.one("cols", "retype", Severity.ERROR, "type text in source, integer in target")
        r.one("cols", "nn", Severity.WARNING, "NOT NULL in target")
        r.one("cols", "dflt", Severity.INFO, "default differs: 1 in source, 2 in target")
        r.one("cols", "gen_t", Severity.INFO, "generated in target")
        r.one("cols", "extra_ok", Severity.INFO, "only in target: gets its default")
        r.one("cols", "cols_widen_renamed", Severity.INFO, "index is named cols_widen_idx in source")
        r.one("cols", "src_only_idx", Severity.WARNING, "index missing in target")
        r.one("cols", "tgt_only_idx", Severity.INFO, "index only in target")
        assertFalse(cols.columns.any { it.name == "gen_t" || it.name == "gone" })

        r.one("fk_child", "fk_child_p_fkey", Severity.WARNING, "foreign key missing in target: FOREIGN KEY (p) REFERENCES $schema.fk_parent(id)")

        val nopk = r.table("$schema.nopk")!!
        assertEquals(TableAction.COPY, nopk.action)
        assertNull(nopk.chunks)
        r.one("nopk", null, Severity.INFO, "no primary key")

        val lob = r.table("$schema.lob")!!
        assertTrue(lob.largeObjects)
        assertTrue(lob.binary)

        assertEquals(TableAction.BLOCKED, r.table("$schema.blocked")!!.action)
        r.one("blocked", "must", Severity.ERROR, "extra NOT NULL column without default")

        assertEquals(TableAction.BLOCKED, r.table("$schema.secret")!!.action)
        r.one("secret", null, Severity.ERROR, "no SELECT privilege")

        assertEquals(listOf("$schema.target_only"), r.targetOnly)
        assertTrue(r.blocking)

        // The comparison rows agree with the findings, and identical objects are there too.
        fun row(table: String, name: String) = r.comparisons.single { it.table == "$schema.$table" && it.name == name }
        assertEquals(Verdict.SAME, row("same", "v").verdict)
        assertEquals(Verdict.SAME, row("same", "$schema.same").verdict)
        assertEquals(Verdict.COMPATIBLE, row("cols", "widen").verdict)
        assertEquals("character varying(10)" to "character varying(20)", row("cols", "widen").source to row("cols", "widen").target)
        assertEquals(Verdict.BLOCKER, row("cols", "retype").verdict)
        assertEquals(Verdict.COMPATIBLE, row("cols", "gone").verdict)
        assertEquals(null, row("cols", "gone").target)
        assertEquals(Verdict.BLOCKER, row("blocked", "must").verdict)
        assertEquals("integer not null", row("blocked", "must").target)
        assertEquals(Verdict.COMPATIBLE, row("fk_child", "fk_child_p_fkey").verdict)
        assertEquals(Verdict.COMPATIBLE, row("missing_t", "$schema.missing_t").verdict)
        assertEquals(Verdict.BLOCKER, row("secret", "$schema.secret").verdict)
        assertEquals(Verdict.COMPATIBLE, row("target_only", "$schema.target_only").verdict)
        assertTrue(r.comparisons.none { it.verdict == Verdict.BLOCKER && r.findings.none { f -> f.severity == Severity.ERROR && f.table == it.table } })
        assertTrue(r.overall.startsWith("BLOCKED: "), r.overall)
    }

    @Test
    fun `allowTypeDifferences turns type errors into warnings and copies as text`() {
        val r = Differ(config("diff:\n  allowTypeDifferences: true\n")).run()
        r.one("cols", "retype", Severity.WARNING, "copied as text (diff.allowTypeDifferences)")
        val cols = r.table("$schema.cols")!!
        assertEquals(TableAction.COPY, cols.action)
        assertFalse(cols.binary)
        assertTrue(cols.columns.single { it.name == "retype" }.asText)
    }

    @Test
    fun `filters apply and the state table is never part of the diff`() {
        admin(55433, "dst-pw", "create table $schema.pumplet_onegin_state (x int)")
        val r = Differ(config("tables:\n  include: [$schema.c*, $schema.f*]\n  exclude: [$schema.fk_parent]\n")).run()
        assertEquals(listOf("cols", "fk_child").map { "$schema.$it" }, r.tables.map { it.table })
        assertFalse(r.targetOnly.any { it.endsWith("pumplet_onegin_state") })
    }

    @Test
    fun `missing target schema blocks and nothing is written to the target`() {
        admin(55433, "dst-pw", "drop schema $schema cascade")
        val cfg = config()
        val r = Differ(cfg).run()
        assertTrue(r.blocking)
        assertTrue(r.findings.any { it.table == schema && it.severity == Severity.ERROR && it.message.contains("does not exist in target") })
        assertTrue(r.tables.all { it.action != TableAction.COPY })
        assertFalse(Db.connect(cfg.target, Role.TARGET).use { StateStore(cfg.state.table, cfg.redactor).exists(it) })
    }

    @Test
    fun `json report is written next to the job file without secrets`() {
        val differ = Differ(config())
        val path = differ.write(differ.run())
        assertEquals(dir.resolve("job.diff.json"), path)
        val text = Files.readString(path)
        assertFalse(text.contains("app-ro-pw"))
        assertFalse(text.contains("dst-pw"))
        val tree = StateStore.json.readTree(text)
        assertNotNull(tree["tables"].firstOrNull { it["table"].asText() == "$schema.cols" })
        assertContains(tree["findings"].toString(), "safe widening")
        // The UI reads the report back from this file.
        val back = StateStore.json.readValue(text, DiffReport::class.java)
        assertEquals(differ.run().comparisons.size, back.comparisons.size)
        assertTrue(back.comparisons.any { it.verdict == Verdict.BLOCKER })
    }
}
