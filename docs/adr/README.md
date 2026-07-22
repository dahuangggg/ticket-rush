# Architecture decision records

ADRs capture durable tradeoffs. They do not prove implementation completion; consult [current versus target](../architecture/current-vs-target.md).

| ADR | Status | Decision |
|---|---|---|
| [0001](0001-redis-reservation-journal-relay.md) | Accepted and implemented; local happy path verified, fault injection pending | Redis Lua Reservation Journal and Kafka relay |
| [0002](0002-mysql-release-intent.md) | Accepted and implemented; real-service transaction/replay tests passed, crash injection pending | MySQL transactional Release Intent |
| [0003](0003-ai-read-only.md) | Accepted and implemented | AI tool boundary is read-only |
| [0004](0004-flyway-schema-truth.md) | Accepted and implemented; blank-database migration verified, guarded legacy upgrade pending | Flyway is the single schema truth |

## Status meanings

- **Proposed:** under discussion.
- **Accepted:** the project should follow this decision.
- **Accepted and implemented:** the source follows the decision; the status may still name external proof that has not run.
- **Superseded:** replaced by another ADR.
- **Rejected:** considered and not chosen.

To change an accepted decision, add a new ADR that links and supersedes the old one. Do not rewrite history to hide the previous tradeoff.
