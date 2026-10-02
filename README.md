# Usage Rating and Invoice Reconciliation Service

Kotlin 2.3 · Spring Boot 4.1 · PostgreSQL 17 · Liquibase · Gradle 9 (Kotlin DSL, wrapper committed) · Java 17

The service accepts usage transactions, rates them asynchronously against effective-dated price rules, issues
monthly invoices and produces reconciliation evidence that traces every amount back to its event and rule.

## Commands

Prerequisites: JDK 17+ and a running Docker engine (integration tests use Testcontainers).
On Windows use `gradlew.bat` instead of `./gradlew`.

| What | Command |
|---|---|
| Full build: compile, unit tests, coverage gate, integration tests, jar | `./gradlew build` |
| Unit tests only (no Docker) | `./gradlew test` |
| Integration tests only (Testcontainers) | `./gradlew integrationTest` |
| Coverage report (unit tests) | `./gradlew test jacocoTestReport` → `build/reports/jacoco/test/html/index.html` |
| Coverage gate only (fails below 85% line coverage) | `./gradlew jacocoTestCoverageVerification` |
| Start app + database | `cp .env.example .env` then `docker compose up --build` |
| Run locally against your own PostgreSQL | `DB_PASSWORD=... TENANT_API_KEYS=tenant-a:key-a ./gradlew bootRun` |

**Migrations** are Liquibase changelogs in `src/main/resources/db/changelog/` and run automatically at startup
(`001-schema.sql` for the schema, `002-seed-data.sql` for two demo tenants and their price rules). Starting the
container is the migration command; `./gradlew integrationTest` also applies them to a throwaway database.

**Configuration** (environment variables): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (required, no default),
`TENANT_API_KEYS` (`tenantId:apiKey,...`), `INGESTION_MAX_PAYLOAD_BYTES` (default 65536), and `RATING_*` tuning knobs in `application.yml`. Compose
reads them from `.env`, which is git-ignored.

**Operations**: `GET /actuator/health` (with `/liveness` and `/readiness` probes). Graceful shutdown is enabled:
in-flight requests and rating transactions get up to 30 s to finish; anything interrupted is rolled back and
picked up again (see "Asynchronous processing").

**OpenAPI**: `http://localhost:8080/v3/api-docs` and Swagger UI at `http://localhost:8080/swagger-ui.html`.

## API

Every `/api/**` request needs `X-API-Key`. The key determines the tenant; see "Tenant isolation".

```bash
KEY=local-key-tenant-a   # from .env

# Ingest (the transaction shape from the specification, unchanged)
curl -i -X POST localhost:8080/api/v1/transactions -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{
  "eventId": "73d4e120-77d0-4f11-a6d2-f3b43b430d9c",
  "tenantId": "tenant-a", "customerId": "customer-42",
  "transactionCode": "VEHICLE_REGISTRATION",
  "occurredAt": "2026-08-15T14:22:31Z", "quantity": 2,
  "metadata": {"source": "upstream-api", "batchId": "batch-2026-08-15-01"}
}'

# Invoice summary for a time range (by occurredAt, half-open [from, to))
curl -s "localhost:8080/api/v1/invoices/summary?customerId=customer-42&from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z" -H "X-API-Key: $KEY"

# Reconciliation evidence for a customer and period
curl -s "localhost:8080/api/v1/reconciliation?customerId=customer-42&from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z" -H "X-API-Key: $KEY"

# Issue (close) the invoice for an ended month; repeat calls return the same invoice
curl -s -X POST localhost:8080/api/v1/invoices -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"customerId": "customer-42", "billingPeriod": "2026-08"}'

# Price rules, and reprocessing a transaction that was UNRATED or FAILED
curl -s localhost:8080/api/v1/pricing-rules -H "X-API-Key: $KEY"
curl -s -X POST localhost:8080/api/v1/pricing-rules -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"transactionCode": "INSPECTION", "unitPrice": 4.50, "effectiveFrom": "2026-11-01T00:00:00Z"}'
curl -s -X POST localhost:8080/api/v1/transactions/73d4e120-77d0-4f11-a6d2-f3b43b430d9c/reprocess -H "X-API-Key: $KEY"
```

