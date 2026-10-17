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

object Db {
    /**
     * Opens a single connection; the password goes only into the driver properties, never into the URL.
     * Source connections are always read-only; [readOnly] makes a target connection read-only too.
     */
    fun connect(db: DbConfig, role: Role, readOnly: Boolean = role == Role.SOURCE): Connection {
        val c = DriverManager.getConnection(db.url, properties(db))
        if (role == Role.SOURCE || readOnly) makeReadOnly(c)
        return c
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
