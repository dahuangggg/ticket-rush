# ADR-0003: AI is read-only

- Status: Accepted and implemented
- Date: 2026-07-21

## Context

Natural-language input and retrieved text are untrusted. A model can misunderstand intent or follow prompt injection. Ticket purchase, payment, cancellation, inventory, and administration have financial or scarce-resource effects.

Putting the normal Rush Request method behind an AI tool would not make the model safe merely because the backend uses Lua.

## Decision

The AI tool registry is an explicit read-only allowlist.

Allowed capabilities:

- search events;
- get event detail;
- list Ticket SKUs;
- query the authenticated user's orders.

Forbidden capabilities:

- rush or reserve;
- create an order;
- pay or cancel;
- initialize or mutate inventory;
- create or mutate reminders;
- perform admin operations.

There is no AI rush-ticket tool.

User identity is derived from authenticated context. Query Modules enforce ownership independently of the tool Adapter.

When asked to mutate, the assistant explains that the user must use the normal application interface.

## Consequences

Positive:

- prompt injection cannot reach a mutation tool;
- AI mistakes cannot reserve scarce inventory;
- deterministic business Modules remain the only write paths;
- tool auditing and authorization are simple.

Costs:

- the assistant cannot provide one-step purchasing convenience;
- reminder creation remains a normal explicit user action rather than conversational automation;
- read DTOs and ownership checks still require maintenance.

## Alternatives

### Allow AI to call the normal Rush Request flow

Rejected. Sharing the backend flow protects stock mechanics but does not provide reliable user consent or defend against prompt injection.

### Ask for confirmation before a write tool

Rejected as the sole control. Model-mediated confirmation is not an authorization boundary.

### Allow low-risk reminder mutation

Rejected to keep the tool registry mechanically read-only and easy to audit.

## Enforcement

- Register only approved query tool objects.
- Test tool enumeration for streaming and non-streaming assistants.
- Keep mutation Modules unreachable from AI configuration.
- Treat chat memory and any future RAG documents as untrusted data. RAG is not part of the current runtime.
- Audit tool calls without sensitive payloads.

The current AI configuration registers only event and order query tools. Regression tests must keep the allowlist read-only.
