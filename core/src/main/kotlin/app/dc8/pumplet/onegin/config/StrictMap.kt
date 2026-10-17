package app.dc8.pumplet.onegin.config

import java.math.BigInteger

/**
 * Typed, strict access to one YAML mapping. Every accessor records the key as
 * known; [finish] then reports any key nobody asked for as unknown. Problems are
 * appended to [errors] so that all of them can be reported at once. Messages
 * mention types, never values.
 */
internal class StrictMap(
    private val path: String,
    private val map: Map<*, *>,
    private val errors: MutableList<String>,
) {
    private val known = linkedSetOf<String>()

    fun keyPath(k: String) = if (path.isEmpty()) k else "$path.$k"

    fun reportError(message: String) {
        errors += message
    }

    /** Raw access; marks the key as known. A key present with a null value counts as absent. */
    fun raw(k: String): Any? {
        known += k
        return map[k]
    }

    fun string(k: String, required: Boolean = false): String? = when (val v = raw(k)) {
        null -> { if (required) errors += "${keyPath(k)}: required"; null }
        is String -> if (v.isBlank()) { errors += "${keyPath(k)}: must not be empty"; null } else v
        else -> { errors += "${keyPath(k)}: expected a string, got ${typeName(v)}"; null }
    }

    fun long(k: String, default: Long, min: Long = 1): Long {
        val v = raw(k) ?: return default
        val n = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is BigInteger -> { errors += "${keyPath(k)}: number is too large"; return default }
            else -> { errors += "${keyPath(k)}: expected an integer, got ${typeName(v)}"; return default }
        }
        if (n < min) { errors += "${keyPath(k)}: must be at least $min"; return default }
        return n
    }

    fun int(k: String, default: Int, min: Int = 1): Int {
        val n = long(k, default.toLong(), min.toLong())
        if (n > Int.MAX_VALUE) { errors += "${keyPath(k)}: number is too large"; return default }
        return n.toInt()
    }

    fun bool(k: String, default: Boolean): Boolean = when (val v = raw(k)) {
        null -> default
        is Boolean -> v
        else -> { errors += "${keyPath(k)}: expected true or false, got ${typeName(v)}"; default }
    }

    fun stringList(k: String): List<String> = when (val v = raw(k)) {
        null -> emptyList()
        is List<*> -> v.mapIndexedNotNull { i, item ->
            if (item is String && item.isNotBlank()) item
            else { errors += "${keyPath(k)}[$i]: expected a non-empty string, got ${typeName(item)}"; null }
        }
        else -> { errors += "${keyPath(k)}: expected a list, got ${typeName(v)}"; emptyList() }
    }

    /** A nested mapping; when absent, an empty one (so defaults apply), or null with an error if [required]. */
    fun obj(k: String, required: Boolean = false): StrictMap? = when (val v = raw(k)) {
        null -> if (required) { errors += "${keyPath(k)}: required"; null } else StrictMap(keyPath(k), emptyMap<String, Any>(), errors)
        is Map<*, *> -> StrictMap(keyPath(k), v, errors)
        else -> { errors += "${keyPath(k)}: expected a mapping, got ${typeName(v)}"; null }
    }

    /** Reports every key that no accessor asked for. Call after all accessors. */
    fun finish() {
        for (k in map.keys) {
            if (k is String && k in known) continue
            val name = k.toString()
            val hint = suggest(name)?.let { " (did you mean '$it'?)" } ?: ""
            errors += "${keyPath(name)}: unknown key$hint; allowed: ${known.joinToString(", ")}"
        }
    }

    private fun suggest(name: String): String? =
        known.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: known.map { it to editDistance(it.lowercase(), name.lowercase()) }
                .filter { it.second <= 2 }.minByOrNull { it.second }?.first

    companion object {
        fun typeName(v: Any?): String = when (v) {
            null -> "nothing"
            is String -> "a string"
            is Boolean -> "a boolean"
            is Number -> "a number"
            is List<*> -> "a list"
            is Map<*, *> -> "a mapping"
            else -> "a ${v.javaClass.simpleName}"
        }

        private fun editDistance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            }
            return d[a.length][b.length]
        }
    }
}
