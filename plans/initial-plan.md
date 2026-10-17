Build `pumplet-onegin`: a Kotlin tool that copies data from one PostgreSQL database to another (typical use: a remote UAT database → my local Docker Postgres). The target schema is created beforehand by Liquibase; copying the schema is OUT OF SCOPE. The tool copies data only, fast and resumably.

Before writing code, give me a short plan (modules, key classes, config format, state model, threading model) and wait for my OK.

## Tech
- Kotlin, JVM 21, Maven multi-module:
    - `core`: a library with no CLI or terminal deps, so a UI can be added later. Public API: run each stage, observe progress as a Kotlin Flow of events (stage started/finished, table started/progress/finished, warnings, errors).
    - `cli`: a runnable fat jar on top of `core`.
- PostgreSQL JDBC driver, HikariCP, Jackson (YAML + JSON), Clikt (CLI), Mordant (terminal progress bars), SLF4J + Logback.
- Tests: JUnit 5 + Testcontainers (two Postgres containers), schema created by a Liquibase changelog in test resources.

## Configuration: job file + separate secrets file
The job file is always passed explicitly on the command line; there is no default location and no positional fallback:
`pumplet-onegin <command> --config job.yaml`
Missing `--config` is an error with a usage hint.

Passwords are NEVER in the job file. They live in a separate secrets file that the job file references:

```yaml
# job.yaml
secrets: ~/.pumplet-onegin/uat-local.secrets.yaml   # relative paths resolve against the job file's directory
source:
  url: jdbc:postgresql://uat-host:5432/app
  user: app_ro
  password: ${secret:uat}
  schemas: [app]
target:
  url: jdbc:postgresql://localhost:5432/app
  user: postgres
  password: ${secret:local}
tables:
  include: []            # globs, e.g. app.order*; empty = all
  exclude: [app.audit_*]
threads:
  readers: 10            # source connections (remote: high latency, so more)
  writers: 4             # target connections
chunking:
  rowsPerChunk: 200000
  minRowsToSplit: 500000
buffer:
  maxMemoryMb: 1024
constraints:
  dropForeignKeys: true
  dropSecondaryIndexes: false
  disableUserTriggers: true
```

```yaml
# uat-local.secrets.yaml
uat: "..."
local: "..."
```

Rules:
- `--secrets <file>` on the command line overrides the `secrets:` path in the job file (useful when the same job runs on different machines).
- A `${secret:name}` that isn't found in the secrets file is an error naming the missing key, never the value.
- A plain password literal in the job file is rejected with an error telling the user to move it to the secrets file.
- On load, if the secrets file is readable by group or others (POSIX permissions), print a warning suggesting `chmod 600`.
- Passwords never appear in logs, the diff report, the state table, exception messages, or `status` output. JDBC URLs are logged without credentials. The config hash used for session matching excludes secrets, so changing a password doesn't break resume.
- Validate both files strictly on load: unknown keys are errors, with clear messages.

## Commands / stages
1. `diff`: compare source and target schemas (see Diff). Read-only on both sides.
2. `disable`: save, then drop or disable, target constraints for the selected tables.
3. `copy`: copy data.
4. `enable`: restore everything saved in step 2.
   Plus:
- `run`: diff → disable → copy → enable in one go; stops before `disable` if the diff contains blocking errors.
- `status`: show the current session: stage, saved constraints, per-table progress.
- `fetch-test`: read-only performance test. Reads the selected tables from the source using the same chunking and reader pool, discards the data, and reports rows, bytes, rows/s, MB/s per table and overall. Touches nothing on the target.
- `recover`: re-apply saved constraints/indexes/triggers from the state table, for after a crash, without copying.

## Source is read-only
- Source connections are opened read-only: `readOnly=true` on the pool, `SET default_transaction_read_only = on`, every transaction `READ ONLY`.
- Never execute DDL or DML on the source.
- A consistent snapshot across all reader threads: one coordinator connection runs `REPEATABLE READ`, calls `pg_export_snapshot()` and stays open for the whole copy; each reader transaction does `SET TRANSACTION SNAPSHOT`.
- A resumed session gets a new snapshot. Warn about that in the output.

