# pumplet-onegin

A command-line tool that copies the **data** of PostgreSQL schemas from a source database
(typically a remote UAT/production replica) to a target database (typically local) quickly, and
can resume after an interruption. The target schema must already exist: it's created beforehand,
for example by Liquibase. The tool doesn't copy schema definitions.

A YAML job file describes the copy. Passwords live in a separate secrets file and never appear in
the job file, in logs, in the state table or in error messages.

The usual way to use it:

```sh
./pumplet-onegin diff --config job.yaml     # what will be copied, and is anything blocking?
./pumplet-onegin run --config job.yaml      # diff, disable, copy, enable
./pumplet-onegin status --config job.yaml   # how did it go (any time, from anywhere)
```

## Desktop UI

There's also a small desktop app (Compose for Desktop) for keeping job profiles and running
commands without a terminal:

```sh
mvn -pl ui -am package -DskipTests
java -jar ui/target/pumplet-onegin-ui.jar
```

You can also run `app.dc8.pumplet.onegin.ui.MainKt` from IntelliJ IDEA.

- **Profiles:** each profile is a folder under `~/.config/pumplet-onegin/profiles/<name>/` (or
  under `$PUMPLET_ONEGIN_HOME/profiles`). It holds a normal `job.yaml` and a `secrets.yaml` with
  the passwords. The folder is mode 700 and the secrets file is mode 600. Each job's log and
  diff report end up in the same folder. Any profile also works from a shell:
  `pumplet-onegin run --config ~/.config/pumplet-onegin/profiles/<name>/job.yaml`.
- **On start:** the app lists the profiles and validates each one *offline*, the same way the
  CLI loads a job. Nothing connects to a database until you press a button.
- **Layout:** the profile list is on the left. On the right, under the command toolbar, the **Profile** tab has the form and the **Output** tab shows command output. Starting a command switches to Output.
- **Editing:** a form holds the connections (passwords masked), the schemas and the
  include/exclude filters. Tuning, the load options, *Raise sequences* and the state table are
  under *Advanced settings*. When any of them differ from the defaults, the link says which. *Save* writes both files and shows any problems.
  *Duplicate* and *Delete* are next to it.
- **Commands:** *Check connection*, *Status*, *Diff*, *Run* and *Fetch test* are on the toolbar.
  *Validate*, *Disable*, *Copy*, *Enable*, *Enable, skip pending*, *Recover* and *Status (all
  jobs)* are under *More*. Commands that write to the target ask first and name the target URL.
  Commands run the real CLI in-process on a background thread, and the output pane shows exactly
  what the terminal would. A copy prints a progress line every 2 seconds. *Stop* works like
  Ctrl-C: in-flight chunks roll back, and running the command again resumes. One command runs at
  a time.

The jar bundles the Skia runtime for macOS on Apple Silicon. On another machine, build it with
`-Dskiko.platform=macos-x64`, `linux-x64` or `windows-x64`.

## Requirements

- JDK 21+
- Maven 3.x (to build)
- PostgreSQL source and target (the URLs must be `jdbc:postgresql:` URLs)

## Build

```sh
mvn package          # produces cli/target/pumplet-onegin.jar (shaded, runnable)
```

You can run the jar directly or use the wrapper script in the repo root:

```sh
./pumplet-onegin <command> --config job.yaml
# same as: java -jar cli/target/pumplet-onegin.jar <command> --config job.yaml
```

## Usage

```
Usage: pumplet-onegin <command> --config <job.yaml> [--secrets <secrets.yaml>]

Commands:
  validate  Load and validate the job and secrets files.
  check     Validate, then connect to source and target and report what would
            be copied.
  diff      Compare source and target schemas and show what would be copied.
            Read-only on both sides; also writes the report as JSON next to the
            job file.
  disable   Diff, then save and drop foreign keys / secondary indexes and
            disable user triggers on the target tables, as set under
            'constraints:'.
  copy      Copy the data of every table the diff marks COPY. Resumes this job's
            session: only chunks that aren't committed yet are copied.
  enable    Restore everything `disable` dropped or disabled; retries only what
            is still pending.
  recover   After a crash or a failed run: restore what a session left dropped
            or disabled, without copying.
  run       diff, disable, copy and enable in one go. Stops at the first stage
            that fails; run it again to resume from where it stopped.
  fetch-test
            Read-only speed test: read the selected tables from the source the
            way `copy` does, discard the data, report rows, MB and rates.
  status    Show this job's latest session (runs, stages, tables, saved
            constraints, report) and earlier sessions. Read-only.

Options (every command):
  --config=<job.yaml>       job file (required)
  --secrets=<secrets.yaml>  secrets file; overrides 'secrets:' in the job file
  -h, --help                Show this message and exit
```

