# Seat Reservation at Scale

This service uses PostgreSQL as its system of record. Reservation requests are **all-or-nothing**: every requested seat is committed together, or none is. Seat rows are locked with PostgreSQL `SELECT ... FOR UPDATE` in sorted seat-number order. A separate `(show_id, user_id)` lock row serializes a user’s quota check across requests for different seats.

H2 is used by fast integration tests. The production PostgreSQL schema and locking paths have also been validated locally with Docker Compose.

## Build and run

A clean local build requires Java 25 and Maven 3.9 or later. The container build includes Maven and does not require a Maven wrapper.

```bash
mvn clean verify
docker compose up --build
```

- API: `http://localhost:8080`
- PostgreSQL: `localhost:5432`

If host port 5432 is already in use, set `POSTGRES_HOST_PORT=5433` before starting Compose. The app continues to connect to PostgreSQL on the internal port 5432.

Set `POSTGRES_PASSWORD` and `RESERVATION_JWT_SECRET` in the environment before using Compose. JWT secrets must be at least 32 bytes. Direct non-Compose startup deliberately fails if the JWT secret is missing.

## Authentication

The API accepts short-lived HS256 bearer JWTs. The required `sub`, `iat`, and `exp` claims provide identity and expiry. Show creation additionally requires `roles: ["ADMIN"]`.

Deployments must inject `RESERVATION_JWT_SECRET` and issue tokens through a trusted identity provider. For local testing, the token helper can mint a user token:

```bash
python3 scripts/mint_token.py buyer-1
```

Append `ADMIN` to mint a local admin token. The helper is for local testing only; anyone with the shared signing secret can mint tokens.

## API examples

### Create a show

```http
POST /shows
Authorization: Bearer <admin-token>
Content-Type: application/json

{"name":"friday-night","seats":["A1","A2","A12"],"price_paise":25000}
```

### Reserve seats

The authenticated identity comes from the JWT subject. Any `user_id` in the request body is ignored.

```http
POST /shows/<show-id>/reserve
Authorization: Bearer <user-token>
Idempotency-Key: order-unique-key
Content-Type: application/json

{"seats":["A12"]}
```

`GET /shows/<show-id>` returns each seat and the `available`, `held`, `confirmed`, and `total_seats` counts.

`POST /reservations/<reservation-id>/cancel` releases seats only for the owning JWT subject. Booking is immediately confirmed; temporary holds are not created. Cancellation is serialized on the reservation row and releases only seats still attached to that reservation.

`GET /shows` and `DELETE /shows/<show-id>` are compatibility endpoints retained from the starter project. The required `POST /shows` contract creates the show and all its seats atomically.

## Idempotency and consistency

Idempotency keys are hashed with `(show_id, authenticated user_id)` to create a database-unique scope. The canonical payload hash includes the sorted seat list.

A unique insert and `FOR UPDATE` serialize retries:

- A completed retry with the same payload returns the original stored response.
- Reusing the same key with a changed payload returns `409 Conflict`.

PostgreSQL is the authority. Requests fail closed when the database is unavailable, and readiness includes the database health contributor. The service does not claim availability through a database partition.

## Deploy to Render

The repository includes a `render.yaml` Blueprint for a Docker web service and PostgreSQL database. Push the repository to GitHub, create a Render Blueprint from it, provide a strong `RESERVATION_JWT_SECRET` of at least 32 bytes, and wait for `/actuator/health/readiness` to pass.

A public live URL should be published only after the Render deployment is actually healthy.

## Observability

- Liveness: `/actuator/health/liveness` (process-only)
- Readiness: `/actuator/health/readiness` (includes PostgreSQL health; returns non-UP if the database cannot be reached)
- Prometheus: `/actuator/prometheus`
- Metrics:
    - `reservations_confirmed_total`
    - `reservations_declined_total{reason="seat_taken|per_user_limit|idempotency_mismatch|idempotent_replay"}`
    - `reservations_idempotent_replays_total`
    - `seats_available_gauge{show_id="..."}`
- Logs are JSON and carry `x-correlation-id`. Clients may supply this header; otherwise, the server generates one.

Page on readiness failure, sustained 5xx responses, database pool exhaustion, or a discrepancy between a show’s total seats and the sum of its status counts. An increase in `seat_taken` responses by itself is expected during a sale.

## Run the local HTTP burst tests

The burst script uses the same JWT secret as the running app to sign test tokens. In PowerShell, retrieve the secret from the local app container without printing it, then run a 2,000-request warm-up followed by the 20,000-request check:

```powershell
$env:RESERVATION_JWT_SECRET = (docker compose exec -T app sh -c 'printf "%s" "$RESERVATION_JWT_SECRET"').Trim()
if ($env:RESERVATION_JWT_SECRET.Length -lt 32) {
    throw "Could not read a valid signing secret from the app container."
}

$env:BURST_REQUESTS = "2000"
$env:BURST_WORKERS = "100"
python scripts/burst.py http://localhost:8080

$env:BURST_REQUESTS = "20000"
$env:BURST_WORKERS = "200"
python scripts/burst.py http://localhost:8080
```

Do not generate a different secret while the app is running: the script signs tokens with the PowerShell value, and the app verifies them with its configured value. Do not print, commit, or share the secret. If the containers are recreated with a new secret, retrieve the new value.

The script creates fresh shows and exercises:

- Concurrent requests for the same seat
- A concurrent 10-seat, same-user quota attempt
- Same-key retries
- Same-key requests with different bodies
- Final state reconciliation

It prints outcome distributions and exits non-zero if expectations fail. `BURST_REQUESTS` and `BURST_WORKERS` tune the hot-seat storm; defaults are 20,000 requests and 200 workers.

### Local PostgreSQL burst results

The local PostgreSQL runs completed successfully:

- The 2,000-request hot-seat run reported 1 confirmation and 1,999 seat-taken declines.
- The 20,000-request hot-seat run reported 1 confirmation and 19,999 seat-taken declines.
- Both reported valid reconciliation.
- The per-user limit scenario reported four confirmations and six declines.
- Same-key replay returned the original reservation; changing the body under the same key returned `409 Conflict`.
- Neither run reported a 5xx or transport-error bucket.

These results are from local Docker Compose, not from a public hosted deployment.

On macOS or Linux, after exporting the same signing secret into the shell, you can also run:

```bash
./burst.sh http://localhost:8080
```

Use the same procedure with the deployed base URL after deployment, handling the deployment’s configured secret securely.

## Validation report

The detailed sequential functional suite and hosted load-test results are documented in [TESTS_RESULT.md](TESTS_RESULT.md).

## AI use and system design

See [WRITEUP.md](WRITEUP.md) for details on atomicity, idempotency, cancellation, partition behavior, operational alerting, limitations, and AI-use disclosure.