## Session state: stored in the target database
- One table with a distinctive name in an existing target schema, created automatically: `<first source schema>.pumplet_onegin_state` by default (next to the copied data), configurable as `state.table` (not part of the config hash). Columns: `(session_id, kind, key, payload jsonb, updated_at)`.
- `kind` covers: session (config hash, stage, start/end times), constraint/index/trigger definitions, table progress, chunk progress, timing results.
- The tool's own state table is excluded from diff and copy.
- A session spans every command of one job (same config hash) until `enable` finishes it; each command invocation is a numbered run (command, host, pid, start/end, outcome). One process per target, enforced by an advisory lock; a RUNNING run whose lock is gone is shown as INTERRUPTED.
- `disable` saves each definition (via `pg_get_constraintdef` / `pg_get_indexdef` etc.) in the same transaction that drops it, so the state can never disagree with the database.
- Each copied chunk is committed in the SAME target transaction as its "chunk done" state row. Resume is exactly-once: no duplicates, no gaps.
- Decided while building `disable`/`enable`: `disable` runs the diff stage first and refuses on blocking errors; the non-empty-target guard applies until this session's copy has started (not merely when a session is resumed). Secondary indexes = indexes no constraint uses (`pg_constraint.conindid`). Only enabled user triggers are disabled; their mode (O/R/A) is restored. `enable` restores indexes, then FKs, then triggers, one transaction per object; a pending FK is dropped again (not left NOT VALID), and a NOT VALID FK left by an interrupted `enable` is validated rather than re-added. `enable --skip-pending` records pending objects as SKIPPED and prints their SQL. `enable` completes the session only when nothing is pending and the copy stage is done; `recover` restores without completing.
- `enable` recreates FKs as `NOT VALID`, then runs `VALIDATE CONSTRAINT` separately. On violation, report the constraint and a count of offending rows, keep it in state as pending, and continue with the others. Re-running `enable` retries only the pending ones.

## Protection against overwriting data
- Before `disable`/`copy`: if any selected target table already has rows, refuse to start, unless an unfinished session with the same config hash exists, in which case resume it.
- Starting a new session over a non-empty target is never automatic: the tool refuses and prints the TRUNCATE statement for the user to run themselves. (A `truncateTargets` option existed briefly and was removed on purpose.)
- Refuse to start a new session while another session's constraints are still dropped; point to `enable`/`recover`.

## Diff
Compare the selected tables in both databases and print a readable report (also written as JSON next to the job file):
- Tables: missing in target (skipped), extra in target (ignored).
- Columns: missing or extra (skipped; warn if an extra target column is NOT NULL without a default, because inserts will fail), type differences (blocking for that table unless the config allows it), nullability, defaults, generated columns.
- Primary keys, unique constraints, foreign keys, check constraints, indexes: compare normalized definitions; report missing/extra/different as warnings.
- Summary: which tables will be copied, with which column list, and which will be skipped and why.
- Decided while building it: blocking ERRORs are type differences that aren't a safe widening (unless `diff.allowTypeDifferences: true`), extra target NOT NULL columns without default/identity/generation (every insert would fail), unreadable source tables, and schemas missing on either side. Safe widenings (varchar length up, varchar->text, int->bigint, real->double, more numeric precision) are warnings and switch that table to text-format COPY. Constraints/indexes are matched by name, then by definition (renamed = INFO). Report: `<job>.diff.json` next to the job file.

## Copy
- Column list per table = columns present in both, excluding generated (virtual) columns (`attgenerated <> ''`). Identity columns are copied with their values (handle GENERATED ALWAYS correctly). Afterwards, reset sequences owned by copied tables to max(value)+1.
- Two separate pools: readers (source) and writers (target), connected by a bounded buffer limited by `buffer.maxMemoryMb`; readers block when it's full. Many tables are copied concurrently.
- Large tables (≥ `minRowsToSplit`, by `pg_class.reltuples`) are split into chunks by primary key:
    - single-column numeric PK: ranges from min/max, refined by sampling if the distribution is skewed
    - other or composite PK: chunk boundaries from a `TABLESAMPLE` + ordered sample, read with keyset predicates (`WHERE pk > ? AND pk <= ?`)
    - no PK: one chunk; on resume, truncate and redo that table
