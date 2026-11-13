# Store Inventory tactic: design document

Transactions and Retry for scenario AV-2. Each process owner writes their own section; Godson
assembles the document, adds the diagrams, and exports it to PDF with pandoc (target 5 to 8
pages). For long-form rationale this document points to the architecture document.

## 1. The scenario: AV-2

TODO(Godson, #34): AV-2 quoted from the architecture document, with its rating, and the response
measure the verdict checks: "100% of store events reach the DC; 0 events lost; 0 events applied
twice."

## 2. The tactics

TODO(Godson, #34): Transactions (*Software Architecture in Practice*, 4th ed., p. 61) and Retry
(p. 60), each with the book's definition and where it lives in our code. Which parts of the
architecture document's interface are implemented (publish, cumulative accepted and applied
acknowledgements, event identity and per-source sequence) and which are not (subscribe,
replay-from-sequence, resync, topics, retention), and why resending after the last
acknowledgement plays the role replay plays in the document for these faults.

## 3. Modules and what they use

TODO(Godson, #34): the module uses diagram, repeated from the README's Dependencies section.

## 4. The processes and their transaction boundaries

### 4.1 Store Agent (owner: Godson Umoren)

- **Transaction boundary.** One SQLite transaction per sale: subtract the quantity from the
  product's stock, insert the Outbox row (new event ID, the next sequence number, product,
  `delta` = −quantity, UTC timestamp, not yet accepted) and advance the sequence. Only after the
  commit does the store answer `OK|eventId|sequence`. A failure anywhere rolls back all three, so
  stock and Outbox never disagree and no sequence number is used up or reused. Sales are accepted
  whether or not the link is up.
- **Outbox sender (Retry).** Sends events after the accepted-through position in sequence order,
  at most 100 unacknowledged. Each `ACK|accepted|source|through` is cumulative: it marks every row
  up to `through` accepted in SQLite; a stale or duplicate acknowledgement never moves the position
  backwards. With no progress within 500 ms (counted from the first send of a burst or the
  position's last move), or when the connection fails, the store reconnects
  after a backoff (100 ms doubling to 2 s, up to 20% jitter) and resends everything after the
  position with the original event IDs.
- **Link down.** After 10 consecutive failed attempts the store sets `linkState=DOWN`, logs it and
  counts a link-down episode. It keeps every event and keeps probing every 2 s; the first
  acknowledgement that moves accepted-through sets it `UP`. An acknowledgement that only repeats
  accepted-through (the CFE refusing an event beyond a gap) is not progress: it neither resets the
  count nor sets the link `UP`, so a CFE that answers but accepts nothing is marked `DOWN` too.
- **Restart.** The accepted flag is durable, so a restarted store sends from accepted-through + 1
  and asks the CFE nothing; the CFE's contiguity rule and cumulative acknowledgements realign both
  sides. Initial stock is seeded once, when the file is created.

### 4.2 Fault link (owner: Leon Letournel)

TODO(Leon, #33): forwarding in both directions; drop, duplicate, delay, cut and restore; the
seeded random source.

### 4.3 Communications Front-End (owner: Kobe LaPrade)

- **Transaction boundary.** The event-log row and its source's accepted-through position commit
  in one SQLite transaction. Only after commit does the CFE send
  `ACK|accepted|source|through`; an acknowledgement therefore promises an event that survives a
  CFE restart. A failed insert or cursor update rolls back both changes and gets an error reply.
- **Acceptance rule.** Accept only the next contiguous sequence for each source. An event ID
  already held, or a sequence at or below accepted-through, is re-acknowledged without another
  row and counted in `duplicatesReacked`. An event beyond a gap is not stored; the reply gives
  the current accepted-through. Sources have independent positions, and STATUS reads all known
  sources from SQLite (plus the configured source even before its first event).
- **Forwarding (Retry).** Forward the original stored events to the master in sequence order
  within each source, with at most 100 outstanding events across the connection. Only cumulative
  `ACK|applied` replies for events sent on that connection advance durable delivered-through;
  stale, duplicate, wrong-kind, unknown-source and ahead-of-sent acknowledgements cannot advance
  it. Repeated acknowledgements do not trigger resends or reset the progress timeout. An idle
  connection gives newly sent events a fresh 500 ms timeout.
- **Failure and restart.** A disconnect or progress timeout closes the connection, waits a
  backoff (100 ms doubling to 2 s, with up to 20% jitter), then reconnects and resends after each
  source's durable delivered-through with the same event IDs. After 10 consecutive failures the
  master link is logged DOWN and probing continues at the capped backoff; only an advancing
  applied acknowledgement resets failures and marks it UP. Events remain in the log throughout.
  On restart, the CFE reopens its log and positions and resumes from delivered-through + 1,
  accepting store traffic independently of master availability.

### 4.4 Central Master Inventory (owner: Mike Andrews)

- **Apply transaction.** `MasterDatabase.apply` adds the event's signed `delta` to stock, records
  its ID and source sequence, and advances the cursor in one SQLite transaction. `MasterInventory`
  sends `ACK|applied|source|through` after that transaction commits.
- **Duplicates and gaps.** A recorded event ID or a sequence at or below the cursor returns the
  saved position and increments `duplicatesIgnored`. An event ahead of the next sequence returns
  the current position so the CFE can resend the missing events. Stock changes once for each new,
  contiguous event, making retries idempotent.
- **Restart and status.** The demo accepts one configured store source. Each product starts at
  1,000,000 units when the database is created. Restarts reopen the saved stock, applied records and
  cursor. Durable STATUS values come from SQLite. The informational duplicate counter starts at
  zero on each restart.
- **Concurrency and failure tests.** Apply, STATUS and database close use the same database lock.
  An apply failure rolls back the transaction. A test-only hook fails after the stock
  update to check rollback. Wire tests cover retries, gaps, restart and concurrent requests.

## 5. Sequence diagrams

TODO(Godson, #34): the normal path and the outage-and-resend path (PlantUML).

## 6. Retry policy

TODO(Godson, #34): window 100; acknowledgement timeout 500 ms; backoff from 100 ms doubling to
2 s with up to 20% jitter; link marked down after 10 consecutive failed attempts, every event
kept, probing at 2 s.

## 7. Why not two-phase commit

TODO(Godson, #34)

## 8. Results from a real run

TODO(Godson, #34): the verdict table printed by `scripts/demo.sh`.
