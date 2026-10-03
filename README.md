## Seat Reservation at Scale

This service uses PostgreSQL as the system of record. A reservation request is **all-or-nothing**: every requested seat is committed together or none is. Seat rows are locked using PostgreSQL `SELECT ... FOR UPDATE` in sorted seat-number order. A separate `(show_id,user_id)` lock row serializes a user's quota check across requests for different seats. H2 is used by fast integration tests; the production PostgreSQL schema and locking paths have also been validated locally with Docker Compose. The 20,000-request local burst is documented below; hosted deployment and hosted load validation remain separate steps.

### Clean checkout: build and run

- Java 25 and Maven 3.9+ are required for local builds. The container build includes Maven and does not require a Maven wrapper.
- `mvn clean verify`
- `docker compose up --build`
- API: `http://localhost:8080`; PostgreSQL: `localhost:5432`.
- If host port 5432 is already in use, set `POSTGRES_HOST_PORT=5433` before Compose startup; the app still connects to PostgreSQL on the internal port 5432.
- Set `POSTGRES_PASSWORD` and `RESERVATION_JWT_SECRET` in the environment before using Compose. JWT secrets must be at least 32 bytes. Direct non-Compose startup deliberately fails if the JWT secret is missing.

### Authentication

The API accepts short-lived HS256 bearer JWTs. The required `sub`, `iat`, and `exp` claims provide identity and expiry; show creation additionally requires `roles: ["ADMIN"]`. Deployments must inject a secret with `RESERVATION_JWT_SECRET` and issue tokens through a trusted identity provider. A helper for local testing is `python3 scripts/mint_token.py buyer-1`; append `ADMIN` for a local admin token.

### API examples

Create a show as admin:

```http
POST /shows
Authorization: Bearer <admin-token>
Content-Type: application/json

{"name":"friday-night","seats":["A1","A2","A12"],"price_paise":25000}
```

Reserve seats (identity is the JWT subject; `user_id` in JSON is ignored):

```http
POST /shows/<show-id>/reserve
Authorization: Bearer <user-token>
Idempotency-Key: order-unique-key
Content-Type: application/json

{"seats":["A12"]}
```

`GET /shows/<show-id>` returns each seat and `available`, `held`, `confirmed`, and `total_seats` counts. `POST /reservations/<reservation-id>/cancel` releases seats only for the owning JWT subject. Booking is immediate confirmation; no temporary holds are created. Cancellation is serialized on the reservation row and releases only seats still attached to that reservation.

`GET /shows` and `DELETE /shows/<show-id>` are compatibility endpoints retained from the starter project; show creation through the required `POST /shows` contract creates all seats atomically.

### Idempotency and consistency

Idempotency keys are hashed with `(show_id, authenticated user_id)` for a database-unique scope. The canonical payload hash includes the sorted seat list. A unique insert and `FOR UPDATE` serialize retries; completed same-payload retries return the stored original response, and a changed body in the same scope returns 409. PostgreSQL is the authority: requests fail closed when the database is unavailable; readiness includes the DB health contributor. The service does not claim availability through a database partition.

### Deploy to Render

The repository contains a `render.yaml` Blueprint for a Docker web service and PostgreSQL database. Push the repository to GitHub, create a Render Blueprint from it, provide a strong `RESERVATION_JWT_SECRET` (at least 32 bytes), and wait for `/actuator/health/readiness` to pass. **A public live URL is not checked in here; publish the URL in your submission only after the Render deployment is actually healthy.**

### Observe

- Liveness: `/actuator/health/liveness` (process-only).
- Readiness: `/actuator/health/readiness` (includes PostgreSQL DB health; returns non-UP if it cannot connect).
- Prometheus: `/actuator/prometheus`.
- Metrics: `reservations_confirmed_total`, `reservations_declined_total{reason="seat_taken|per_user_limit|idempotency_mismatch|idempotent_replay"}`, `reservations_idempotent_replays_total`, and `seats_available_gauge{show_id="..."}`.
- Logs are JSON and carry `x-correlation-id`; clients may supply it or the server generates one.
- Page on readiness down, sustained 5xx, database pool exhaustion, or a discrepancy between per-show total seats and the sum of status counts. A seat-taken increase by itself is expected on sale.

### Run the real HTTP burst

The script uses the same JWT secret as the running app to sign its test tokens. In PowerShell, retrieve it from the local app container without printing it, then run a 2,000-request warm-up followed by the 20,000-request check:

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

Do not generate a different secret while the app is running: the script signs tokens with the PowerShell value and the app verifies them with its configured value. Do not print, commit, or share the secret. If the containers are recreated with a new secret, retrieve the new value again.

The local PostgreSQL runs completed successfully. The 2,000-request hot-seat run reported 1 confirmation and 1,999 seat-taken declines; the 20,000-request run reported 1 confirmation and 19,999 seat-taken declines. Both reported valid reconciliation, four confirmations and six per-user-limit declines in the limit scenario, and a successful same-key replay followed by a 409 for a changed body. Neither run reported a 5xx/transport bucket. These results are from local Docker Compose, not from a public hosted deployment.

On macOS/Linux, after exporting the same signing secret into the shell, the wrapper can also be used:

```bash
./burst.sh http://localhost:8080
```

The script creates fresh shows and exercises the same hot seat with concurrent users, a concurrent 10-seat same-user quota attack, same-key retries, same-key/different-body rejection, and final reconciliation. It prints outcome distributions and exits non-zero if expectations fail. `BURST_REQUESTS` and `BURST_WORKERS` tune the hot-seat storm; defaults are 20,000 requests and 200 workers. Use the same procedure with the deployed base URL after deployment, using the deployment's configured secret securely.

## AI use and system design

See [WRITEUP.md](WRITEUP.md) for atomicity, idempotency, cancellation, partition behavior, operational alerting, limitations, and specific AI-use disclosure.