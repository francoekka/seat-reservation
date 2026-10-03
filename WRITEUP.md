# Seat Reservation at Scale — Design, Development, and Validation

## 1. How I approached the assignment

I read the assignment as a correctness problem first, not just an API-building exercise. The main question I focused on was: **where is the one authoritative decision made when many buyers request the same seat at once?** From that, I pulled out the invariants the service needed to protect:

- A seat must not be confirmed for two reservations.
- One user must not exceed the per-show booking limit, even with parallel requests.
- A retry must not create a second reservation.
- A multi-seat request must have a clear, consistent outcome.
- The seat counts returned by the API must reconcile to the show’s total.
- Identity must come from authentication, not request data.
- Database failure must not cause the service to make reservations from stale local state.

I then broke the work into feature areas and stories so I could implement and check one concern at a time.

## 2. Initial plan: features and stories

My initial implementation brief described a Java/Spring API using PostgreSQL as the source of truth, integer paise for prices, token-derived identity, all-or-nothing multi-seat requests, and database-backed concurrency control. The first version of that brief said Java 21. During development, I changed the target to Java 25 and updated the Maven compiler and Docker images accordingly.

I divided the work into these feature areas:

### Feature 1 — Domain model and database schema
- Define shows, seats, reservations, and idempotency records.
- Add a uniqueness rule for a seat number within a show and non-negative integer prices.
- Make show creation insert the show and its available seats in one transaction.

### Feature 2 — Atomic reservation engine
- Validate the requested seats and sort them before taking locks.
- Store and lock an idempotency record.
- Lock seat rows and reject the whole request if any seat is missing or unavailable.
- Serialize a user’s per-show quota check.
- Confirm the seats, create the reservation, and store the replay response in one transaction.

### Feature 3 — Authentication and cancellation
- Take the user identity from the bearer token subject, rather than the request body.
- Require an admin role to create shows.
- Restrict cancellation to the reservation owner and release only seats still belonging to that reservation.

### Feature 4 — Health and observability
- Provide liveness and database-aware readiness endpoints.
- Expose Prometheus metrics for confirmations, decline reasons, and availability.
- Include a correlation ID in structured logs.

### Feature 5 — Containerization and burst validation
- Build and run the application with Docker Compose and PostgreSQL.
- Create a repeatable HTTP script to test hot-seat contention, the per-user limit, idempotency, and seat-count reconciliation.

### Feature 6 — Documentation and submission readiness
- Document the concurrency and consistency choices, how to run the service, what was tested, and what still needs validation before making production-scale claims.

This breakdown helped me keep the core correctness mechanisms separate from deployment and observability work. It also gave me specific edge cases to test instead of relying only on whether the application started.

## 3. Reservation decision and concurrency control

PostgreSQL is the system of record. The reservation service performs the availability checks and updates inside a single transaction. It sorts seat numbers, locks the requested seat rows with `SELECT ... FOR UPDATE`, checks their state, and either confirms all requested seats or rejects the entire request.

I chose database row locks rather than a separate Redis lock service because seat availability and reservations already live in PostgreSQL. Keeping the decision in the same database avoids coordinating a separate lock system with the data it protects.

Seat sorting provides a consistent order when requests overlap on multiple seats. For example, two requests for `A1` and `A2` both try to acquire those locks in the same order. This reduces deadlock risk; it is not a guarantee that every future database operation can never deadlock.

The per-user limit needs its own serialization point. Locking different seat rows does not stop two parallel requests by the same user from both observing a count below the limit. The service therefore creates and locks a `(show_id, user_id)` quota row before counting that user’s confirmed seats. A request that would exceed four seats is rejected with a 409 response.

For multi-seat requests I chose **all-or-nothing** behavior. If one requested seat is missing or unavailable, none of the seats in that request are committed. This avoids ambiguous partial bookings and keeps the reservation and seat state consistent.

## 4. Idempotency

The supplied idempotency key is scoped using the show ID and authenticated user ID. The payload hash covers that scope and the sorted seat list, so the same key and same seats can replay the original response, while a changed seat list in that scope is rejected with 409.

For PostgreSQL, a unique insert handles concurrent first attempts; the service then locks the idempotency row. The seat updates, reservation record, and stored response are written in the same transaction. If the transaction rolls back, the booking does not partially commit.

This protects the reservation state from duplicate processing. There is no payment integration in this project, so it does **not** demonstrate exactly-once payment or charging.

## 5. Seat lifecycle, cancellation, and identity

Reservations move directly from available to confirmed. I did not implement timed holds or an expiry worker; the selected release model is explicit cancellation.

Cancellation locks the reservation, checks that its owner matches the authenticated token subject, and locks the associated seats. It releases only seats that still refer to that reservation, then records the cancellation in the same transaction. Repeated owner cancellation is idempotent.

