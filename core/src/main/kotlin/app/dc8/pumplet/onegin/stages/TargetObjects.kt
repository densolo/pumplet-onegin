package app.dc8.pumplet.onegin.stages

import app.dc8.pumplet.onegin.config.Redactor
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.Sql
import app.dc8.pumplet.onegin.db.SqlError
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.ObjectKind
import app.dc8.pumplet.onegin.state.ObjectStatus
import app.dc8.pumplet.onegin.state.SavedObject
import app.dc8.pumplet.onegin.state.StateStore
import java.sql.Connection
import java.sql.SQLException

/**
 * Drops and restores target foreign keys, secondary indexes and user triggers.
 *
 * Every change runs in its own transaction on [c] (autocommit off) together with
 * the state row that records it, so the state table always matches the database.
 */
class TargetObjects(
    private val c: Connection,
    private val store: StateStore,
    private val session: String,
    private val redactor: Redactor,
    private val events: EventSink,
) {
    init {
        c.autoCommit = false
        c.createStatement().use {
            // Fully qualified definitions; don't wait forever for a lock someone else holds.
            it.execute("set search_path = pg_catalog")
            it.execute("set lock_timeout = '60s'")
        }
        c.commit()
    }

    /** What would be dropped on [tables] (`schema.table`), with the given kinds enabled. */
    fun find(tables: List<String>, foreignKeys: Boolean, indexes: Boolean, triggers: Boolean): List<SavedObject> = buildList {
        if (foreignKeys) addAll(
            query(
                """
                select n.nspname || '.' || c.relname, con.conname, pg_get_constraintdef(con.oid)
                from pg_constraint con join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace
                where con.contype = 'f' and n.nspname || '.' || c.relname = any(?)
                order by 1, 2
                """.trimIndent(),
                tables,
            ) { SavedObject(ObjectKind.FOREIGN_KEY, it.getString(1), it.getString(2), it.getString(3), ObjectStatus.DROPPED) }
        )
        if (indexes) addAll(
            // Not indexes behind a constraint, nor ones a constraint depends on (e.g. a unique index an FK references).
            query(
                """
                select n.nspname || '.' || c.relname, ic.relname, pg_get_indexdef(i.indexrelid)
                from pg_index i
                join pg_class ic on ic.oid = i.indexrelid
                join pg_class c on c.oid = i.indrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname || '.' || c.relname = any(?)
                  and not exists (select 1 from pg_constraint con where con.conindid = i.indexrelid)
                order by 1, 2
                """.trimIndent(),
                tables,
            ) { SavedObject(ObjectKind.INDEX, it.getString(1), it.getString(2), it.getString(3), ObjectStatus.DROPPED) }
        )
        if (triggers) addAll(
            query(
                """
                select n.nspname || '.' || c.relname, t.tgname, pg_get_triggerdef(t.oid), t.tgenabled
                from pg_trigger t join pg_class c on c.oid = t.tgrelid join pg_namespace n on n.oid = c.relnamespace
                where not t.tgisinternal and t.tgenabled <> 'D' and n.nspname || '.' || c.relname = any(?)
                order by 1, 2
                """.trimIndent(),
                tables,
            ) {
                SavedObject(ObjectKind.TRIGGER, it.getString(1), it.getString(2), it.getString(3), ObjectStatus.DROPPED, enabledMode = it.getString(4))
            }
        )
    }

    /** Saves [obj] as DROPPED and drops/disables it, in one transaction. */
    fun drop(obj: SavedObject) {
        val saved = obj.copy(status = ObjectStatus.DROPPED, error = null)
        transaction {
            record(saved)
            exec(saved, dropSql(saved))
        }
        events.emit(Event.ObjectChanged(saved))
    }

    /** Detail of the last restore failure (SQL, where, and the stack trace unless it was a data problem). */
    private var failureDetail: String? = null

    /**
     * Restores [obj]; returns it with its new status. On failure the object is
     * left out of place and recorded as PENDING with the reason.
     */
    fun restore(obj: SavedObject): SavedObject {
        failureDetail = null
        val result = try {
            when (obj.kind) {
                ObjectKind.FOREIGN_KEY -> restoreForeignKey(obj)
                else -> {
                    val done = obj.copy(status = ObjectStatus.RESTORED, error = null)
                    transaction {
                        exec(obj, restoreSql(obj))
                        record(done)
                    }
                    done
                }
            }
        } catch (e: SQLException) {
            noteFailure(e)
            markPending(obj, SqlError.find(e)?.pgMessage ?: e.message ?: e.sqlState)
        }
        events.emit(Event.ObjectChanged(result, failureDetail))
        return result
    }

    private fun noteFailure(e: SQLException) {
        val err = SqlError.find(e)
        failureDetail = redactor.scrub(err?.detail(withStackTrace = !err.dataProblem) ?: e.stackTraceToString())
    }

    /** Records [obj] as given up on: it stays out of place, and no longer blocks new sessions. */
    fun skip(obj: SavedObject): SavedObject {
        val skipped = obj.copy(status = ObjectStatus.SKIPPED)
        transaction { record(skipped) }
        events.emit(Event.ObjectChanged(skipped))
        return skipped
    }

    /**
     * Adds the foreign key as NOT VALID, then validates it in a separate transaction.
     * If the data violates it, counts the offending rows, drops it again and marks it PENDING.
     * Copes with a NOT VALID constraint left behind by an interrupted earlier attempt.
     */
    private fun restoreForeignKey(obj: SavedObject): SavedObject {
        val t = Sql.table(obj.table)
        val n = Sql.ident(obj.name)
        val alreadyNotValid = obj.definition.endsWith(" NOT VALID")
        val existing = constraintValidated(obj)
        if (existing == null) {
            transaction { exec(obj, "alter table $t add constraint $n ${obj.definition}${if (alreadyNotValid) "" else " NOT VALID"}") }
        }
        if (!alreadyNotValid && existing != true) {
            try {
                transaction { exec(obj, "alter table $t validate constraint $n") }
            } catch (e: SQLException) {
                noteFailure(e)
                val violations = if (e.sqlState == FOREIGN_KEY_VIOLATION) countViolations(obj) else null
                transaction { exec(obj, "alter table $t drop constraint $n") }
                val why = violations?.let { "%,d row(s) in ${obj.table} have no matching row in the referenced table".format(it) }
                    ?: (SqlError.find(e)?.pgMessage ?: e.message ?: e.sqlState)
                return markPending(obj, why)
            }
        }
        val done = obj.copy(status = ObjectStatus.RESTORED, error = null)
        transaction { record(done) }
        return done
    }

    /** null if the constraint isn't there; else whether it's validated. */
    private fun constraintValidated(obj: SavedObject): Boolean? =
        c.prepareStatement(
            """
            select con.convalidated from pg_constraint con
            join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace
            where n.nspname || '.' || c.relname = ? and con.conname = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, obj.table)
            ps.setString(2, obj.name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
        }.also { c.commit() }

    /** Rows of the referencing table whose (non-null) key has no match: MATCH SIMPLE semantics. */
    private fun countViolations(obj: SavedObject): Long? = try {
        val cols = c.prepareStatement(
            """
            select (select string_agg(quote_ident(a.attname), ',' order by k.ord)
                    from unnest(con.conkey) with ordinality k(attnum, ord)
                    join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k.attnum),
                   (select string_agg(quote_ident(a.attname), ',' order by k.ord)
                    from unnest(con.confkey) with ordinality k(attnum, ord)
                    join pg_attribute a on a.attrelid = con.confrelid and a.attnum = k.attnum),
                   con.confrelid::regclass::text
            from pg_constraint con
            join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace
            where n.nspname || '.' || c.relname = ? and con.conname = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, obj.table)
            ps.setString(2, obj.name)
            ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
        } ?: return null
        val child = cols.first.split(',')
        val parent = cols.second.split(',')
        val notNull = child.joinToString(" and ") { "c.$it is not null" }
        val match = child.zip(parent).joinToString(" and ") { (ch, p) -> "p.$p = c.$ch" }
        c.createStatement().use { st ->
            st.executeQuery(
                "select count(*) from ${Sql.table(obj.table)} c where $notNull and not exists (select 1 from ${cols.third} p where $match)"
            ).use { rs -> rs.next(); rs.getLong(1) }
        }.also { c.commit() }
    } catch (e: SQLException) {
        c.rollback()
        null
    }

    private fun markPending(obj: SavedObject, error: String): SavedObject {
        val pending = obj.copy(status = ObjectStatus.PENDING, error = redactor.scrub(error))
        transaction { record(pending) }
        return pending
    }

    /** "foreign key app.orders.orders_customer_id_fkey", for error messages. */
    private fun subject(o: SavedObject) = "${o.kind.name.lowercase().replace('_', ' ')} ${o.table}.${o.name}"

    /** Runs a statement on [o], reporting a failure with the database, the object and the SQL. */
    private fun exec(o: SavedObject, sql: String) {
        SqlError.on(c, Role.TARGET, subject(o), sql) { c.createStatement().use { it.execute(sql) } }
    }

    /** Writes [o]'s state row (in the caller's transaction). */
    private fun record(o: SavedObject) =
        SqlError.on(c, Role.TARGET, "state of ${subject(o)}", "insert into ${store.table} (… kind 'object' …)") {
            store.put(c, session, Kind.OBJECT, o.key, o)
        }

    private fun dropSql(o: SavedObject) = when (o.kind) {
        ObjectKind.FOREIGN_KEY -> "alter table ${Sql.table(o.table)} drop constraint ${Sql.ident(o.name)}"
        ObjectKind.INDEX -> "drop index ${Sql.ident(o.table.substringBefore('.'))}.${Sql.ident(o.name)}"
        ObjectKind.TRIGGER -> "alter table ${Sql.table(o.table)} disable trigger ${Sql.ident(o.name)}"
    }

    private fun restoreSql(o: SavedObject) = when (o.kind) {
        ObjectKind.FOREIGN_KEY -> "alter table ${Sql.table(o.table)} add constraint ${Sql.ident(o.name)} ${o.definition}"
        ObjectKind.INDEX -> o.definition
        ObjectKind.TRIGGER -> {
            val how = when (o.enabledMode) {
                "R" -> "enable replica trigger"
                "A" -> "enable always trigger"
                else -> "enable trigger"
            }
            "alter table ${Sql.table(o.table)} $how ${Sql.ident(o.name)}"
        }
    }


    private fun <T> transaction(block: () -> T): T = try {
        block().also { c.commit() }
    } catch (e: Throwable) {
        runCatching { c.rollback() }
        throw e
    }

    private fun <T> query(sql: String, tables: List<String>, row: (java.sql.ResultSet) -> T): List<T> =
        SqlError.on(c, Role.TARGET, "reading constraints, indexes and triggers of ${tables.size} table(s)", sql) {
            c.prepareStatement(sql).use { ps ->
                ps.setArray(1, c.createArrayOf("text", tables.toTypedArray()))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }
        }.also { c.commit() }

    companion object {
        private const val FOREIGN_KEY_VIOLATION = "23503"

        /** The SQL that restores [o] by hand; shown when the user gives up on a pending object. */
        fun manualRestoreSql(o: SavedObject): String = when (o.kind) {
            ObjectKind.FOREIGN_KEY -> "alter table ${Sql.table(o.table)} add constraint ${Sql.ident(o.name)} ${o.definition};"
            ObjectKind.INDEX -> "${o.definition};"
            ObjectKind.TRIGGER -> "alter table ${Sql.table(o.table)} enable trigger ${Sql.ident(o.name)};"
        }
    }
}
