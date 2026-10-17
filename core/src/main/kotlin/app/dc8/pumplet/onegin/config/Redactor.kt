package app.dc8.pumplet.onegin.config

/**
 * Last line of defence against leaking passwords: replaces every known secret
 * value in a string. Values shorter than [MIN_LENGTH] are skipped, since scrubbing
 * them would mangle ordinary text far more than it would protect anything.
 */
class Redactor(secretValues: Collection<String>) {
    private val values = secretValues.filter { it.length >= MIN_LENGTH }.distinct().sortedByDescending { it.length }

    fun scrub(text: String?): String {
        if (text == null) return ""
        var result: String = text
        for (v in values) result = result.replace(v, MASK)
        return result
    }

    companion object {
        const val MASK = "****"
        const val MIN_LENGTH = 4
        val NONE = Redactor(emptyList())

        private val credentialParams = setOf("user", "password", "sslpassword")
        private val userInfo = Regex("//[^/?#@]*@")

        /** The JDBC URL with any userinfo and user/password query parameters removed. */
        fun safeJdbcUrl(url: String): String {
            val noUserInfo = userInfo.replaceFirst(url, "//")
            val q = noUserInfo.indexOf('?')
            if (q < 0) return noUserInfo
            val kept = noUserInfo.substring(q + 1).split('&')
                .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in credentialParams }
            val base = noUserInfo.substring(0, q)
            return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }

        /** True if the URL carries a password, either as userinfo or a query parameter. */
        fun urlHasPassword(url: String): Boolean {
            val info = userInfo.find(url)?.value
            if (info != null && info.contains(':')) return true
            val q = url.indexOf('?')
            if (q < 0) return false
            return url.substring(q + 1).split('&').any {
                it.substringBefore('=').lowercase() in setOf("password", "sslpassword")
            }
        }
    }
}
