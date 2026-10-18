package app.dc8.pumplet.onegin.db

import app.dc8.pumplet.onegin.config.Redactor
import java.sql.Connection
import java.sql.SQLException

/**
 * A statement failed. The message says where in one line, which is what state, status and
 * summaries keep:
 * `target database (jdbc:postgresql://localhost/app as postgres), table app.orders chunk 3/6: ERROR: …`.
 * [detail] adds the statement and the stack trace, for the output and the log.
 */
class SqlError(
    val role: Role,
    val database: String,
    /** What the statement applied to, e.g. "table app.orders, chunk 3/6" or "foreign key app.orders.orders_fk". */
    val subject: String,
    val sql: String,
    cause: SQLException,
) : SQLException("${role.label} database ($database), $subject: ${cause.message}", cause.sqlState, cause) {
    val pgMessage: String get() = (cause as SQLException).message.orEmpty()

    /** The where, the failed SQL and the full stack trace, for printing. */
    val detail: String get() = detail(withStackTrace = true)

    /** Without the stack trace: for data problems (violated keys, duplicates), where a trace says nothing. */
    fun detail(withStackTrace: Boolean): String = buildString {
        appendLine("failed on the ${role.label} database ($database), $subject")
        appendLine("SQL:")
        sql.trim().lines().forEach { appendLine("  $it") }
        appendLine("PostgreSQL: $pgMessage" + (sqlState?.let { " (SQLSTATE $it)" } ?: ""))
        if (withStackTrace) {
            appendLine("stack trace:")
            stackTraceToString().trimEnd().lines().forEach { appendLine("  $it") }
        }
    }.trimEnd()

    /** Integrity violations: the data, not the tool, is the problem. */
    val dataProblem: Boolean get() = sqlState?.startsWith("23") == true

    companion object {
        /** `url as user` of an open connection, without credentials. */
        fun describe(c: Connection): String = runCatching {
            "${Redactor.safeJdbcUrl(c.metaData.url)} as ${c.metaData.userName}"
        }.getOrDefault("unknown database")

        /** Runs [block], turning a failure into an [SqlError] that says where and what (an existing one passes through). */
        inline fun <T> on(c: Connection, role: Role, subject: String, sql: String, block: () -> T): T = try {
            block()
        } catch (e: SqlError) {
            throw e
        } catch (e: SQLException) {
            throw SqlError(role, describe(c), subject, sql, e)
        }

        /** The [SqlError] behind [e], if any (it may be wrapped, e.g. by a pipe or an executor). */
        fun find(e: Throwable?): SqlError? {
            var t = e
            while (t != null) {
                if (t is SqlError) return t
                t = t.cause.takeIf { it !== t }
            }
            return null
        }

        /** What to print for a failure: the SQL error's detail, or the stack trace of anything else. */
        fun detailOf(e: Throwable, redactor: Redactor): String = redactor.scrub(find(e)?.detail ?: e.stackTraceToString().trimEnd())
    }
}
