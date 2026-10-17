package app.dc8.pumplet.onegin.ui

import app.dc8.pumplet.onegin.config.ConfigException
import app.dc8.pumplet.onegin.config.ConfigLoader
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Everything the profile form edits; saved as an ordinary job file plus its secrets file. */
data class Profile(
    val name: String,
    val sourceUrl: String = "jdbc:postgresql://host:5432/db",
    val sourceUser: String = "",
    val sourcePassword: String = "",
    val schemas: List<String> = emptyList(),
    val targetUrl: String = "jdbc:postgresql://localhost:5432/db",
    val targetUser: String = "",
    val targetPassword: String = "",
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val readers: Int = 10,
    val writers: Int = 4,
    val rowsPerChunk: Long = 200_000,
    val minRowsToSplit: Long = 500_000,
    val maxMemoryMb: Int = 1024,
    val dropForeignKeys: Boolean = true,
    val dropSecondaryIndexes: Boolean = false,
    val disableUserTriggers: Boolean = true,
    val allowTypeDifferences: Boolean = false,
    /** Empty = the default, `<first schema>.pumplet_onegin_state`. */
    val stateTable: String = "",
)

/** Labels of the advanced settings that differ from the defaults (the form hides them unless asked). */
fun Profile.customisedAdvanced(): List<String> {
    val d = Profile(name)
    return listOfNotNull(
        "readers".takeIf { readers != d.readers },
        "writers".takeIf { writers != d.writers },
        "rows per chunk".takeIf { rowsPerChunk != d.rowsPerChunk },
        "min rows to split".takeIf { minRowsToSplit != d.minRowsToSplit },
        "buffer".takeIf { maxMemoryMb != d.maxMemoryMb },
        "drop foreign keys".takeIf { dropForeignKeys != d.dropForeignKeys },
        "drop secondary indexes".takeIf { dropSecondaryIndexes != d.dropSecondaryIndexes },
        "disable user triggers".takeIf { disableUserTriggers != d.disableUserTriggers },
        "allow type differences".takeIf { allowTypeDifferences != d.allowTypeDifferences },
        "state table".takeIf { stateTable != d.stateTable },
    )
}

/** Offline check of a saved profile (ConfigLoader, no connections). */
sealed interface Validation {
    data class Ok(val warnings: List<String>) : Validation
    data class Invalid(val problems: List<String>) : Validation
}

/**
 * Job profiles under [root] (default `~/.config/pumplet-onegin/profiles`), one folder each:
 * `job.yaml` and `secrets.yaml` (passwords, mode 600). The job's log and diff report land in
 * the same folder. Any profile also works from the command line:
 * `pumplet-onegin run --config ~/.config/pumplet-onegin/profiles/<name>/job.yaml`.
 */
class ProfileStore(val root: Path = defaultRoot()) {
    fun dir(name: String): Path = root.resolve(name)
    fun jobFile(name: String): Path = dir(name).resolve(JOB)
    fun secretsFile(name: String): Path = dir(name).resolve(SECRETS)

