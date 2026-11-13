# Store Inventory tactic: Transactions and Retry

Running code for the availability scenario AV-2 in our architecture document: *100% of store
events reach the DC; 0 events lost; 0 events applied twice.* It implements two availability
tactics from *Software Architecture in Practice* (4th ed.): **Transactions** (p. 61) and
**Retry** (p. 60).

Four processes talk over plain TCP on `127.0.0.1`:

```
point of sale ──SALE──▶ Store Agent ──PUBLISH──▶ fault link ──PUBLISH──▶ CFE ──PUBLISH──▶ Central Master Inventory
                         (SQLite)    ◀──ACK accepted──        ◀──ACK accepted──  (SQLite)  ◀──ACK applied──   (SQLite)
```

- The **Store Agent** records each sale and its Outbox event in one SQLite transaction and resends
  every event the CFE has not accepted, with its original identity.
- The **fault link** sits between store and CFE and drops, duplicates, delays or cuts traffic on
  command.
- The **CFE** (Communications Front-End) stores each event before acknowledging it and resends it
  to the master until the master acknowledges it as applied.
- The **Central Master Inventory** applies each event exactly once.

The design document is [`docs/design-document.md`](docs/design-document.md).

## Status and owners

Every process starts, binds its ports and answers `STATUS`, and the shared contract in `protocol`
is complete. The Store Agent records sales and runs its Outbox (#30). Tactic behaviour still to
come is marked `TODO(<owner>, #<issue>)` in the code.

| Part | Owner | Reviewer | Issue |
|---|---|---|---|
| `protocol`, build, CI, `system` harness, scaffold | Godson Umoren | Kobe LaPrade | #29 |
| `store-agent` | Godson Umoren | Kobe LaPrade | #30 |
| `cfe` | Kobe LaPrade | Leon Letournel | #31 |
| `master-inventory` | Mike Andrews | Godson Umoren | #32 |
| `fault-link`, `scripts/demo.sh`, this README's Dependencies section | Leon Letournel | Mike Andrews | #33 |
| Integration: demo driver, verdict table, end-to-end tests, design document | Godson Umoren | Kobe LaPrade | #34 |

## Prerequisites

- JDK 21 on the `PATH` (we build with Temurin 21).
- Maven 3.9 or later.
- For the demo script: bash (Git Bash on Windows). See [Dependencies](#dependencies).

Supported platforms: Windows (x86-64), macOS and Linux. The SQLite JDBC driver carries the
native SQLite library for each of them and unpacks it into the Java temporary folder at startup.

## Build

```bash
cd tactic
mvn -B verify      # compile, run the tests, build the executable jars
```

Each process is a self-contained jar (built with the Maven shade plugin):
`store-agent/target/store-agent.jar`, `fault-link/target/fault-link.jar`, `cfe/target/cfe.jar`,
`master-inventory/target/master-inventory.jar`.

## Run the demo

```bash
cd tactic
bash scripts/demo.sh
```

The script builds the jars, clears `demo-data/` and `demo-logs/`, and starts the four processes
in the background (master, CFE, fault link, store). It records each process ID in
`demo-logs/<process>.pid` and writes each log to `demo-logs/<process>.log`. Then it runs the
phases: normal running, a lossy link (drop 20%, duplicate 10%), a 30-second link cut, and a
`kill -9` and restart of the store, the CFE and the master in turn. A restarted process uses the
same ports and data folder. At the end the script stops every process with a plain `kill`.

TODO(Godson, #34): the demo driver sends sales through every phase, and the verdict table
replaces the raw `STATUS` lines that the script prints last.

## Run each process by hand

Start them in this order, each in its own terminal, from the `tactic` folder:

```bash
java -jar master-inventory/target/master-inventory.jar
java -jar cfe/target/cfe.jar
java -jar fault-link/target/fault-link.jar
java -jar store-agent/target/store-agent.jar
```

Each prints one line once it is listening, for example
`READY store-agent sales=7101 control=7102`. Ask any process for its status:

```bash
printf 'STATUS\n' | nc 127.0.0.1 7102
```

### Options

| Process | Options (all optional) |
|---|---|
| `store-agent` | `--sales-port N` `--control-port N` `--link-port N` `--data-dir PATH` `--source ID` |
| `fault-link` | `--listen-port N` `--control-port N` `--cfe-port N` |
| `cfe` | `--listen-port N` `--control-port N` `--master-port N` `--data-dir PATH` `--source ID` |
| `master-inventory` | `--listen-port N` `--control-port N` `--data-dir PATH` `--source ID` |

Port `0` asks for a free port; the `READY` line shows the port actually bound. Data folders
default to `demo-data/<process>` under the working folder; on Windows pass a relative path or a
Windows path (`cygpath -w`), not a Git Bash path such as `/c/...`. `--source` is the store's
identifier (default `store-1`).

The fault link's drop and duplicate decisions come from a random source seeded with the system
property `inventory.link.seed` (default 755).

The retry settings are Java system properties, shared by the store and the CFE:

| Property | Default | Meaning |
|---|---|---|
| `inventory.retry.window` | 100 | events in flight without an acknowledgement |
| `inventory.retry.ackTimeoutMs` | 500 | wait for an acknowledgement before resending |
| `inventory.retry.initialBackoffMs` | 100 | first wait before a resend, doubling after each failure |
| `inventory.retry.maxBackoffMs` | 2000 | longest wait, also the probe interval while the link is down |
| `inventory.retry.jitterPercent` | 20 | random lengthening of each wait, up to this percentage |
| `inventory.retry.linkDownAfter` | 10 | consecutive failed attempts before the link is marked down |

For example `java -Dinventory.retry.ackTimeoutMs=50 -jar store-agent/target/store-agent.jar`.

## Default ports

| Process | Listens for | Port | Control port |
|---|---|---|---|
| Store Agent | sales (`SALE` lines) | 7101 | 7102 |
| Fault link | the store | 7201 | 7202 |
| CFE | the fault link | 7301 | 7302 |
| Central Master Inventory | the CFE | 7401 | 7402 |

Every listener binds to `127.0.0.1` only and sets address reuse, so a killed process can be
restarted on the same port at once.

## How to read the verdict table

TODO(Godson, #34): each row of the verdict table, which numbers decide pass or fail (lost,
applied twice, stock match) and which are informational.

## Tests

`mvn -B verify` runs:

- `protocol`: every message's line, round trip, and rejection of malformed lines; the frozen
  STATUS keys; the retry settings.
- each process: starts on its ports (including port 0), reports what it bound, answers `STATUS`
  with every key, and keeps its durable `STATUS` values across a stop and a crash.
- `store-agent`, over its ports with a fake CFE built from `protocol` on its link port: sales are
  recorded and answered (and bad lines refused) whether or not the CFE is reachable; every event
  reaches the CFE in order with its original event ID; lost events and lost acknowledgements are
  covered by resends, and a resent event the CFE already holds is not stored twice; duplicate and
  stale acknowledgements never move it backwards; at most the window is in flight; the link is
  marked `DOWN` without losing an event, both when the CFE is unreachable and when it answers but
  refuses every event, and `UP` again on the first acknowledgement that moves accepted-through; a
  restarted store resumes after its last accepted sequence and never
  re-seeds. The tests compress the retry timings and assert outcomes, never retry counts.
- `fault-link`, over its ports between a fake store and a fake CFE: every line is forwarded
  unchanged and in order in both directions at zero; drop and duplicate rates are within
  tolerance in both directions over a seeded run; a delay holds lines back and keeps their order;
  `CUT` closes and refuses connections until `RESTORE`; `SET` changes `STATUS` and an out-of-range
  value is refused; a store connection is closed when the CFE is down.
- the Store Agent's sale transaction is also tested with a **test-only crash hook**, the one test
  that reaches past the wire: the package-private
  `StoreAgent.start(config, retry, failSalesAfterStockUpdate)` makes every sale fail after its
  stock update and before its Outbox insert, and the test checks that neither change is present
  after the store is reopened. The hook is off in every normal run and has no command-line option.
- `system`: the in-JVM harness starts all four processes and crashes and restarts each one. A
  crash in the harness interrupts the process's threads, closes its sockets and releases its
  SQLite file without committing. An open transaction is rolled back and committed data stays,
  the same data on disk as after a kill at a transaction boundary, but no hot journal is left for
  SQLite to recover. The demo script's `kill -9` covers a real process kill and that recovery. The
  end-to-end tests (lossy link, duplicates, link cut, store crash, CFE crash, master crash,
  combined run) are written and disabled until the integration pull request (#34).

## The shared contract

The `protocol` module is the contract every process is built against: the message formats
(`LineCodec`), the STATUS keys (`StatusKeys`), the default ports (`Defaults`) and the retry
settings (`RetrySettings`). Its Javadoc states the conventions: the sign of `delta`, event
identity, sequence numbering, cumulative acknowledgements and timestamps. It is frozen: a change
to a message format or a STATUS key is its own small pull request, reviewed by Godson, never part
of a process's pull request.

## Module layout

```
tactic/
  pom.xml            parent build: Java 21, every library and plugin version
  protocol/          shared contract and the small TCP helpers every process uses
  store-agent/       Store Agent process
  fault-link/        fault link process
  cfe/               Communications Front-End process
  master-inventory/  Central Master Inventory process
  system/            in-JVM harness and the end-to-end tests
  scripts/           demo.sh, check-readme-dependencies.sh
  docs/              design document
```

## Dependencies

### Modules and what they use

| Module | Uses | Used by |
|---|---|---|
| `protocol` | (no module) | every other module |
| `store-agent` | `protocol` | system |
| `fault-link` | `protocol` | system |
| `cfe` | `protocol` | system |
| `master-inventory` | `protocol` | system |
| `system` | `protocol`, `store-agent`, `fault-link`, `cfe`, `master-inventory` | (no module) |

```
                              system
            ┌──────────────┬────┴─────────┬──────────────────┐
            ▼              ▼              ▼                  ▼
       store-agent     fault-link        cfe         master-inventory
            └──────────────┴──────┬───────┴──────────────────┘
                                  ▼
                              protocol
```

An arrow means "uses" (a Maven compile dependency). `system` also uses `protocol` directly. The
four process modules do not use each other: they communicate only over TCP, with the message
formats in `protocol`.

### Libraries and build tools

Every version is pinned in `tactic/pom.xml`. CI fails when this table and the pom files differ
(`scripts/check-readme-dependencies.sh`).

| Group | Artifact | Version | Scope | Used by |
|---|---|---|---|---|
| `org.xerial` | `sqlite-jdbc` | `3.54.1.0` | runtime | `store-agent`, `cfe`, `master-inventory` |
| `org.junit.jupiter` | `junit-jupiter` | `5.14.4` | test | every module |
| `org.apache.maven.plugins` | `maven-clean-plugin` | `3.5.0` | build | every module |
| `org.apache.maven.plugins` | `maven-resources-plugin` | `3.5.0` | build | every module |
| `org.apache.maven.plugins` | `maven-compiler-plugin` | `3.16.0` | build | every module |
| `org.apache.maven.plugins` | `maven-surefire-plugin` | `3.6.1` | build | every module |
| `org.apache.maven.plugins` | `maven-jar-plugin` | `3.5.1` | build | every module |
| `org.apache.maven.plugins` | `maven-shade-plugin` | `3.6.2` | build | the four process modules |
| `org.apache.maven.plugins` | `maven-install-plugin` | `3.2.0` | build | every module |
| `org.apache.maven.plugins` | `maven-deploy-plugin` | `3.2.0` | build | every module |
| `org.apache.maven.plugins` | `maven-site-plugin` | `3.22.0` | build | every module |

The code uses only the Java 21 standard library and the libraries above. It is compiled for
Java 21 (`maven.compiler.release`).

### External services and resources

The tactic uses no network service outside its four processes: no database server, no message
broker, no internet access at run time. Maven downloads the libraries above from Maven Central
at build time only. Each process uses the resources below. All ports are on `127.0.0.1` and are
the defaults (see [Options](#options)).

| Process | File | Listens on | Connects to |
|---|---|---|---|
| `store-agent` | `demo-data/store-agent/store-agent.db` (SQLite) | 7101 (sales), 7102 (control) | 7201 (fault link) |
| `fault-link` | (no file) | 7201 (store), 7202 (control) | 7301 (CFE) |
| `cfe` | `demo-data/cfe/cfe.db` (SQLite) | 7301 (fault link), 7302 (control) | 7401 (master) |
| `master-inventory` | `demo-data/master-inventory/master-inventory.db` (SQLite) | 7401 (CFE), 7402 (control) | (none) |

The SQLite JDBC driver also writes its native library to the Java temporary folder
(`java.io.tmpdir`) at startup.

`scripts/demo.sh` needs these tools:

- bash 3.2 or later (Git Bash on Windows). Control commands use bash's `/dev/tcp`, so `nc` is not
  needed.
- Java 21 or later on the `PATH` (`java`).
- Maven 3.9 or later on the `PATH` (`mvn`).
- `kill` (with `-9` for the crash phases), `sleep`, `grep`, `sed`, `tr`, `cat`, `tail`, `rm` and
  `mkdir`. Git Bash includes all of them.
- `cygpath` on Windows is not needed: the script passes only paths relative to `tactic/`.