| Endpoint | Responses |
|---|---|
| `POST /api/v1/transactions` | `202 ACCEPTED` stored, rating pending · `200 DUPLICATE` same eventId and same billable content, nothing new billed · `409 DUPLICATE_CONFLICT` same eventId, different content, original kept · `400 REJECTED` validation errors (all listed) · `403 REJECTED` payload `tenantId` ≠ authenticated tenant · `413` body over `INGESTION_MAX_PAYLOAD_BYTES`, not stored · `401` missing/unknown key |
| `POST /api/v1/transactions/{eventId}/reprocess` | `202 REQUEUED` · `404` unknown in this tenant · `409` not UNRATED/FAILED |
| `GET /api/v1/invoices/summary?customerId&from&to` | counts, quantities and amounts per transaction code, total, and `unratedTransactionCount` |
| `GET /api/v1/reconciliation?customerId&from&to` | counts (received, accepted, duplicate, rejected, pending, unrated, failed, rated, invoiced), amounts, and per-event rows with price rule, unit price, amount, billing period, invoice id |
| `POST /api/v1/invoices` | `201` issued now · `200` already issued (same invoice) · `409` period not ended, or usage that occurred in the period is still `PENDING` rating |
| `GET /api/v1/invoices/{id}`, `GET /api/v1/invoices?customerId` | invoice with lines and the charges behind them |
| `GET/POST /api/v1/pricing-rules` | `201` · `409` overlapping effective period · `400` invalid |

Errors use RFC 9457 problem details.

**Contract compatibility.** The ingestion request is exactly the specified shape. Additions: `quantity` may be a
decimal (up to 6 places), unknown fields are ignored (forward compatible), and `metadata` is stored but is not
part of duplicate detection. The authenticated `X-API-Key` header is new and required.

## Architecture

A single Spring Boot application, packaged by feature: `tenancy`, `ingestion`, `pricing`, `rating`,
`invoicing`, `reconciliation`, `common`. Spring Data JPA for entities and tenant-scoped queries; native SQL only
where PostgreSQL semantics matter (`ON CONFLICT`, `FOR UPDATE SKIP LOCKED`, advisory locks).

```
POST /transactions ──► IngestionService (tx) ──► usage_transaction [PENDING] + processing log
                                    │ after commit
                                    ▼
          RatingDispatcher (@Async) ─┐        RatingScheduler (poll, safety net)
                                     ▼                 ▼
                              RatingWorker ──► RatingService (tx per transaction)
                                                 claim row FOR UPDATE SKIP LOCKED
                                                 effective rule → amount → billing period lock
                                                 rated_charge + status RATED/UNRATED
POST /invoices ──► InvoiceService (tx): period lock → collect uninvoiced charges → invoice + lines → link charges
GET summary / reconciliation ──► read-only REPEATABLE READ snapshots
```

### Data model

| Table | Role |
|---|---|
| `tenant` | Tenant id and billing currency. |
| `price_rule` | Immutable per-unit price per tenant + transaction code over `[effective_from, effective_to)`. A GiST exclusion constraint forbids overlapping intervals. |
| `usage_transaction` | Accepted input: parsed fields, the **raw payload exactly as received**, a fingerprint of the billable fields, and processing state (`PENDING`, `RATED`, `UNRATED`, `FAILED`, attempts, next attempt, reason). Unique `(tenant_id, event_id)`. Input columns are never updated. |
| `transaction_processing_log` | Append-only history of every outcome (ACCEPTED, RATED, UNRATED, ATTEMPT_FAILED, FAILED, REQUEUED), so reprocessing never erases what happened before. |
| `duplicate_delivery` | Every re-delivery, with its raw payload and whether its content matched the original. |
| `rejected_submission` | Every rejected submission, with the raw payload and all reasons. |
| `rated_charge` | The rating result: transaction, **applied rule id**, unit price at the time, rounded amount, currency, billing period, late-arrival flag, invoice link. Unique `transaction_id`. |
| `invoice`, `invoice_line` | Issued invoice per customer and month (unique), with lines per transaction code. |

**Money**: `NUMERIC(19,6)` for prices and quantities, `NUMERIC(19,4)` for amounts, `BigDecimal` throughout.
Each transaction is rounded once, to the currency's minor unit, `HALF_EVEN`. Invoice lines and totals are plain
sums of those rounded amounts, so a total always equals the sum of the charges a reviewer can list. API
responses present amounts at the currency's minor-unit scale (`27.50` USD, never `27.5000`). The rescale is
exact (`RoundingMode.UNNECESSARY`), because stored amounts are already rounded to that scale. Unit prices keep
their full 6-decimal precision.