`pumplet-onegin --help` lists the commands, and `pumplet-onegin <command> --help` shows that
command's options.

- You must always pass `--config`. The tool has no default job file location.
- Both `--config file` and `--config=file` work, and the same goes for `--secrets`.
- Exit codes: `0` success, `1` failure (invalid configuration, a failed check, a blocking diff,
  a refused or failed stage, or objects left pending), `2` usage error, `130`/`143` stopped by
  Ctrl-C or SIGTERM.

### `validate`

Parses the job and secrets files, resolves every `${secret:…}` reference and prints a summary.
It does not connect to any database.

```
$ ./pumplet-onegin validate --config test-env/job.yaml
configuration OK: /…/test-env/job.yaml
  secrets: /…/test-env/local.secrets.yaml
  source:  jdbc:postgresql://localhost:55432/app
  target:  jdbc:postgresql://localhost:55433/app
  hash:    a64632f28727d5311c6804d2930f8b2fb142a6ce3fdcc60dbd303318e0c61843
```

`hash` identifies the job when a copy session is resumed. Passwords and the secrets file path
are left out of the hash, so you can rotate a password or use `--secrets` on another machine
and the session still matches.

### `check`

Runs `validate`, then connects to both databases and reports:

- the source tables your filters select, with estimated row counts (from `pg_class.reltuples`)
  and how many chunks each one will be split into
- tables your filters exclude
- missing source schemas, and tables the source user cannot `SELECT`
- how many of the selected tables already exist on the target

```
$ ./pumplet-onegin check --config test-env/job.yaml

source: jdbc:postgresql://localhost:55432/app as app_ro
  connected: PostgreSQL 17.11
  tables: 6 selected of 7 in schemas app
    app.OrderNotes   ~1,000 rows
    app.customers    ~10,000 rows
    app.order_items  ~1,200,000 rows  -> 6 chunks
    app.orders       ~600,000 rows  -> 3 chunks
    app.products     ~500 rows
    app.type_zoo     ~3 rows
  excluded: app.audit_log

target: jdbc:postgresql://localhost:55433/app as postgres
  connected: PostgreSQL 17.11
  selected tables already present on target: 6 of 6

check OK
```

If anything is wrong, the report shows a `PROBLEM:` line for each issue, ends with
`check found N problem(s)` and exits with code `1`.

### `diff`

Compares the selected tables in both databases and works out, per table and column, what
`copy` will do. It's read-only on both sides: the connections are read-only, and it writes no
state. The same report is also written as JSON next to the job file (`job.yaml` gives
`job.diff.json`).

```
$ ./pumplet-onegin diff --config shop.yaml
source: jdbc:postgresql://uat-db:5432/app as app_ro
target: jdbc:postgresql://localhost:5432/app as postgres

tables: 3 selected (1 skip, 2 blocked)
  shop.legacy    SKIP    missing in target
  shop.orders    BLOCKED created_at: extra NOT NULL column without default in target: every insert would fail
  shop.payments  BLOCKED amount: type text in source, numeric(12,2) in target; set diff.allowTypeDifferences: true to copy it as text anyway

findings:
  ERROR    shop.orders.created_at: extra NOT NULL column without default in target: every insert would fail
  ERROR    shop.payments.amount: type text in source, numeric(12,2) in target; set diff.allowTypeDifferences: true to copy it as text anyway
  WARNING  shop.legacy: table missing in target: not copied
  WARNING  shop.orders.note: type character varying(40) in source, character varying(200) in target: safe widening, copied as text
  WARNING  shop.orders.status: nullable in source but NOT NULL in target: rows with NULL here will fail
  WARNING  shop.orders.total: type numeric(10,2) in source, numeric(12,2) in target: safe widening, copied as text
  WARNING  shop.payments.payments_order_id_fkey: foreign key missing in target: FOREIGN KEY (order_id) REFERENCES shop.orders(id)
  (1 info finding(s) hidden; use --verbose)

report written to /home/me/jobs/shop.diff.json
diff found 2 blocking error(s) and 5 warning(s)
```

Each table ends up with one action:

| Action    | Meaning                                                                       |
|-----------|-------------------------------------------------------------------------------|
| `COPY`    | Copied with the columns both sides have. The line shows rows, chunks, and whether it needs text format or has large objects. |
| `SKIP`    | Not copied: missing in the target, or no columns in common.                   |
| `BLOCKED` | Has an error. Fix it first; `run` stops before `disable` while any error exists. |