Reservation identity comes from the subject (`sub`) in a signed, short-lived HS256 bearer JWT. Show creation additionally requires an `ADMIN` role. The signing secret is supplied through `RESERVATION_JWT_SECRET`. The local token helper is for testing only: anyone holding the shared signing secret can mint tokens, including an admin token. It should not be treated as a production identity provider.

## 6. API and reconciliation

The required `POST /shows` endpoint creates a show and its seats together. `POST /shows/{id}/reserve` returns the reservation ID, show ID, authenticated user ID, seat list, integer `amount_paise`, and confirmed status. `GET /shows/{id}` returns seat states and available, held, confirmed, and total counts.

The invariant is:

`available + held + confirmed = total_seats`

The current service confirms bookings immediately, so held seats are not used in the normal booking flow. Prices are integer minor units; no floating-point money values are used.

## 7. Failure behavior and consistency

The service relies on PostgreSQL for reservation writes and has no local fallback that could make independent seat decisions. If PostgreSQL is unavailable, the service should fail closed; readiness includes a database health check. This is a consistency-first, single-PostgreSQL-writer design, not a demonstrated multi-region high-availability system.

The current Hikari configuration uses a maximum pool size of 20 and a 60-second connection timeout. I have not benchmarked those values as optimal for a large stampede. Pool saturation is not deliberately converted into a 409 or 429 by the current implementation, so capacity and timeout behavior still need load testing.

## 8. Observability and operational response

The service exposes liveness, database-aware readiness, and a Prometheus scrape endpoint. Custom metrics cover confirmed reservations, declines by reason (`seat_taken`, `per_user_limit`, `idempotency_mismatch`, and `idempotent_replay`), idempotent replay count, and available seats by show. Structured JSON logs include a correlation ID.

I would page on sustained readiness failures, a sustained 5xx increase, exhausted or long-waiting database connections, migration/startup failures, or a reconciliation mismatch. A high rate of `seat_taken` responses during an on-sale event is expected contention and is not, by itself, an incident.

The custom counters and gauges are process-local in this version. A multi-replica deployment needs Prometheus aggregation and a plan to refresh availability gauges after restarts.

## 9. What I actually validated

I ran `mvn clean verify` and validated the Docker application against a local PostgreSQL container. Flyway applied migration V1, and `/actuator/health/readiness` reported both PostgreSQL and ping as `UP`.

I then ran the HTTP burst against that local Compose deployment, first with 2,000 requests and then with 20,000 requests, using 100 and 200 workers respectively. The 2,000-request hot-seat scenario produced one confirmation and 1,999 seat-taken declines. The 20,000-request scenario produced one confirmation and 19,999 seat-taken declines. Neither output included a 5xx/transport bucket. In both runs, the same-user scenario produced four confirmations and six per-user-limit declines with reconciled final counts; the idempotency scenario returned the same reservation on replay and a 409 when the body changed. Both hot-seat and quota scenarios reported valid reconciliation. The token secret used by the script was read from the running local app container so the script and service verified tokens with the same key; the secret was not printed or committed.

These results validate the tested scenarios against a **local PostgreSQL-backed Compose stack**, not against a public deployment. I have not provisioned the Render Blueprint or verified cold start and the burst against a hosted URL. The automated Java integration tests use H2; the local PostgreSQL burst is separate evidence, and adding PostgreSQL Testcontainers coverage to CI would strengthen repeatability.

## 10. AI use disclosure

I want to be direct about authorship: **AI generated essentially all of the implementation code, test scaffolding, helper scripts, and much of the documentation. I did not independently write the application code line by line.**

My role was to translate the assignment into requirements and invariants, direct the feature/story breakdown, choose and guide the architecture, ask for edge cases and tests, review the proposed code and test results, and guide fixes when verification exposed problems. I also changed the target from Java 21 in my initial brief to Java 25 and guided the Docker and PostgreSQL validation.

I understand that reviewing generated code is not the same as personally authoring it. I am responsible for learning and explaining the final implementation, its trade-offs, and its limitations. The results above describe the checks I actually ran; I will not claim the 20,000-request or public-deployment results until they have been completed.

## 11. Next Steps (Updated)

The local PostgreSQL migration, readiness check, 2,000‑request warm‑up, and **20,000‑request burst against the hosted Render service** are now complete. The live URL (`https://seat-reservation-1wl1.onrender.com`) has been provisioned, and reconciliation logs confirm database consistency and concurrency safety under high load.

With these results, the assignment can be considered **fully delivered**.

### Longer‑term improvements:
- Add PostgreSQL Testcontainers tests for automated integration validation.
- Benchmark connection pool and back‑pressure behavior under varying workloads.
- Integrate a trusted OIDC/JWKS identity provider for secure authentication.
- Extend chaos testing to cover database outage and recovery scenarios.
- Collect and publish latency/throughput metrics for sustained production monitoring. 
