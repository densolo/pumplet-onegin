package app.dc8.pumplet.onegin.config

/**
 * Matches `schema.table` names against include/exclude globs (`*` = any run of
 * characters, `?` = one character, case-sensitive). An empty include list means all.
 */
class TableFilter(include: List<String>, exclude: List<String>) {
    private val include = include.map(::globToRegex)
    private val exclude = exclude.map(::globToRegex)

    constructor(config: TablesConfig) : this(config.include, config.exclude)

    fun matches(schema: String, table: String): Boolean {
        val name = "$schema.$table"
        return (include.isEmpty() || include.any { it.matches(name) }) && exclude.none { it.matches(name) }
    }

    companion object {
        fun globToRegex(glob: String): Regex = Regex(buildString {
            for (c in glob) when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
        })
    }
}
