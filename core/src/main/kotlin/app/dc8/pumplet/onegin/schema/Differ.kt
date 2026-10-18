package app.dc8.pumplet.onegin.schema

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.config.TableFilter
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.state.StateStore
import java.nio.file.Files
import java.nio.file.Path

/** Runs the `diff` stage: reads both catalogs over read-only connections and compares them. */
class Differ(private val config: JobConfig) {
    fun run(): DiffReport {
        val schemas = config.source.schemas
        val source = Db.connect(config.source, Role.SOURCE).use { CatalogReader.read(it, schemas) }
        val target = Db.connect(config.target, Role.TARGET, readOnly = true).use { CatalogReader.read(it, schemas, Role.TARGET) }
        return SchemaDiff(
            schemas = schemas,
            filter = TableFilter(config.tables),
            chunking = config.chunking,
            allowTypeDifferences = config.diff.allowTypeDifferences,
            stateTable = config.state.table.qualified,
        ).compare(source, target, config.configHash(), Db.describe(config.source), Db.describe(config.target))
    }

    /** `job.yaml` -> `job.diff.json`, next to the job file. */
    fun reportPath(): Path {
        val job = config.jobFile
        return job.resolveSibling(job.fileName.toString().substringBeforeLast('.') + ".diff.json")
    }

    /** Writes [report] as JSON, scrubbed of secrets; returns where. */
    fun write(report: DiffReport): Path {
        val path = reportPath()
        val text = StateStore.json.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        Files.writeString(path, config.redactor.scrub(text) + "\n")
        return path
    }
}
