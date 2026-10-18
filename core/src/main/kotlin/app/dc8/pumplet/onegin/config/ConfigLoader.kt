package app.dc8.pumplet.onegin.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

data class LoadedConfig(val config: JobConfig, val warnings: List<String>)

/**
 * Loads a job file and the secrets file it references.
 *
 * @param secretsOverride `--secrets` from the command line; replaces the job file's `secrets:` path.
 * @param cwd directory that a relative [secretsOverride] resolves against.
 */
class ConfigLoader(
    private val cwd: Path = Paths.get("").toAbsolutePath(),
    private val home: Path = Paths.get(System.getProperty("user.home")),
) {
    fun load(jobFile: Path, secretsOverride: String? = null): LoadedConfig {
        val job = cwd.resolve(jobFile).normalize()
        val root = readJobYaml(job)
        val errors = mutableListOf<String>()
        val refs = mutableListOf<SecretRef>()

        val r = StrictMap("", root, errors)
        val secretsPath = r.string("secrets")
        val source = r.obj("source", required = true)?.let { db(it, refs, isSource = true) }
        val target = r.obj("target", required = true)?.let { db(it, refs, isSource = false) }
        val tables = r.obj("tables")?.let { t ->
            TablesConfig(globs(t, "include"), globs(t, "exclude")).also { t.finish() }
        }
        val threads = r.obj("threads")?.let { t ->
            ThreadsConfig(t.int("readers", 10), t.int("writers", 4)).also { t.finish() }
        }
        val chunking = r.obj("chunking")?.let { c ->
            ChunkingConfig(c.long("rowsPerChunk", 200_000), c.long("minRowsToSplit", 500_000)).also { c.finish() }
        }
        val buffer = r.obj("buffer")?.let { b -> BufferConfig(b.int("maxMemoryMb", 1024)).also { b.finish() } }
        val constraints = r.obj("constraints")?.let { c ->
            ConstraintsConfig(
                c.bool("dropForeignKeys", true),
                c.bool("dropSecondaryIndexes", false),
                c.bool("disableUserTriggers", true),
            ).also { c.finish() }
        }
        val largeObjects = r.obj("largeObjects")?.let { l ->
            val raw = l.string("mode")
            val mode = raw?.let { LargeObjectMode.of(it) ?: LargeObjectMode.BULK.also { _ ->
                l.reportError("${l.keyPath("mode")}: expected one of ${LargeObjectMode.entries.joinToString(", ") { it.key }}")
            } } ?: LargeObjectMode.BULK
            LargeObjectsConfig(mode).also { l.finish() }
        }
        val sequences = r.obj("sequences")?.let { q -> SequencesConfig(q.bool("raiseToSource", true)).also { q.finish() } }
        val diff = r.obj("diff")?.let { d -> DiffConfig(d.bool("allowTypeDifferences", false)).also { d.finish() } }
        // null = default: resolved below, once the source schemas are known to be valid.
        val stateTable = r.obj("state")?.let { st ->
            val raw = st.string("table")
            val table = raw?.let {
                TableName.parse(it) ?: null.also {
                    st.reportError("${st.keyPath("table")}: expected <schema>.<table> in lower-case letters, digits and '_'")
                }
            }
            st.finish()
            table
        }
        r.finish()
        if (errors.isNotEmpty()) throw ConfigException(errors.map { "$job: $it" })

        // Secrets stage.
        val warnings = mutableListOf<String>()
        val secretsFile = when {
            secretsOverride != null -> expand(secretsOverride, cwd)
            secretsPath != null -> expand(secretsPath, job.parent)
            else -> null
        }
        val secrets: Map<String, String> = when {
            secretsFile != null -> readSecrets(secretsFile, warnings)
            refs.isNotEmpty() -> throw ConfigException(
                "$job: ${refs.first().field} references \${secret:${refs.first().name}} but no secrets file is " +
                    "configured; set 'secrets:' in the job file or pass --secrets <file>"
            )
            else -> emptyMap()
        }
        val resolved = mutableMapOf<String, Secret>()
        for (ref in refs) {
            val value = secrets[ref.name]
            if (value == null) errors += "${ref.field}: secret '${ref.name}' not found in secrets file $secretsFile"
            else resolved[ref.field] = Secret(ref.name, value)
        }
        if (errors.isNotEmpty()) throw ConfigException(errors)

        val config = JobConfig(
            jobFile = job,
            secretsFile = secretsFile,
            source = source!!.copy(password = resolved["source.password"]),
            target = target!!.copy(password = resolved["target.password"]),
            tables = tables!!, threads = threads!!, chunking = chunking!!, buffer = buffer!!, constraints = constraints!!,
            diff = diff!!,
            sequences = sequences!!,
            largeObjects = largeObjects!!,
            state = stateTable?.let { StateConfig(it) } ?: StateConfig.default(source!!.schemas),
            redactor = Redactor(secrets.values),
        )
        return LoadedConfig(config, warnings)
    }

    private data class SecretRef(val field: String, val name: String)

    private fun db(m: StrictMap, refs: MutableList<SecretRef>, isSource: Boolean): DbConfig {
        val url = m.string("url", required = true)
        if (url != null) {
            if (!url.startsWith("jdbc:postgresql:")) {
                m.reportError("${m.keyPath("url")}: expected a jdbc:postgresql: URL")
            } else if (Redactor.urlHasPassword(url)) {
                m.reportError("${m.keyPath("url")}: must not contain a password; use '${m.keyPath("password")}: \${secret:<name>}' instead")
            }
        }
        val user = m.string("user")
        when (val pw = m.raw("password")) {
            null -> {}
            is String -> {
                val name = SECRET_REF.matchEntire(pw.trim())?.groupValues?.get(1)
                if (name != null) refs += SecretRef(m.keyPath("password"), name)
                else m.reportError(
                    "${m.keyPath("password")}: plain-text passwords are not allowed in the job file; " +
                        "move it to the secrets file and reference it as \${secret:<name>}"
                )
            }
            else -> m.reportError("${m.keyPath("password")}: expected a secret reference like \${secret:<name>}")
        }
        val schemas = if (isSource) m.stringList("schemas") else emptyList()
        if (isSource && (m.raw("schemas") as? List<*>?).isNullOrEmpty()) {
            m.reportError("${m.keyPath("schemas")}: list at least one schema to copy")
        }
        m.finish()
        return DbConfig(url ?: "", user, null, schemas)
    }

    private fun globs(m: StrictMap, key: String): List<String> {
        val list = m.stringList(key)
        list.forEachIndexed { i, g ->
            if ('.' !in g) m.reportError("${m.keyPath(key)}[$i]: expected <schema>.<table> (globs allowed, e.g. app.order*)")
        }
        return list
    }

    private fun readJobYaml(job: Path): Map<*, *> {
        val text = readText(job, "job file")
        val doc = try {
            Yaml(SafeConstructor(loaderOptions())).load<Any?>(text)
        } catch (e: YAMLException) {
            throw ConfigException("$job: invalid YAML: ${e.message}")
        }
        return doc as? Map<*, *> ?: throw ConfigException("$job: expected a mapping at the top level")
    }

    /**
     * Every scalar is read as a plain string (no implicit int/bool/null typing), so
     * `pw: 0123` stays "0123". Parser errors report only the position, because the
     * parser's context snippet would show the line containing the password.
     */
    private fun readSecrets(file: Path, warnings: MutableList<String>): Map<String, String> {
        val text = readText(file, "secrets file")
        checkPermissions(file, warnings)
        val stringsOnly = object : Resolver() {
            override fun addImplicitResolvers() {}
        }
        val yaml = Yaml(SafeConstructor(loaderOptions()), Representer(DumperOptions()), DumperOptions(), loaderOptions(), stringsOnly)
        val doc = try {
            yaml.load<Any?>(text)
        } catch (e: MarkedYAMLException) {
            val mark = e.problemMark ?: e.contextMark
            val where = mark?.let { " at line ${it.line + 1}, column ${it.column + 1}" } ?: ""
            throw ConfigException("$file: invalid YAML$where (details hidden to avoid printing secret values)")
        } catch (e: YAMLException) {
            throw ConfigException("$file: invalid YAML (details hidden to avoid printing secret values)")
        }
        if (doc == null || doc == "") return emptyMap()
        if (doc !is Map<*, *>) throw ConfigException("$file: expected a mapping of secret names to values")
        val errors = mutableListOf<String>()
        val result = linkedMapOf<String, String>()
        for ((k, v) in doc) {
            when {
                k !is String || !SECRET_NAME.matches(k) ->
                    errors += "$file: invalid secret name '$k' (use letters, digits, '_', '-', '.')"
                v !is String -> errors += "$file: '$k' must be a plain string value"
                v.isEmpty() -> errors += "$file: '$k' is empty"
                else -> result[k] = v
            }
        }
        if (errors.isNotEmpty()) throw ConfigException(errors)
        return result
    }

    private fun checkPermissions(file: Path, warnings: MutableList<String>) {
        val perms = try {
            Files.getPosixFilePermissions(file)
        } catch (e: UnsupportedOperationException) {
            return
        } catch (e: IOException) {
            return
        }
        val open = perms.filter { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }
        if (open.isNotEmpty()) {
            val who = buildList {
                if (open.any { it.name.startsWith("GROUP_") }) add("group")
                if (open.any { it.name.startsWith("OTHERS_") }) add("others")
            }.joinToString(" and ")
            warnings += "secrets file $file is accessible by $who (${posix(perms)}); run: chmod 600 $file"
        }
    }

    private fun posix(perms: Set<PosixFilePermission>): String =
        java.nio.file.attribute.PosixFilePermissions.toString(perms)

    private fun readText(file: Path, what: String): String {
        if (!Files.exists(file)) throw ConfigException("$what not found: $file")
        if (!Files.isRegularFile(file)) throw ConfigException("$what is not a regular file: $file")
        return try {
            Files.readString(file)
        } catch (e: IOException) {
            throw ConfigException("cannot read $what $file: ${e.javaClass.simpleName}")
        }
    }

    private fun expand(raw: String, base: Path): Path {
        val p = when {
            raw == "~" -> home
            raw.startsWith("~/") -> home.resolve(raw.substring(2))
            else -> Paths.get(raw)
        }
        return base.resolve(p).normalize()
    }

    private fun loaderOptions() = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 10
    }

    companion object {
        private val SECRET_NAME = Regex("[A-Za-z0-9_.-]+")
        private val SECRET_REF = Regex("""\$\{secret:([A-Za-z0-9_.-]+)\}""")
    }
}