**Database-enforced invariants**: one transaction per `(tenant, eventId)`; one charge per transaction; one
invoice per `(tenant, customer, month)`; no overlapping rules; positive quantity, non-negative price and amount;
and composite foreign keys on `(tenant_id, …)` so a charge cannot point at another tenant's transaction, rule or
invoice.

### Transaction boundaries

| Operation | Boundary | Why |
|---|---|---|
| Ingestion | `@Transactional` (REQUIRED, READ COMMITTED) | Insert, log entry and queued event commit or roll back together. `INSERT … ON CONFLICT DO NOTHING` makes concurrent duplicates resolve without an exception that would poison the transaction. |
| Rating | One `@Transactional` per transaction | Claim, rule lookup, period lock, charge insert and status change are atomic. Any exception rolls everything back. |
| Failure recording | Separate `@Transactional`, called after the rating transaction rolled back | The attempt counter and reason must survive the rollback of the work they describe. |
| Processing log, period lock | `Propagation.MANDATORY` | They are meaningless outside the caller's transaction (a log entry without its change; a transaction-scoped lock without a transaction). Calling them without one fails fast; an integration test proves it. |
| Invoice issuance | `@Transactional` | Period lock → read uninvoiced charges → insert invoice and lines → link charges, atomically. |
| Summary, reconciliation | `readOnly`, `REPEATABLE READ` | Several queries must see one snapshot so counts and amounts add up while workers commit. |

**When async work becomes eligible**: a `PENDING` row is invisible to workers until the ingesting transaction
commits (READ COMMITTED), and the dispatch event is a `@TransactionalEventListener(AFTER_COMMIT)`, so a
rolled-back ingestion can never be rated. Both are covered by integration tests.

**Concurrency**: READ COMMITTED with explicit locks rather than SERIALIZABLE. Workers claim rows with
`FOR UPDATE SKIP LOCKED`. Rating and invoice issuance for the same tenant + customer + month are serialized by a
transaction-scoped advisory lock (`pg_advisory_xact_lock`). Without it, a charge could commit into a month just
after that month's invoice had read its charges. With it, either the charge is on the invoice or it sees the month
as closed and moves to the next one.

### Asynchronous processing and multiple instances

The database is the queue (an outbox pattern without a broker):

- **Fast path**: after commit, `RatingDispatcher` rates the new transaction on Spring's task executor.
- **Safety net**: `RatingScheduler` polls due `PENDING` rows every `RATING_POLL_INTERVAL` (default 5 s). It covers
  lost dispatches, restarts, and retries after backoff.

**Guarantees.** Any number of instances can run both paths.

- `SKIP LOCKED` means each row is rated by at most one worker at a time.
- The unique constraint on `rated_charge.transaction_id` backs that up, so there is never a second charge.
- A crash mid-rating rolls back with the connection, and the row is picked up again.
- Failures are retried with linear backoff (`RATING_RETRY_BACKOFF`). After `RATING_MAX_ATTEMPTS` the row is parked as `FAILED` (poison event), visible in reconciliation, and re-queueable.

**Limits.**

- Delivery to the worker is at-least-once, so rating must be, and is, idempotent.
- Ordering across transactions is not guaranteed and is not needed: each rating depends only on its own row and the rules.
- A crash *during* a rating attempt does not increment the attempt counter, so an event that kills the JVM would be retried indefinitely. A claim-with-lease step would bound that.
- Throughput is bounded by PostgreSQL. At much higher volume, a log such as Kafka partitioned by tenant would replace polling.

### Idempotency

The idempotency key is `(tenantId, eventId)`, enforced by a unique constraint. A re-delivery:

- is detected by a SHA-256 fingerprint of the billable fields (`customerId`, `transactionCode`, `occurredAt`, normalized `quantity`);
- with a matching fingerprint, answers `200 DUPLICATE` with the original's status;
- with a different fingerprint, answers `409 DUPLICATE_CONFLICT`;
- in both cases is recorded in `duplicate_delivery` and never touches the original.

`metadata` is excluded from the fingerprint because a retry may legitimately come from a different upstream batch.

### Tenant isolation

