package app.dc8.pumplet.onegin.state

import app.dc8.pumplet.onegin.config.Redactor
import app.dc8.pumplet.onegin.config.TableName
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection

/**
 * Typed access to the state table on the target. Every method takes the
 * connection to use, so callers can write state in the same transaction as the
 * data it describes (e.g. a copied chunk and its "chunk done" row).
 *
 * Payloads pass through [redactor] before they are written, so a secret that
 * slipped into an error message never reaches the table.
 */
class StateStore(val table: TableName, private val redactor: Redactor) {
    private val t = table.sql

    fun exists(c: Connection): Boolean =
        c.prepareStatement("select to_regclass(?) is not null").use { ps ->
            ps.setString(1, t)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    /** Creates the table if needed. Fails with a clear message if its schema doesn't exist. */
    fun ensure(c: Connection) {
        val schemaExists = c.prepareStatement("select 1 from pg_namespace where nspname = ?").use { ps ->
            ps.setString(1, table.schema)
            ps.executeQuery().use { it.next() }
        }
        if (!schemaExists) {
            throw StateException(
                "schema '${table.schema}' for the state table does not exist on the target; " +
                    "create it or set state.table to <schema>.<table> in an existing schema"
            )
        }
        c.createStatement().use {
            it.execute(
                """
                create table if not exists $t (
                    session_id text not null,
                    kind       text not null,
                    key        text not null,
                    payload    jsonb not null,
                    updated_at timestamptz not null default now(),
                    primary key (session_id, kind, key)
                )
                """.trimIndent()
            )
        }
    }

    fun put(c: Connection, session: String, kind: String, key: String, payload: Any) {
        c.prepareStatement(
            """
            insert into $t (session_id, kind, key, payload) values (?, ?, ?, cast(? as jsonb))
            on conflict (session_id, kind, key) do update set payload = excluded.payload, updated_at = now()
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, session)
            ps.setString(2, kind)
            ps.setString(3, key)
            ps.setString(4, redactor.scrub(json.writeValueAsString(payload)))
            ps.executeUpdate()
        }
    }

    fun <T> get(c: Connection, session: String, kind: String, key: String, type: Class<T>): T? =
        c.prepareStatement("select payload from $t where session_id = ? and kind = ? and key = ?").use { ps ->
            ps.setString(1, session)
            ps.setString(2, kind)
            ps.setString(3, key)
            ps.executeQuery().use { rs -> if (rs.next()) json.readValue(rs.getString(1), type) else null }
        }

    /** All records of one kind in a session, ordered by key. */
    fun <T> list(c: Connection, session: String, kind: String, type: Class<T>): List<T> =
        c.prepareStatement("select payload from $t where session_id = ? and kind = ? order by key").use { ps ->
            ps.setString(1, session)
            ps.setString(2, kind)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(json.readValue(rs.getString(1), type)) } }
        }

    /** Sessions, newest first; only those of one job when [configHash] is given. */
    fun sessions(c: Connection, configHash: String? = null, limit: Int = Int.MAX_VALUE): List<SessionInfo> =
        c.prepareStatement(
            """
            select payload from $t
            where kind = '${Kind.SESSION}' and (cast(? as text) is null or payload->>'configHash' = ?)
            order by cast(payload->>'createdAt' as timestamptz) desc
            limit ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, configHash)
            ps.setString(2, configHash)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(json.readValue(rs.getString(1), SessionInfo::class.java)) }
            }
        }

    /** Finds a session by full id or unique id prefix. */
    fun findSession(c: Connection, idPrefix: String): SessionInfo? {
        val matches = c.prepareStatement(
            "select payload from $t where kind = '${Kind.SESSION}' and session_id like ? escape '\\' limit 2"
        ).use { ps ->
            ps.setString(1, idPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(json.readValue(rs.getString(1), SessionInfo::class.java)) }
            }
        }
        if (matches.size > 1) throw StateException("session id '$idPrefix' is ambiguous; give more characters")
        return matches.firstOrNull()
    }

    companion object {
        val json: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }
}

class StateException(message: String) : RuntimeException(message)

inline fun <reified T> StateStore.get(c: Connection, session: String, kind: String, key: String): T? =
    get(c, session, kind, key, T::class.java)

inline fun <reified T> StateStore.list(c: Connection, session: String, kind: String): List<T> =
    list(c, session, kind, T::class.java)
