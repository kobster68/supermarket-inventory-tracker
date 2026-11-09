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

This is the step-0 scaffold: every process starts, binds its ports and answers `STATUS`, and the
shared contract in `protocol` is complete. Tactic behaviour is marked `TODO(<owner>, #<issue>)`
in the code.

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

TODO(Leon, #33; Godson, #34): `scripts/demo.sh` builds everything, runs the two-minute scripted
fault demo, and prints the verdict table last. Until then it prints its phases and exits.

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

TODO(Leon, #33): table and diagram of which module uses which. In short: every process module
uses `protocol`; `system` uses all four process modules; nothing uses `system`.

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

TODO(Leon, #33): no network service outside the four processes; each process's SQLite file and
the ports it listens on and connects to; the shell tools the demo script needs, by name.