1. **Authentication**: `ApiKeyAuthenticationFilter` maps `X-API-Key` to a tenant and binds it to `TenantContext`
   for the request. The payload's `tenantId` is only checked against it (mismatch → `403`, recorded).
2. **Service boundary (AOP)**: `@TenantScoped` + `TenantContextAspect` (below).
3. **Queries**: every repository query takes `tenantId` and filters on it. The inherited `findById` is not used
   for tenant data. A resource in another tenant is reported as `404`, the same as one that does not exist.
4. **Database**: composite tenant foreign keys, as listed above.

The one cross-tenant query is the worker's "find due work", which returns only `(tenantId, id)` pairs. Each item
is then processed inside its own tenant's context.

### Spring AOP: `@TenantScoped`

- **Pointcut**: `@within(TenantScoped) || @annotation(TenantScoped)`, applied to the service classes for ingestion, rating, pricing, invoicing, summary and reconciliation.
- **Advice**: `@Before`. It fails with `MissingTenantContextException` when no tenant is bound. It fails with `TenantMismatchException` when a parameter annotated `@TenantId` differs from the bound tenant. Either way, the call never reaches the target.
- **Ordering**: `@Order(HIGHEST_PRECEDENCE + 10)`.
  - That is after Spring's `ExposeInvocationInterceptor` (`HIGHEST_PRECEDENCE + 1`), which AspectJ-style advice needs. A unit test caught the bug when the aspect was first declared at `HIGHEST_PRECEDENCE`.
  - That is also before the transaction interceptor (`LOWEST_PRECEDENCE`), so a rejected call never opens a transaction. An integration test verifies that the transaction manager is never called.
- **Proxy limits**:
  - The aspect uses Spring's proxy-based AOP. Only calls that come through the Spring proxy are checked, so a call from a method to another method of the same object (self-invocation) is not.
  - Kotlin classes are final, but the `kotlin-spring` plugin opens Spring components so they can be proxied.
  - This is why the aspect is a fail-fast guard, not the isolation mechanism. Queries and database constraints enforce isolation regardless.
- **Async propagation**: `TenantContext` is a `ThreadLocal`, so it does not cross threads on its own.
  - `TenantContextTaskDecorator`, which Spring Boot applies to its task executor, copies the tenant into `@Async` tasks.
  - The scheduler has no request context. The worker binds each row's own `tenant_id` before calling the tenant-scoped service.

### Reconciliation terminology

The specification asks to distinguish accepted, duplicate, rejected, unrated, rated and invoiced transactions.
The report's `counts` map to those terms as follows:

| Specification term | Report field(s) | Meaning |
|---|---|---|
| accepted | `accepted` | Stored transactions (unique per tenant + eventId) whose `occurredAt` is in the range. |
| duplicate | `duplicate` | Re-deliveries of those eventIds, with matching or conflicting content (`fingerprintMatches`). |
| rejected | `rejected` | Submissions that failed validation, attributed to the customer and period when those fields were parseable. |
| unrated | `pending` + `unrated` + `failed` | Accepted but carrying no charge. `pending`: waiting for, or retrying, rating. `unrated`: rated, but no price rule was effective at `occurredAt`. `failed`: rating kept failing and the event was parked after the attempt budget. |
| rated | `rated` | Charged but not yet on an invoice. |
| invoiced | `invoiced` | Charged and linked to an issued invoice. |

`accepted = pending + unrated + failed + rated + invoiced`, and `received = accepted + duplicate + rejected`.
The invoice summary's `unratedTransactionCount` uses the specification's broad sense (`pending + unrated + failed`).

### Late arrivals: next-open-period policy

A transaction is priced with the rule effective at its `occurredAt`, which is always the price at the time of use.
It is billed in its own month unless that month already has an issued invoice. In that case it goes to the first
later month without an invoice, with `lateArrival=true`, and its original `occurredAt` stays visible.

**What `lateArrival` means exactly:** the charge's billing period differs from the month of its `occurredAt`,
because that month was already invoiced when the transaction was rated. It does *not* mean "received after the
month ended". A transaction for August received on 3 September, before August is invoiced, is billed in August
with `lateArrival=false`; `occurredAt` and `receivedAt` are both reported, so "received late" can always be
derived. Invoices are protected from the avoidable case: a month cannot be invoiced while usage that occurred in
it is still `PENDING` rating (`409`). Otherwise a rating backlog alone would push on-time usage into the next
invoice. `UNRATED` and `FAILED` usage do not block issuance: they need operator action (add a rule, fix the
cause, `/reprocess`), are counted in reconciliation, and are billed as late arrivals once rated.

