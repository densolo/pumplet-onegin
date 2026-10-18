package app.dc8.pumplet.onegin.schema

import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.db.SqlError
import java.sql.Connection

data class Column(
    val name: String,
    /** As `format_type` prints it, e.g. `character varying(20)`, `integer[]`, `app.status`. */
    val type: String,
    /** `pg_type.typname`, e.g. `oid` for large-object references. */
    val typeName: String,
    val notNull: Boolean,
    val default: String?,
    /** `a` = GENERATED ALWAYS AS IDENTITY, `d` = BY DEFAULT, null = not an identity column. */
    val identity: Char?,
    /** Generation expression of a generated column, null for ordinary columns. */
    val generated: String?,
)

/** A primary key, unique, foreign key, check or exclusion constraint. */
data class Constraint(val name: String, val type: Char, val definition: String) {
    val typeName get() = when (type) {
        'p' -> "primary key"
        'u' -> "unique constraint"
        'f' -> "foreign key"
        'c' -> "check constraint"
        'x' -> "exclusion constraint"
        else -> "constraint"
    }
}

/** An index that doesn't back a constraint. [definition] has the index name removed so it compares across databases. */
data class Index(val name: String, val definition: String)

data class Table(
    val schema: String,
    val name: String,
    val estRows: Long,
    val readable: Boolean,
    val columns: List<Column>,
    val primaryKey: List<String>,
    val constraints: List<Constraint>,
    val indexes: List<Index>,
) {
    val qualified get() = "$schema.$name"
    fun column(name: String) = columns.firstOrNull { it.name == name }
}

/** The tables of some schemas in one database, keyed by `schema.table`. */
data class Catalog(val schemas: Set<String>, val tables: Map<String, Table>)

/**
 * Reads table definitions from the PostgreSQL catalogs. One query per kind of
 * object for all schemas at once, so it stays quick over a high-latency link.
 * Only reads; safe on a read-only source connection.
 */
object CatalogReader {
    /** @param role which side [c] is, so a failing query says where it ran. */
    fun read(c: Connection, schemas: List<String>, role: Role = Role.SOURCE): Catalog {
        // Schema-qualify every name that pg_get_*def and format_type print, so both sides compare equal.
        c.createStatement().use { it.execute("set search_path = pg_catalog") }
        val existing = query(c, role, "select nspname from pg_namespace where nspname = any(?)", schemas) { it.getString(1) }.toSet()

        data class Rel(val oid: Long, val schema: String, val name: String, val rows: Long, val readable: Boolean)
        val rels = query(
            c,
            role,
            """
            select c.oid, n.nspname, c.relname, c.reltuples::bigint, has_table_privilege(c.oid, 'SELECT')
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where c.relkind in ('r', 'p') and not c.relispartition and n.nspname = any(?)
            """.trimIndent(),
            schemas,
        ) { Rel(it.getLong(1), it.getString(2), it.getString(3), it.getLong(4), it.getBoolean(5)) }

        val columns = query(
            c,
            role,
            """
            select a.attrelid, a.attname, format_type(a.atttypid, a.atttypmod), t.typname, a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid), a.attidentity, a.attgenerated
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            join pg_type t on t.oid = a.atttypid
            left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
            where a.attnum > 0 and not a.attisdropped and c.relkind in ('r', 'p') and not c.relispartition
              and n.nspname = any(?)
            order by a.attrelid, a.attnum
            """.trimIndent(),
            schemas,
        ) {
            val expr = it.getString(6)
            val generated = it.getString(8).orEmpty().isNotEmpty()
            it.getLong(1) to Column(
                name = it.getString(2),
                type = it.getString(3),
                typeName = it.getString(4),
                notNull = it.getBoolean(5),
                default = if (generated) null else expr,
                identity = it.getString(7).orEmpty().firstOrNull(),
                generated = if (generated) expr else null,
            )
        }.groupBy({ it.first }, { it.second })

        val primaryKeys = query(
            c,
            role,
            """
            select i.indrelid, a.attname
            from pg_index i
            join pg_class c on c.oid = i.indrelid
            join pg_namespace n on n.oid = c.relnamespace
            cross join lateral unnest(i.indkey) with ordinality k(attnum, ord)
            join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.attnum
            where i.indisprimary and n.nspname = any(?)
            order by i.indrelid, k.ord
            """.trimIndent(),
            schemas,
        ) { it.getLong(1) to it.getString(2) }.groupBy({ it.first }, { it.second })

        val constraints = query(
            c,
            role,
            """
            select con.conrelid, con.conname, con.contype, pg_get_constraintdef(con.oid)
            from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
            where con.contype in ('p', 'u', 'f', 'c', 'x') and n.nspname = any(?)
            order by con.conrelid, con.conname
            """.trimIndent(),
            schemas,
        ) { it.getLong(1) to Constraint(it.getString(2), it.getString(3).single(), it.getString(4)) }
            .groupBy({ it.first }, { it.second })

        val indexes = query(
            c,
            role,
            """
            select i.indrelid, ic.relname, pg_get_indexdef(i.indexrelid)
            from pg_index i
            join pg_class ic on ic.oid = i.indexrelid
            join pg_class c on c.oid = i.indrelid
            join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = any(?)
              and not exists (
                  select 1 from pg_constraint con
                  where con.conindid = i.indexrelid and con.conrelid = i.indrelid and con.contype in ('p', 'u', 'x'))
            order by i.indrelid, ic.relname
            """.trimIndent(),
            schemas,
        ) { it.getLong(1) to Index(it.getString(2), withoutIndexName(it.getString(3))) }
            .groupBy({ it.first }, { it.second })

        val tables = rels.associate { r ->
            "${r.schema}.${r.name}" to Table(
                schema = r.schema,
                name = r.name,
                estRows = r.rows,
                readable = r.readable,
                columns = columns[r.oid].orEmpty(),
                primaryKey = primaryKeys[r.oid].orEmpty(),
                constraints = constraints[r.oid].orEmpty(),
                indexes = indexes[r.oid].orEmpty(),
            )
        }
        return Catalog(existing, tables)
    }

    private val INDEX_NAME = Regex("""^CREATE (UNIQUE )?INDEX ("(?:[^"]|"")+"|\S+) ON """)

    /** `CREATE INDEX orders_x ON app.orders USING btree (x)` -> `CREATE INDEX ON app.orders USING btree (x)`. */
    fun withoutIndexName(def: String): String =
        INDEX_NAME.find(def)?.let { def.replaceRange(it.range, "CREATE ${it.groupValues[1]}INDEX ON ") } ?: def

    private fun <T> query(c: Connection, role: Role, sql: String, schemas: List<String>, row: (java.sql.ResultSet) -> T): List<T> =
        SqlError.on(c, role, "reading the catalog of schemas ${schemas.joinToString(", ")}", sql) {
            c.prepareStatement(sql).use { ps ->
                ps.setArray(1, c.createArrayOf("text", schemas.toTypedArray()))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }
        }
}