    /** Profile names, sorted. */
    fun list(): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        return root.listDirectoryEntries().filter { it.isDirectory() && Files.exists(it.resolve(JOB)) }.map { it.name }.sortedBy { it.lowercase() }
    }

    fun exists(name: String) = Files.exists(jobFile(name))

    /** Reads a profile back into the form's fields. */
    fun load(name: String): Profile {
        val job = yamlMap(jobFile(name))
        val secrets: Map<*, *> = if (Files.exists(secretsFile(name))) yamlMap(secretsFile(name)) else emptyMap<Any, Any>()
        fun section(key: String) = job[key] as? Map<*, *> ?: emptyMap<Any, Any>()
        fun Map<*, *>.str(key: String) = this[key]?.toString().orEmpty()
        fun Map<*, *>.list(key: String) = (this[key] as? List<*>)?.map { it.toString() }.orEmpty()
        fun Map<*, *>.num(key: String, default: Long) = (this[key] as? Number)?.toLong() ?: default
        fun Map<*, *>.flag(key: String, default: Boolean) = this[key] as? Boolean ?: default
        /** `${secret:x}` -> the value of x in the secrets file. */
        fun Map<*, *>.password(): String {
            val ref = str("password")
            val key = Regex("""\$\{secret:([^}]+)}""").matchEntire(ref.trim())?.groupValues?.get(1) ?: return ""
            return secrets[key]?.toString().orEmpty()
        }
        val src = section("source")
        val dst = section("target")
        val tables = section("tables")
        val threads = section("threads")
        val chunking = section("chunking")
        val buffer = section("buffer")
        val constraints = section("constraints")
        val d = Profile(name)
        return Profile(
            name = name,
            sourceUrl = src.str("url"), sourceUser = src.str("user"), sourcePassword = src.password(), schemas = src.list("schemas"),
            targetUrl = dst.str("url"), targetUser = dst.str("user"), targetPassword = dst.password(),
            include = tables.list("include"), exclude = tables.list("exclude"),
            readers = threads.num("readers", d.readers.toLong()).toInt(), writers = threads.num("writers", d.writers.toLong()).toInt(),
            rowsPerChunk = chunking.num("rowsPerChunk", d.rowsPerChunk), minRowsToSplit = chunking.num("minRowsToSplit", d.minRowsToSplit),
            maxMemoryMb = buffer.num("maxMemoryMb", d.maxMemoryMb.toLong()).toInt(),
            dropForeignKeys = constraints.flag("dropForeignKeys", d.dropForeignKeys),
            dropSecondaryIndexes = constraints.flag("dropSecondaryIndexes", d.dropSecondaryIndexes),
            disableUserTriggers = constraints.flag("disableUserTriggers", d.disableUserTriggers),
            allowTypeDifferences = section("diff").flag("allowTypeDifferences", false),
            stateTable = section("state").str("table"),
        )
    }

    /**
     * Writes [p] (renaming the folder from [previousName] if the name changed) and
     * returns the offline validation of the result.
     */
    fun save(p: Profile, previousName: String? = null): Validation {
        require(NAME.matches(p.name)) { "profile name: use letters, digits, '.', '_' and '-'" }
        if (previousName != null && previousName != p.name) {
            require(!exists(p.name)) { "a profile named '${p.name}' already exists" }
            if (Files.exists(dir(previousName))) Files.move(dir(previousName), dir(p.name), StandardCopyOption.ATOMIC_MOVE)
        } else if (previousName == null) {
            require(!exists(p.name)) { "a profile named '${p.name}' already exists" }
        }
        Files.createDirectories(dir(p.name))
        runCatching { Files.setPosixFilePermissions(dir(p.name), PosixFilePermissions.fromString("rwx------")) }
        writePrivate(secretsFile(p.name), secretsYaml(p))
        Files.writeString(jobFile(p.name), jobYaml(p))
        return validate(p.name)
    }

    fun delete(name: String) {
        val d = dir(name)
        if (!Files.exists(d)) return
        Files.walk(d).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    /** A free name like "uat-copy", "uat-copy-2", ... */
    fun freeName(base: String): String = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { !exists(it) }

    /** Loads the job like the CLI does, without connecting anywhere. */
    fun validate(name: String): Validation = try {
        val loaded = ConfigLoader().load(jobFile(name))
        Validation.Ok(loaded.warnings)
    } catch (e: ConfigException) {
        Validation.Invalid(e.problems.map { it.removePrefix("${jobFile(name)}: ") })
    }

    private fun jobYaml(p: Profile) = buildString {
        appendLine("# pumplet-onegin job \"${p.name}\", written by the pumplet-onegin UI.")
        appendLine("# Passwords are in secrets.yaml next to this file. Run it from a shell with:")
        appendLine("#   pumplet-onegin <command> --config ${jobFile(p.name)}")
        appendLine("secrets: $SECRETS")
        appendLine("source:")
        appendLine("  url: ${q(p.sourceUrl)}")
        if (p.sourceUser.isNotBlank()) appendLine("  user: ${q(p.sourceUser)}")
        if (p.sourcePassword.isNotEmpty()) appendLine("  password: \${secret:source}")
        appendLine("  schemas: ${list(p.schemas)}")
        appendLine("target:")
        appendLine("  url: ${q(p.targetUrl)}")
        if (p.targetUser.isNotBlank()) appendLine("  user: ${q(p.targetUser)}")
        if (p.targetPassword.isNotEmpty()) appendLine("  password: \${secret:target}")
        appendLine("tables:")
        appendLine("  include: ${list(p.include)}")
        appendLine("  exclude: ${list(p.exclude)}")
        appendLine("threads:")
        appendLine("  readers: ${p.readers}")
        appendLine("  writers: ${p.writers}")
        appendLine("chunking:")
        appendLine("  rowsPerChunk: ${p.rowsPerChunk}")
        appendLine("  minRowsToSplit: ${p.minRowsToSplit}")
        appendLine("buffer:")
        appendLine("  maxMemoryMb: ${p.maxMemoryMb}")
        appendLine("constraints:")
        appendLine("  dropForeignKeys: ${p.dropForeignKeys}")
        appendLine("  dropSecondaryIndexes: ${p.dropSecondaryIndexes}")
        appendLine("  disableUserTriggers: ${p.disableUserTriggers}")
        appendLine("diff:")
        appendLine("  allowTypeDifferences: ${p.allowTypeDifferences}")
        if (p.stateTable.isNotBlank()) {
            appendLine("state:")
            appendLine("  table: ${q(p.stateTable)}")
        }
    }

    private fun secretsYaml(p: Profile) = buildString {
        if (p.sourcePassword.isNotEmpty()) appendLine("source: ${q(p.sourcePassword)}")
        if (p.targetPassword.isNotEmpty()) appendLine("target: ${q(p.targetPassword)}")
    }

    /** Creates the file as 600 before writing, so the passwords are never readable by others. */
    private fun writePrivate(file: Path, text: String) {
        if (!Files.exists(file)) {
            runCatching { Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
                .onFailure { Files.createFile(file) }
        }
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(file, text)
    }

    private fun yamlMap(file: Path): Map<*, *> =
        Yaml(SafeConstructor(LoaderOptions())).load<Any?>(Files.readString(file)) as? Map<*, *> ?: emptyMap<Any, Any>()

    companion object {
        const val JOB = "job.yaml"
        const val SECRETS = "secrets.yaml"
        val NAME = Regex("[A-Za-z0-9._-]+")

        /** `PUMPLET_ONEGIN_HOME` overrides `~/.config/pumplet-onegin`. */
        fun defaultRoot(): Path {
            val home = System.getenv("PUMPLET_ONEGIN_HOME")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".config", "pumplet-onegin")
            return home.resolve("profiles")
        }

        /** A double-quoted YAML scalar. */
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun list(items: List<String>) = items.joinToString(", ", "[", "]") { q(it) }
    }
}