- Use `COPY (SELECT cols FROM t WHERE range) TO STDOUT` on the source and `COPY t (cols) FROM STDIN` on the target (pgjdbc CopyManager), with binary format where possible.
- LOBs: tables with `oid` columns referencing large objects (`pg_largeobject`) go through a row-level path: read each LO from the source via LargeObjectManager, create it on the target with the same OID (`lo_create(oid)`), then insert the row. Stream large `bytea`/`text` values; never load a whole huge value into memory more than once.
- After copying each table: `ANALYZE` it.
- Decided while building `copy`: each chunk = one writer task that starts its reader and streams through its own bounded pipe; at most `readers` chunks in flight, each with `maxMemoryMb / readers`, so memory is bounded and readers can't deadlock writers. Chunk plans (bounds) are stored per session and reused on resume; the database verifies bounds are strictly increasing before copying (a sample-ordering bug produced overlapping chunks during development). Sampling uses TABLESAMPLE SYSTEM sized to span many pages. Failed chunks retry 3 times; a table whose chunk keeps failing is FAILED and the others continue. Writers use `synchronous_commit = off` (safe: data and state commit together). `copy` refuses while FKs are in place (unless `dropForeignKeys: false`). `status` derives in-progress table figures from committed chunks. Ctrl-C/SIGTERM set a cancel flag: in-flight chunks roll back, the run is INTERRUPTED, exit 130/143.
- Design the row-level path with a `RowTransformer` interface (no-op by default), so data masking can be added later.

## Progress and timing
- Interactive terminal: one bar per active table (rows/estimated rows, MB, rate, ETA) plus an overall bar. Non-interactive output (no TTY or piped): a summary line every N seconds instead.
- Final report: per table rows, bytes, duration, rows/s, MB/s; per stage duration; totals. Store it in the state table and print it; `status` can show it later.

## Logging and errors
- Log file next to the job file; console shows only progress and important messages.
- One failing table does not stop others: mark it failed in state and continue, then exit non-zero with a summary. Rerunning `copy` resumes failed and unfinished chunks only.
- Clean shutdown on Ctrl-C: finish or roll back in-flight chunks, keep state consistent.

## Tests (Testcontainers)
Liquibase test schema with:
- cyclic foreign keys
- generated columns
- identity and sequence columns
- composite and non-numeric PKs
- a table without a PK
- large-object columns
- one table big enough to be chunked
- schema differences between source and target: missing table, extra/missing columns, type mismatch

Scenarios:
- full `run` produces identical data
- the diff report is correct
- the tool refuses to run on a non-empty target
- kill mid-copy, then resume, with no duplicates
- `recover` after a failed `disable`/`copy`
- `enable` with a violating row
- `fetch-test` touches nothing on the target
- include/exclude filters

Write a README with the config reference, the stages, and the recovery procedure.

## Done (as built)
All of the above is implemented. Notes on how:
- Core API: `Pumplet(config)` returns a `StageRun` per stage; `events` is a cold `Flow<Event>` that runs the stage on Dispatchers.IO; cancelling the collector stops the stage cleanly; `result` holds the outcome. Internally the stages report through an `EventSink` callback.
- Log file: `<job>.log` next to the job file, appended per command, every line redacted; the tool's loggers go to the file only.
- Tests: Testcontainers 2.0.5 (two postgres:17 containers) + Liquibase 4.33.0 (Apache 2.0; 5.x is FSL) changelog in `cli/src/test/resources/e2e/changelog.sql`; the kill scenario kills a real child JVM with `kill -9`. The docker-compose `test-env/` stays for manual runs and the remaining integration tests.
- Not done: schema mapping (target schema with another name than the source).

## Sequences (added later)
- After each table, owned sequences are moved past the copied max, never lowered.
- `sequences.raiseToSource` (default true, not in the config hash; "Raise sequences" checkbox in the UI): after a successful copy, every sequence of the copied schemas present on both sides is raised to the source's `pg_sequences.last_value` when the target is behind; a target ahead is kept. The source value is read, not taken with nextval() (which would write to the read-only source and burn ids). Unreadable source sequences are skipped with a warning that prints the GRANT.

## Desktop UI (added later)
- Module `ui`: Compose for Desktop 1.12.2 (Material), built with Maven: Compose compiler via `-Xplugin` (embeddable plugin jar), `-desktop` artifacts named explicitly, Google Maven repo for AndroidX, AndroidX compose runtime pinned to 1.12.1. Runs as a plain Java app (shaded jar with the macOS arm64 skiko runtime; `-Dskiko.platform` for others); no native packaging.
- Profiles: `~/.config/pumplet-onegin/profiles/<name>/{job.yaml, secrets.yaml}` (dir 700, secrets 600; `PUMPLET_ONEGIN_HOME` overrides). A profile is an ordinary job, usable from the CLI. Edited with a form; validated offline with ConfigLoader on start and on save; nothing connects until a button is pressed.
- Commands run the real CLI in-process (`runCli` with `Io` streams feeding the output pane, cancel flag = Stop), one at a time; target-writing commands ask for confirmation. Progress lines every 2 s (`Io.progressEverySeconds`).