Findings by severity:

- **ERROR** (blocks the job, and `diff` exits with `1`):
  - a type difference that isn't a safe widening (unless `diff.allowTypeDifferences: true`)
  - an extra `NOT NULL` target column with no default, identity or generation expression
  - a source table the job user can't `SELECT`
  - a schema missing in the source or in the target
- **WARNING:**
  - a table or column missing in the target (its data isn't copied)
  - a safe widening, such as `varchar(40)` to `varchar(200)`/`text`, `int` to `bigint`, `real` to
    `double precision`, or more numeric precision. These columns go through text-format `COPY`
    instead of binary.
  - a nullable source column that is `NOT NULL` in the target
  - a primary key, unique, foreign key, check or exclusion constraint, or an index, that is
    missing, extra or different
- **INFO** (shown with `--verbose`):
  - a different default
  - a target column that is generated, which the target computes and isn't copied
  - extra nullable or defaulted target columns
  - tables that exist only in the target, which are left alone
  - constraints and indexes that are the same apart from their name
  - tables without a primary key, which are copied in one piece
  - large-object columns

`--verbose` also lists the columns that will be copied for each table. Constraints and indexes
are compared by their definition, as printed by `pg_get_constraintdef`/`pg_get_indexdef`
with schema-qualified names. So differently named but identical ones (common with Liquibase)
are only INFO.

### `disable`, `enable` and `recover`

Loading is much faster without foreign keys, secondary indexes and triggers on the target.
`disable` takes them away and `enable` puts them back, exactly as they were.

```
$ ./pumplet-onegin disable --config job.yaml
new session 69e501d5, run #1 (disable)
diff...
diff: 6 table(s) to copy, 0 warning(s)
disable...
  dropped  foreign key app.OrderNotes.OrderNotes_orderId_fkey
  dropped  foreign key app.order_items.order_items_order_id_fkey
  dropped  foreign key app.order_items.order_items_product_id_fkey
  dropped  foreign key app.orders.orders_customer_id_fkey
  disabled trigger     app.orders.orders_audit
disable: 4 foreign keys, 1 trigger dropped/disabled
```

**`disable`:**

- **Runs the diff first** as a stage of the session. It stops if the diff has blocking errors.
- **Refuses non-empty tables:** if any target table that will be copied already has rows, and
  this session's copy hasn't started yet, nothing is dropped.
- **Acts only on the tables that will be copied**, and only on what `constraints:` enables:
  - foreign keys on those tables (`dropForeignKeys`)
  - secondary indexes (`dropSecondaryIndexes`): indexes that no constraint uses. Primary key
    and unique constraints, and any index a constraint depends on, stay.
  - user triggers that are enabled (`disableUserTriggers`), including replica and always
    triggers. Triggers that are already disabled are left alone.
- **Saves each definition** in the state table in the same transaction that drops or disables
  the object. The state can never disagree with the database, even if the process is killed.
- **Running it again is harmless:** whatever is already out of place is just counted.

**`enable`** restores in this order:

1. indexes
2. foreign keys: each is added as `NOT VALID` and then validated in a separate step
3. triggers, in the mode they had (`ENABLE`, `ENABLE REPLICA` or `ENABLE ALWAYS`)

Each object gets its own transaction, so one failure doesn't affect the others:

```
$ ./pumplet-onegin enable --config job.yaml
continuing session 69e501d5, run #2 (enable)
enable...
  restored foreign key app.order_items.order_items_order_id_fkey
  PENDING  foreign key app.orders.orders_customer_id_fkey: 1 row(s) in app.orders have no matching row in the referenced table
  ...
1 foreign key could not be restored:
  app.orders.orders_customer_id_fkey: 1 row(s) in app.orders have no matching row in the referenced table
fix the data and run `enable` again (it retries only these), or give up on them with `enable --skip-pending`
```

- **Pending objects:** an object that can't be restored is left out of place and marked
  *pending*, with the reason. For example, a foreign key whose data violates it shows the
  count of offending rows, and a unique index with duplicates shows the error. `status` shows
  pending objects, and the next `enable` retries only those.
- **`--skip-pending`:** gives up on the pending objects. It records them as skipped, so they no
  longer block new sessions, and prints the SQL to restore them by hand later.
- **Session completion:** `enable` completes the session when nothing is pending and this
  session's copy has finished. Until the copy finishes, the session stays open.

**`recover`** does the same restore without copying anything, and never completes the session.
Use it after a crash or a failed run when you want the target's constraints back now.
`--session <id>` recovers any session, including another job's (see
[Recovery](#recovery)).

### `copy` and `run`

`run` is the normal way to copy. It runs the four stages in order:

1. `diff`, which stops on blocking errors
2. `disable`
3. `copy`
4. `enable`

It stops at the first stage that fails. Running it again picks up where it stopped: `disable`
finds nothing more to drop, and `copy` copies only what is missing. You can also run the stages
one at a time with `diff`, `disable`, `copy` and `enable`. `copy` refuses to start while foreign
keys are still in place on the target tables, unless `constraints.dropForeignKeys: false`.

```
$ ./pumplet-onegin run --config job.yaml
new session 4dda21f9, run #1 (run)
diff: 6 table(s) to copy, 0 warning(s)
disable: 4 foreign keys, 1 trigger dropped/disabled
copy...
  done    app.order_items: 1,200,000 rows, 43.5 MB in 3s
  ...
copy: 6 of 6 table(s) done, 1,811,503 rows, 80.7 MB in this run
enable: 4 foreign keys, 1 trigger restored

copy report (this run):
  app.order_items     1,200,000 rows     43.5 MB        3s     357,143 rows/s    12.9 MB/s
  app.orders            600,000 rows     36.5 MB        2s     260,643 rows/s    15.8 MB/s
  ...
  total               1,811,503 rows     80.7 MB        3s     458,957 rows/s    20.4 MB/s
session 4dda21f9 completed
```

In a terminal, `copy` shows a progress bar for each active table and one for all tables: rows of
the estimate, MB, rows/s and the time left. When output isn't a terminal (piped, CI, a log
file), it prints a line as each table finishes and a summary line every 10 seconds instead.

#### How the copy works

- **One consistent picture of the source:** one source connection opens a `REPEATABLE READ`
  transaction, exports its snapshot (`pg_export_snapshot()`) and stays open for the whole copy.
  Every reader imports that snapshot, so all chunks of all tables see the source at the same
  moment. Source connections are read-only at every level.
- **Chunks:** large tables, estimated at `minRowsToSplit` rows or more, are split into chunks of
  about `rowsPerChunk` rows by primary key:
  - a single integer key gets equal ranges between min and max, unless a sample shows the keys
    bunched up, in which case the bounds come from the sample
  - any other key, composite or text, gets bounds from an ordered `TABLESAMPLE`, read with
    keyset predicates (`(a, b) > (…) and (a, b) <= (…)`)
  - before copying, the database checks that the bounds are in key order, so chunks can't
    overlap or leave gaps

  Small tables and tables without a primary key are one chunk. The chunk plan is stored, so a
  resumed copy uses exactly the same chunks.
- **Streaming:** each chunk is streamed with `COPY (SELECT … WHERE <range>) TO STDOUT` from the
  source straight into `COPY … FROM STDIN` on the target. It isn't loaded into memory first.
  Binary format is used where both sides have the same column types, and text format where the
  diff allowed a type difference. Many tables are copied at once:
  - `threads.readers` connections read and `threads.writers` connections write
  - each chunk passes through a bounded buffer, and together the buffers stay within
    `buffer.maxMemoryMb`. If writers fall behind, readers wait instead of using more memory.
- **One commit per chunk:** each chunk is one target transaction, together with the state row
  that marks it done. A chunk is either fully copied and recorded, or neither. So an
  interrupted copy keeps everything it committed, and the next run copies exactly the missing
  chunks: no duplicates, no gaps. Target writers use `synchronous_commit = off`, which is safe
  because data and state commit together.
- **Failures:**
  - a failed chunk is retried up to 3 times
  - a table whose chunk keeps failing is marked FAILED, and the other tables carry on
  - the run then fails with a summary, and the next `copy` retries only what is missing
- **After each table:** sequences owned by its columns (serial and identity) are moved past the
  copied maximum, never back, and the table is `ANALYZE`d.
- **Sequences after the copy** (`sequences.raiseToSource`, on by default): every sequence in the
  copied schemas that exists on both sides is raised to the source's value when the target is
  behind. That includes sequences no column owns. A target sequence that is already ahead stays
  as it is. This keeps local inserts from reusing ids the source has already handed out, for
  example after rows were deleted on the source. The source value is *read*
  (`pg_sequences.last_value`), never taken with `nextval()`, so the source isn't changed. Reading
  needs `SELECT` on the source sequences, which `grant select on all tables` doesn't cover. When
  a sequence can't be read, the copy warns and prints the `grant select on all sequences in
  schema …` statement.
- **Columns:** a table gets the columns both sides have. Columns generated on the target are left
  for the target to compute. Identity values are copied as they are, `GENERATED ALWAYS`
  included.
- **Large objects:** tables with `oid` columns that reference large objects go row by row. Each
  object is copied with the same OID and then its row is inserted. On this path rows pass
  through a `RowTransformer` hook, a no-op for now, so that data masking can be added later.
- **Resuming:** a resumed copy takes a new snapshot of the source and says so. Chunks copied by
  earlier runs may reflect an earlier state of the source than the rest.

#### Stopping

Ctrl-C (or SIGTERM) stops the copy cleanly. Chunks in flight are rolled back, and committed ones
stay. The run is recorded as `INTERRUPTED`, the partial report is printed, and the exit code is
130 (143 for SIGTERM). If the process is killed outright (`kill -9`, a crash, a lost
connection), the same holds, because nothing half-done was ever committed. Either way, run the
same command again to continue.

#### Starting over a non-empty target

A new session never writes into tables that already have rows: `disable`, `copy` and `run`
refuse and list them. The tool never empties target tables itself. If starting over is what you
want, empty them yourself; the error message gives the statement, for example
`truncate "app"."orders", "app"."order_items";`. A session that is resuming its own copy isn't
affected, because rows its copy committed are expected.

### `fetch-test`

`fetch-test` measures how fast the source can be read before you copy anything. It reads the
selected tables with the same snapshot, chunking and readers as `copy`, throws the data away,
and reports rows, MB, rows/s and MB/s per table and overall. It never connects to the target
and writes no state.

```
$ ./pumplet-onegin fetch-test --config job.yaml
fetch-test: reading from jdbc:postgresql://uat-db:5432/app as app_ro with 10 reader(s); nothing is written anywhere
fetch-test report:
  app.order_items     1,200,000 rows     43.5 MB      714ms   1,680,672 rows/s    60.9 MB/s
  ...
  total               1,811,503 rows     80.7 MB      715ms   2,533,571 rows/s   112.8 MB/s
```

Use it to tune `threads.readers` for a remote source: more readers hide network latency, up to
what the source and the link can deliver.

### `status`

Shows how this job's commands went, read from the state table on the target (see
[Session state](#session-state)). It writes nothing, and it works after a run succeeded,
failed or was killed.

```
$ ./pumplet-onegin status --config job.yaml
state: app.pumplet_onegin_state on jdbc:postgresql://localhost:5432/app as postgres

session 40f23177-4700-46f0-8194-9d20bde6db3f  OPEN
  created:   2026-10-17 15:43:34
  stage:     copy
  runs:
    #1   disable  2026-10-17 15:43:34  3s       SUCCEEDED
    #2   copy     2026-10-17 15:43:37  4m10s    FAILED: 1 table failed
    #3   copy     2026-10-17 16:02:11  -        INTERRUPTED: the process ended without finishing (killed, crashed or lost its connection)
  stages:
    disable  DONE    3s        14 foreign keys dropped
    copy     FAILED  4m10s
  tables: 6 (5 done, 1 failed); copied 1,400,000 rows, 160.2 MB
    app.orders  FAILED  400,000 rows of ~600,000  chunks 2/3: connection reset
    ...
  saved objects: 14 foreign keys: 14 dropped, 0 pending, 0 restored
    -> constraints/indexes/triggers are not all in place; run `enable` (or `recover`) to restore them

earlier sessions of this job:
  15aa3f9d  COMPLETED  created 2026-10-16 09:00:02  last run #4 enable 2026-10-16 09:11:40 SUCCEEDED
```

| Option           | Effect                                                                  |
|------------------|-------------------------------------------------------------------------|
| `--session <id>` | Show that session instead of the latest. A unique id prefix is enough.  |
| `--all`          | List sessions of every job recorded in the state table, not just this one. |
| `--limit <n>`    | How many earlier sessions to list (default 10).                          |

A run marked `RUNNING` is either still working (the first line then says a process is working
on the target) or was killed. `status` tells the two apart, and shows a killed run as
`INTERRUPTED`.

## Configuration

A job is made of two files:

| File         | Contains                                       | Commit it?                  |
|--------------|------------------------------------------------|-----------------------------|
| job file     | connection URLs, users, filters, tuning        | yes                         |
| secrets file | `name: password` pairs, nothing else           | **no**: keep it `chmod 600` |

### Job file reference

```yaml
# Path to the secrets file. A relative path resolves against this job file's directory;
# a leading ~/ expands to your home directory. --secrets on the command line overrides it.
secrets: ~/.pumplet-onegin/uat-local.secrets.yaml

source:                                     # required
  url: jdbc:postgresql://uat-host:5432/app  # required; must not contain a password
  user: app_ro                              # optional; driver default if omitted
  password: ${secret:uat}                   # optional; must be a ${secret:<name>} reference
  schemas: [app]                            # required; at least one schema to copy

target:                                     # required (same keys as source, minus schemas)
  url: jdbc:postgresql://localhost:5432/app
  user: postgres
  password: ${secret:local}

tables:
  include: []              # <schema>.<table> globs; empty = every table in source.schemas
  exclude: [app.audit_*]   # applied after include

threads:
  readers: 10              # source connections (remote: high latency, so use more)
  writers: 4               # target connections

chunking:
  rowsPerChunk: 200000     # rows per chunk when a table is split
  minRowsToSplit: 500000   # tables with fewer estimated rows are copied in one piece

buffer:
  maxMemoryMb: 1024        # in-memory buffer between readers and writers

constraints:
  dropForeignKeys: true        # drop FKs on the target during the load
  dropSecondaryIndexes: false  # drop non-PK/unique indexes during the load
  disableUserTriggers: true    # disable user triggers on the target during the load

diff:
  allowTypeDifferences: false  # true: copy columns of different types as text instead of blocking

sequences:
  raiseToSource: true      # after the copy, raise target sequences that are behind the source

state:
  table: app.pumplet_onegin_state  # where session state is kept on the target;
                                   # default: <first source schema>.pumplet_onegin_state
```

Only `source` and `target` are required. Any section you leave out uses the defaults shown
above.

Validation is strict:

- An unknown key is an error, and the message suggests the closest valid key.
- A value of the wrong type is an error (for example `readers: "10"` or `dropForeignKeys: yes-please`).
- Numbers must be at least `1`.
- The tool collects every problem it finds and reports them all in one run.

```
$ ./pumplet-onegin validate --config bad.yaml
error: invalid configuration
  - bad.yaml: source.password: plain-text passwords are not allowed in the job file; move it to the secrets file and reference it as ${secret:<name>}
  - bad.yaml: source.schemas: list at least one schema to copy
  - bad.yaml: source.schema: unknown key (did you mean 'schemas'?); allowed: url, user, password, schemas
  - bad.yaml: threds: unknown key (did you mean 'threads'?); allowed: secrets, source, target, tables, threads, chunking, buffer, constraints, sequences, diff, state
```

### Secrets file

A flat YAML mapping of secret names to values:

```yaml
# ~/.pumplet-onegin/uat-local.secrets.yaml   (chmod 600)
uat: "s3cr3t-uat-password"
local: "postgres"
```

- Names may contain letters, digits, `_`, `-` and `.`.
- Values are always read as strings, so `pw: 0123` stays `"0123"`. Values must not be empty.
- If a `${secret:name}` reference has no matching entry, the error names the missing key and
  never shows the value.
- If group or other users can read the file, the tool prints a warning that suggests
  `chmod 600 <file>`.
- If the secrets file has a YAML syntax error, the tool reports only the line and column, so
  the error never prints a password.

### Table filters

Filters match against `schema.table` names:

- `*` matches any run of characters and `?` matches exactly one character.
- Matching is case-sensitive, so `app.OrderNotes` and `app.ordernotes` are different names.
- The filters only see tables in `source.schemas`.

| Goal                                    | Config                                                      |
|-----------------------------------------|-------------------------------------------------------------|
| Everything in the listed schemas        | `include: []`                                               |
| Only order-related tables               | `include: [app.order*]`                                     |
| Everything except audit and temp tables | `exclude: [app.audit_*, app.tmp_*]`                         |
| Two schemas, skip one big table         | `schemas: [app, billing]` + `exclude: [billing.events]`     |
| `*_log` tables in every schema excluded | `exclude: ["*.*_log"]`                                      |

Quote globs that start with `*`, because YAML treats an unquoted leading `*` as an alias.

## Examples

### 1. Remote UAT to local, run from your laptop

`~/jobs/uat-to-local.yaml`:

```yaml
secrets: ~/.pumplet-onegin/uat.secrets.yaml
source:
  url: jdbc:postgresql://uat-db.internal:5432/app?sslmode=require
  user: app_ro
  password: ${secret:uat}
  schemas: [app]
target:
  url: jdbc:postgresql://localhost:5432/app
  user: postgres
  password: ${secret:local}
tables:
  exclude: [app.audit_*, app.session*]
```

```sh
chmod 600 ~/.pumplet-onegin/uat.secrets.yaml
./pumplet-onegin check --config ~/jobs/uat-to-local.yaml
```

### 2. One job file, different secrets per machine

Commit the job file with no `secrets:` line. Each machine passes its own secrets file:

```sh
./pumplet-onegin check --config jobs/nightly.yaml --secrets ~/.pumplet-onegin/ci.secrets.yaml
```

A relative `--secrets` path resolves against the current directory. A relative `secrets:` path
inside the job file resolves against the job file's directory.

### 3. Slow, high-latency source over a VPN

Use more readers to hide latency, smaller chunks so retries cost less, and a bigger buffer:

```yaml
threads:
  readers: 16
  writers: 4
chunking:
  rowsPerChunk: 100000
  minRowsToSplit: 250000
buffer:
  maxMemoryMb: 2048
```

### 4. Fastest bulk load into a disposable local DB

```yaml
constraints:
  dropForeignKeys: true
  dropSecondaryIndexes: true   # rebuilt after the load
  disableUserTriggers: true
```

### 5. Passwordless auth (`.pgpass`, trust, peer)

`user` and `password` are both optional. If you omit `password`, the PostgreSQL JDBC driver
handles authentication:

```yaml
source:
  url: jdbc:postgresql://replica:5432/app
  user: readonly
  schemas: [public]
target:
  url: jdbc:postgresql://localhost:5432/app
```

## Session state

Everything the tool needs to resume, and everything `status` shows, is kept in one table on the
**target** database. It sits next to the data it describes: in the schema you're copying, as
`<schema>.pumplet_onegin_state`. Copying `source.schemas: [app_owner]` keeps state in
`app_owner.pumplet_onegin_state` on the target. With several schemas, the first one listed is
used. Set `state.table` to put it somewhere else.

- **Creation:** the table is created the first time a command records state. `check` and
  `status` never create it. The schema must already exist on the target, which is normally
  the case because Liquibase created it.
- **Format:** each record is a row `(session_id, kind, key, payload jsonb, updated_at)`. You
  can query it directly, for example
  `select kind, key, payload from app.pumplet_onegin_state where session_id = '…'`.
- **Never copied:** the state table is left out of the copy even when its schema is listed in
  `source.schemas`.
- **No secrets:** every value from the secrets file is masked before a record is written.

**Sessions and runs.** A *session* covers one job from start to finish, and the job is
identified by its config hash. It can take several commands, such as `disable`, then `copy`,
then `copy` again after a failure, then `enable`. Each command is a numbered *run* in that
session, which records:

- the command
- the host and process id
- the start and end times
- the outcome: `SUCCEEDED`, `FAILED` with the error, or `INTERRUPTED`

A command joins this job's open session if one exists. Otherwise it starts a new one. Changing
any setting that is part of the hash makes it a different job with its own session.

**One process per target.** While a command runs, it holds a PostgreSQL advisory lock that is
tied to the state table. A second command against the same target is refused until the first
one finishes. If the process is killed, the server releases the lock. `status` then shows the
run as `INTERRUPTED`, and the next command records it that way.

## Recovery

Nothing can get lost between the database and the state table, because each drop, restore and
state write is committed together. Whatever happened, `status` tells you where things stand:

```sh
./pumplet-onegin status --config job.yaml
```

| Situation | What `status` shows | What to do |
|-----------|---------------------|------------|
| A command was killed (Ctrl-C, crash, lost connection) | The run is `INTERRUPTED`; tables show committed chunks, e.g. `chunks 24/48` | Run the same command again (`run` or `copy`): it continues the session and copies only the missing chunks. |
| A table failed during `copy` | The table is `FAILED` with the error; the other tables are `DONE` | Fix the cause (shown in the error), then `copy` or `run` again: only that table's missing chunks are copied. |
| `disable` or `copy` failed, and you want to stop | Saved objects `dropped` | `recover`: puts every constraint, index and trigger back. |
| `enable` couldn't restore something | Saved objects `pending`, with the reason | Fix the data and run `enable` again, or use `enable --skip-pending` and apply the printed SQL later. |
| A different job won't start: "session … still has … dropped" | That session's dropped objects (`status --all`) | `recover --config <job.yaml> --session <id>`, then start the new job. |
| "another pumplet-onegin process is working on this target" | `a pumplet-onegin process is working on this target right now` | Wait for it, or stop it. If it was killed, its lock is already gone. |

A new session never starts while another session still has objects dropped or pending. It also
never writes into target tables that already have rows. Both checks exist so that one job can't
silently build on another job's half-finished work.

## Log file

Every command that loads a job appends to a log next to the job file (`job.yaml` gives
`job.log`). The log records each stage, every constraint, index and trigger change, every chunk
committed or failed, and every error with its stack trace. The console only shows progress and
important messages. Every line passes through the same redaction as the console output, so
secret values are masked in the log too.

## What stays secret

- Passwords are passed to the JDBC driver only through connection properties, never inside
  the URL.
- A JDBC URL that contains a password (`user:pw@host` or `?password=`) is rejected. URLs are
  printed with any credentials removed.
- Every value from the secrets file is masked as `****` in error output, in the state table, in
  the log file and in the diff report. The tool scrubs any
  message that could reach the console.

## Local test environment

`test-env/` contains two throwaway PostgreSQL 17 containers. Their init scripts run only when a
container is first created, so after changing a script, recreate the container with
`docker compose -f test-env/docker-compose.yml up -d --force-recreate --renew-anon-volumes --wait`.

- `pg-src` on port 55432, seeded with schema `app`: FKs, indexes, a trigger, an excluded audit
  table, mixed-case identifiers and a wide range of column types
- `pg-dst` on port 55433, with the same schema `app` but no data, as Liquibase would leave it (`target-init.sql`, generated from the source with `pg_dump --schema-only`)

```sh
docker compose -f test-env/docker-compose.yml up -d --wait
./pumplet-onegin check --config test-env/job.yaml
```

## Development

The project has three Maven modules:

- `core`: the engine (config, database access, stages). It has no CLI or terminal
  dependencies, so another front end can be built on it.
- `cli`: the Clikt command line, packaged as `cli/target/pumplet-onegin.jar`.
- `ui`: the Compose for Desktop app, packaged as `ui/target/pumplet-onegin-ui.jar`. It runs
  CLI commands in-process.

Building Compose with Maven (normally done with Gradle) needs three things, all set in
`ui/pom.xml`:

- The Compose compiler is loaded with `-Xplugin=`. `maven-dependency-plugin` copies the plugin
  jars to `ui/target/compose-compiler/` first, so the path doesn't depend on where a machine keeps
  its local repository. Maven uses the `-embeddable` build. IntelliJ's own build needs the plain
  one, and gets it through the `intellij` profile, which is active only during IntelliJ's import.
- The `-desktop` artifacts are named directly, and Google's Maven repository is added for the
  AndroidX libraries.
- The AndroidX Compose runtime versions are pinned, because Maven can't reconcile their
  exact-version ranges the way Gradle does.

### Library API

`core` has one entry point for a front end other than the CLI. Every stage is a `StageRun`.
Collecting its `events` (a Kotlin `Flow<Event>`) runs the stage on `Dispatchers.IO` and emits
progress as it happens: stages, tables, chunks, constraints, warnings. Afterwards `result`
holds the outcome. If the collector cancels, the stage stops cleanly: a copy rolls back its
in-flight chunks, the run is recorded as interrupted, and the flow completes only after that.

```kotlin
val config = ConfigLoader().load(Paths.get("job.yaml")).config
val api = Pumplet(config)
val run = api.run()                               // diff, disable, copy, enable
run.events.collect { event -> show(event) }
val result = run.result.await()                   // or the exception the stage failed with
val status = api.status()                         // what `status` shows
```

### Tests

```sh
mvn test
```

There are three kinds of tests:

- **Unit tests:** no database needed.
- **End-to-end tests** (`cli/src/test/.../e2e`): these run the plan's scenarios through the CLI
  against two PostgreSQL 17 containers that Testcontainers starts. Liquibase creates their
  schemas from `cli/src/test/resources/e2e/changelog.sql`. The schema has cyclic foreign keys,
  generated, identity and serial columns, composite, text and uuid keys, a table without a
  primary key, large objects, a chunked table, and schema drift: a missing table, extra and
  missing columns, and a type mismatch. The scenarios are:
  - a full run with identical checksums
  - the diff report
  - refusing a non-empty target
  - a real `kill -9` of a child JVM in the middle of a copy, then a resume with no duplicates
  - `recover` after a failed copy
  - `enable` with a violating row
  - `fetch-test` leaving the target untouched
  - include/exclude filters

  These tests need Docker and are skipped without it.
- **Integration tests against `test-env/`** (`DockerIntegrationTest`, and the `core` tests that
  build throwaway schemas): these run when the Docker Compose environment below is up, and are
  skipped otherwise.