- **Trade-off**: issued invoices are immutable and every late charge is visible and traceable.
- **Cost**: the customer sees it a period later, and a usage-period view (the summary, by `occurredAt`) can differ from a billing-period view (the invoices). Reconciliation shows both: `occurredAt` and `billingPeriod` per event.
- **Rejected alternatives**: reopening an invoice would rewrite a financial document. Credit/debit notes would be the next step if late usage had to land in its original period.

### Simplifications and assumptions

- **Billing period**: a calendar month in UTC. An invoice can be issued only after its month has ended and once none of its usage is still `PENDING`. Issuing an empty month produces a zero invoice and closes it.
- **Request size**: ingestion bodies are limited to 64 KiB by default. Larger bodies get `413` and are not stored as rejection evidence, which would otherwise be a storage-amplification vector.
- **Invoice lifecycle**: issued and immutable. There are no drafts, payments, voids or credit notes.
- **Currency**: one billing currency per tenant.
- **Authentication**: static API keys from configuration stand in for real authentication (e.g. OAuth2 with a tenant claim).
- **Customers**: not modelled beyond an id string.
- **Price rules**: per tenant + transaction code, not per customer. Rules are immutable and can only be created; correcting a price means re-rating with a superseding record, which is not implemented.
- **Unknown transaction code**: accepted but `UNRATED`, not rejected, so a missing rule never loses usage.
- **Rejections without a parseable `occurredAt`**: attributed to the month in which they were received.
- **Future-dated `occurredAt`**: accepted. No business rule was specified.

## Testing

| Suite | Location | What it proves |
|---|---|---|
| Unit (`test`) | `src/test` | Rating arithmetic and rounding, effective-date boundaries, validation, fingerprinting, state machine, retry/poison handling, late-arrival period search, invoice building, reconciliation classification, aspect behaviour on a real AOP proxy, filter, context propagation. `@WebMvcTest` slices cover the HTTP contract with mocked services (no Docker). |
| Integration (`integrationTest`) | `src/integrationTest` | `@DataJpaTest` against PostgreSQL with the Liquibase schema: every database invariant above. `@SpringBootTest` with Testcontainers (`@ServiceConnection`, one shared container): idempotency including 12 concurrent deliveries, end-to-end rating, rule boundary to the microsecond, unrated → rule added → reprocessed with history kept, invoicing idempotency and concurrency, late arrival, concurrent workers, reconciliation totals, tenant isolation through every endpoint, AOP ordering before the transaction, rollback after a mid-rating failure, MANDATORY propagation, after-commit dispatch and no dispatch on rollback, a pending-usage invoice block (with period-boundary events), 413 with nothing stored, and amount formatting. `PeriodLockRaceIT` deterministically proves the period lock in both directions. One side holds its transaction open on a latch; the other side is observed waiting on the advisory lock in `pg_locks`. Both tests fail when the lock is removed. |

**Coverage gate.** `check` depends on `jacocoTestCoverageVerification`, which fails below 0.85 line coverage.
The gate is measured on the `test` suite only. Integration tests (Testcontainers) never count towards it.

**What the gated suite contains.** The `test` suite is defined as "tests that need no external infrastructure".
That means plain unit tests plus `@WebMvcTest` web slices. The slices start a minimal Spring MVC context with
mocked services, but no database, network or Docker, and they are the tests for controllers, the API-key filter
and the error mapping. Their contribution matters: without them, line coverage of the gated suite is
81.2% (698 of 860 lines), below the gate. Anyone who counts only context-free tests as unit tests should know that.

**Exclusions.** The only exclusion is `UsageRatingApplication*`, the Spring Boot `main()` bootstrap. Domain,
service, rating, tenancy and reconciliation code are all in scope.

**Coverage report.** `docs/coverage/unit-test-coverage.csv` is the JaCoCo CSV from the submitted build (unit
suite: 836 of 860 lines covered, 97.2%). Regenerate it, and the browsable HTML version, with
`./gradlew test jacocoTestReport`.
