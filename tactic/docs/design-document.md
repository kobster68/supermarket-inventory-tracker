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

TODO(Godson, #30): the sale transaction (stock change, Outbox row, next sequence), the Outbox,
the sender to the CFE, link-down marking, and restart from accepted-through.

### 4.2 Fault link (owner: Leon Letournel)

TODO(Leon, #33): forwarding in both directions; drop, duplicate, delay, cut and restore; the
seeded random source.

### 4.3 Communications Front-End (owner: Kobe LaPrade)

TODO(Kobe, #31): the acceptance rule and its transaction, re-acknowledging duplicates, refusing
gaps, and the sender to the master.

### 4.4 Central Master Inventory (owner: Mike Andrews)

TODO(Mike, #32): the apply transaction (stock change, applied-event record, cursor), duplicate
detection by event ID and by sequence, and seed-once initial stock.

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
