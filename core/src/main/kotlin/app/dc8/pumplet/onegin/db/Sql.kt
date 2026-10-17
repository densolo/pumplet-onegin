package app.dc8.pumplet.onegin.db

/** Identifier quoting for statements built from catalog names. */
object Sql {
    fun ident(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /** `schema.table` (split at the first dot) as a quoted, qualified name. */
    fun table(qualified: String): String {
        val schema = qualified.substringBefore('.')
        val table = qualified.substringAfter('.')
        return "${ident(schema)}.${ident(table)}"
    }
}
