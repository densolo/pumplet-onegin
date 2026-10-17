package app.dc8.pumplet.onegin.e2e

import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two PostgreSQL containers for the end-to-end tests, started once per JVM. The
 * source database is migrated and seeded once (tests only read it); every test gets
 * a fresh target database, migrated with the "target" context, so tests can't affect
 * each other.
 */
object E2eEnvironment {
    const val SOURCE_DB = "app"
    private val counter = AtomicInteger()

    val dockerAvailable: Boolean by lazy { runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) }

    val source: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17").withDatabaseName(SOURCE_DB).also {
            it.start()
            connect(it, SOURCE_DB).use { c -> migrate(c, "source") }
        }
    }

    val target: PostgreSQLContainer by lazy { PostgreSQLContainer("postgres:17").also { it.start() } }

    /** A new, migrated, empty target database; returns its name. */
    fun freshTarget(): String {
        val name = "t" + counter.incrementAndGet()
        connect(target, target.databaseName).use { c -> c.createStatement().use { it.execute("create database $name") } }
        connect(target, name).use { c -> migrate(c, "target") }
        return name
    }

    fun url(container: PostgreSQLContainer, db: String) = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$db"

    /** As the container's superuser. */
    fun connect(container: PostgreSQLContainer, db: String): Connection =
        DriverManager.getConnection(url(container, db), container.username, container.password)

    private fun migrate(c: Connection, context: String) {
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(c))
        @Suppress("DEPRECATION")
        Liquibase("e2e/changelog.sql", ClassLoaderResourceAccessor(), db).update(Contexts(context), LabelExpression())
    }

    /**
     * Writes a job (and its secrets file) for copying into target database [targetDb].
     * The source user is the read-only app_ro; the target user is the container superuser.
     */
    fun job(dir: Path, targetDb: String, extra: String = "", exclude: String = "[shop.mismatch]"): Path {
        val secrets = dir.resolve("e2e.secrets.yaml")
        Files.writeString(secrets, "src: \"ro-pw\"\ndst: \"${target.password}\"\n")
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"))
        val job = dir.resolve("e2e.yaml")
        Files.writeString(
            job,
            """
            secrets: e2e.secrets.yaml
            source:
              url: ${url(source, SOURCE_DB)}
              user: app_ro
              password: ${'$'}{secret:src}
              schemas: [shop]
            target:
              url: ${url(target, targetDb)}
              user: ${target.username}
              password: ${'$'}{secret:dst}
            tables:
              exclude: $exclude
            threads:
              readers: 4
              writers: 3
            chunking:
              rowsPerChunk: 20000
              minRowsToSplit: 20000
            buffer:
              maxMemoryMb: 64
            """.trimIndent() + "\n" + extra,
        )
        return job
    }

    /** Copied tables -> (columns compared, ORDER BY). emp is compared on the columns both sides have. */
    val copied = linkedMapOf(
        "dept" to ("x::text" to "id"),
        "emp" to ("row(x.id, x.dept_id, x.name, x.title)::text" to "id"),
        "product" to ("x::text" to "id"),
        "ticket" to ("x::text" to "id"),
        "note" to ("x::text" to "id"),
        "ledger" to ("x::text" to "account, entry_no"),
        "visit" to ("x::text" to "id"),
        "event_log" to ("x::text" to "at, msg"),
        "document" to ("x::text" to "id"),
        "measurement" to ("x::text" to "id"),
    )

    /** Row count and md5 of every copied table, plus large-object contents. */
    fun checksums(c: Connection): Map<String, String> = copied.mapValues { (t, spec) ->
        scalar(c, "select count(*) || ':' || coalesce(md5(string_agg(${spec.first}, '|' order by ${spec.second})), '') from shop.$t x")
    } + ("document contents" to scalar(c, "select md5(string_agg(coalesce(md5(lo_get(body)), '-'), '|' order by id)) from shop.document"))

    fun sourceChecksums(): Map<String, String> = connect(source, SOURCE_DB).use { checksums(it) }

    fun scalar(c: Connection, sql: String): String = c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getString(1) } }

    fun exec(c: Connection, sql: String) {
        c.createStatement().use { it.execute(sql) }
    }

    /** Rows in all copied tables. */
    fun rows(c: Connection): Long = copied.keys.sumOf { scalar(c, "select count(*) from shop.$it").toLong() }
}
