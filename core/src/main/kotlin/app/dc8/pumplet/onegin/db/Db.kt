package app.dc8.pumplet.onegin.db

import app.dc8.pumplet.onegin.config.DbConfig
import app.dc8.pumplet.onegin.config.Redactor
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/** Which side of the copy a connection belongs to. The source is never written to. */
enum class Role(val label: String) { SOURCE("source"), TARGET("target") }

/**
 * Connecting to one side failed. Says which database, where, and what to check, so
 * an error like "password authentication failed" is never ambiguous.
 */
class DbConnectException(val role: Role, where: String, hint: String?, cause: java.sql.SQLException) :
    java.sql.SQLException(
        "can't connect to the ${role.label} database ($where): ${cause.message}" + (hint?.let { "; $it" } ?: ""),
        cause.sqlState,
        cause,
    )

object Db {
    /**
     * Opens a single connection; the password goes only into the driver properties, never into the URL.
     * Source connections are always read-only; [readOnly] makes a target connection read-only too.
     */
    fun connect(db: DbConfig, role: Role, readOnly: Boolean = role == Role.SOURCE): Connection {
        val c = try {
            DriverManager.getConnection(db.url, properties(db))
        } catch (e: java.sql.SQLException) {
            throw DbConnectException(role, describe(db), hint(db, role, e), e)
        }
        if (role == Role.SOURCE || readOnly) makeReadOnly(c)
        return c
    }

    /** What to check, by PostgreSQL error code. */
    private fun hint(db: DbConfig, role: Role, e: java.sql.SQLException): String? {
        val side = role.label
        return when (e.sqlState) {
            "28P01" -> "check the $side password" + (db.password?.let { " (secret '${it.name}' in the secrets file)" } ?: " (none is set: $side.password)")
            "28000" -> "the $side user isn't allowed to log in, or pg_hba.conf doesn't allow this connection"
            "3D000" -> "the database in the $side URL doesn't exist"
            "08001", "08004", "08006" -> "is the $side server running and reachable at that host and port?"
            else -> null
        }
    }

    /**
     * A connection pool for one side. Source pools are read-only at every level:
     * the pool flag, the session default, and therefore every transaction.
     */
    fun pool(db: DbConfig, role: Role, size: Int, name: String = role.label, initSql: String? = null): HikariDataSource {
        val hc = HikariConfig().apply {
            poolName = "pumplet-onegin-$name"
            jdbcUrl = db.url
            dataSourceProperties = properties(db)
            maximumPoolSize = size
            minimumIdle = 0
            connectionTimeout = 30_000
            initializationFailTimeout = -1
            isReadOnly = role == Role.SOURCE
            connectionInitSql = listOfNotNull(
                if (role == Role.SOURCE) "SET default_transaction_read_only = on" else null,
                initSql,
            ).joinToString("; ").ifEmpty { null }
        }
        return HikariDataSource(hc)
    }

    /** A credential-free description of where [db] points, safe for logs and output. */
    fun describe(db: DbConfig): String = "${Redactor.safeJdbcUrl(db.url)} as ${db.user ?: "(driver default user)"}"

    private fun makeReadOnly(c: Connection) {
        c.isReadOnly = true
        c.createStatement().use { it.execute("SET default_transaction_read_only = on") }
    }

    private fun properties(db: DbConfig) = Properties().apply {
        db.user?.let { this["user"] = it }
        db.password?.let { this["password"] = it.reveal() }
        this["ApplicationName"] = "pumplet-onegin"
        this["connectTimeout"] = "10"
        this["loginTimeout"] = "10"
    }
}
