# Seat Reservation Validation and Load Test Results

## Overview

This document describes a sequential functional validation suite and a high-concurrency load test for the live Render environment:

- **Target URL:** https://seat-reservation-1wl1.onrender.com
- **Functional suite:** Token minting, show creation, seat reservation, idempotency, conflict handling, and state reconciliation
- **Load test:** 2,000 requests with 50 worker threads

## Prerequisites

Run these commands in PowerShell from the project directory:

```powershell
$env:RESERVATION_JWT_SECRET = "<your-reservation-jwt-secret>"

$ADMIN_TOKEN = (python scripts/mint_token.py admin-1 ADMIN).Trim()
$USER_TOKEN  = (python scripts/mint_token.py user-1).Trim()
```

## Sequential Functional Validation Suite

### Step 1: Create a show

Create a show with three seats priced at 25,000 paise (₹250) each.

```powershell
$response = curl.exe -s -X POST "https://seat-reservation-1wl1.onrender.com/shows" `
  -H "Authorization: Bearer $ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"name\":\"render-test-show\",\"seats\":[\"A1\",\"A2\",\"A3\"],\"price_paise\":25000}"

$response
```

**Expected:** HTTP `201 Created`, with a new `show_id` and all three seats available.

Example response:

```json
{
  "show_id": "8c32c00d-5663-4852-b19c-08745376813d",
  "name": "render-test-show",
  "total_seats": 3,
  "available": 3,
  "held": 0,
  "confirmed": 0,
  "seats": [
    { "seat_number": "A1", "price_paise": 25000, "status": "available" },
    { "seat_number": "A2", "price_paise": 25000, "status": "available" },
    { "seat_number": "A3", "price_paise": 25000, "status": "available" }
  ]
}
```

Use the `show_id` returned by the create request in the commands below.

```powershell
$SHOW_ID = "<show_id-from-create-response>"
```

### Step 2: Verify the initial show state

```powershell
curl.exe -s -X GET "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID"
```

**Expected:** HTTP `200 OK`; the show has three available seats and no confirmed seats.

### Step 3: Reserve seat A1

```powershell
$res = curl.exe -s -X POST "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID/reserve" `
  -H "Authorization: Bearer $USER_TOKEN" `
  -H "Idempotency-Key: render-test-key-001" `
  -H "Content-Type: application/json" `
  -d "{\"seats\":[\"A1\"]}"

$res
```

**Expected:** HTTP `200 OK` or `201 Created`, with a confirmed reservation for A1 and an amount of 25,000 paise.

Example response:

```json
{
  "show_id": "8c32c00d-5663-4852-b19c-08745376813d",
  "reservation_id": "ca737176-76e9-49f3-91a8-7dbf716498ee",
  "status": "confirmed",
  "seats": ["A1"],
  "user_id": "user-1",
  "amount_paise": 25000
}
```

### Step 4: Verify idempotent replay

Repeat the same request with the same idempotency key and payload:

```powershell
$idemRes = curl.exe -s -X POST "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID/reserve" `
  -H "Authorization: Bearer $USER_TOKEN" `
  -H "Idempotency-Key: render-test-key-001" `
  -H "Content-Type: application/json" `
  -d "{\"seats\":[\"A1\"]}"

$idemRes
```

**Expected:** HTTP `200 OK` with the same reservation object and `reservation_id` as Step 3. No duplicate reservation or charge should be created.

### Step 5: Verify occupied-seat conflict handling

Attempt to reserve A1 again using a new idempotency key:

```powershell
$conflictRes = curl.exe -s -X POST "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID/reserve" `
  -H "Authorization: Bearer $USER_TOKEN" `
  -H "Idempotency-Key: render-test-key-002" `
  -H "Content-Type: application/json" `
  -d "{\"seats\":[\"A1\"]}"

$conflictRes
```

**Expected:** HTTP `409 Conflict`.

Example response:

```json
{
  "timestamp": "...",
  "status": 409,
  "error": "Conflict",
  "path": "/shows/<show_id>/reserve"
}
```

### Step 6: Reconcile the mid-test state

```powershell
curl.exe -s -X GET "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID"
```

**Expected:** A1 is confirmed; A2 and A3 remain available. The show has two available seats and one confirmed seat.

### Step 7: Reserve the remaining seats

Reserve A2 and A3 in a single request:

```powershell
$resRemaining = curl.exe -s -X POST "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID/reserve" `
  -H "Authorization: Bearer $USER_TOKEN" `
  -H "Idempotency-Key: render-test-key-003" `
  -H "Content-Type: application/json" `
  -d "{\"seats\":[\"A2\",\"A3\"]}"

$resRemaining
```

**Expected:** HTTP `200 OK` or `201 Created`, confirming both seats with a total amount of 50,000 paise.

Example response:

```json
{
  "show_id": "8c32c00d-5663-4852-b19c-08745376813d",
  "reservation_id": "7ab64b76-9560-438f-b196-5355aabfb8b5",
  "status": "confirmed",
  "seats": ["A2", "A3"],
  "user_id": "user-1",
  "amount_paise": 50000
}
```

### Step 8: Verify the final show state

```powershell
curl.exe -s -X GET "https://seat-reservation-1wl1.onrender.com/shows/$SHOW_ID"
```

**Expected:** All three seats are confirmed; `available` is `0`, `confirmed` is `3`, and `total_seats` is `3`.

### Functional suite summary

| Step | Endpoint | Idempotency key | Expected status | Validation |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `POST /shows` | N/A | `201 Created` | Admin authorization and show creation |
| 2 | `GET /shows/{id}` | N/A | `200 OK` | Initial state: 3 available |
| 3 | `POST /shows/{id}/reserve` | `render-test-key-001` | `200 OK` or `201 Created` | Reserve A1 |
| 4 | `POST /shows/{id}/reserve` | `render-test-key-001` | `200 OK` | Replay returns the original reservation |
| 5 | `POST /shows/{id}/reserve` | `render-test-key-002` | `409 Conflict` | Reject reservation of an occupied seat |
| 6 | `GET /shows/{id}` | N/A | `200 OK` | Mid-test state: 2 available, 1 confirmed |
| 7 | `POST /shows/{id}/reserve` | `render-test-key-003` | `200 OK` or `201 Created` | Atomically reserve A2 and A3 |
| 8 | `GET /shows/{id}` | N/A | `200 OK` | Final state: 0 available, 3 confirmed |

## Live Load and Concurrency Test

### Test configuration

- **Target URL:** https://seat-reservation-1wl1.onrender.com
- **Requests:** 2,000
- **Worker threads:** 50

### Run the burst test

```powershell
$env:BURST_REQUESTS = "2000"
$env:BURST_WORKERS = "50"

python scripts/burst.py https://seat-reservation-1wl1.onrender.com
```

Check the output for `valid=True` in reconciliation lines to verify the reported state invariants.

### Recorded output

```text
hot-seat storm: {"confirmed": 1, "declined-seat-taken": 1999}
reconciliation: available=0 held=0 confirmed=1 total=1 valid=True

per-user limit: {"confirmed": 4, "declined-per-user-limit": 6}
reconciliation: available=6 held=0 confirmed=4 total=10 valid=True

idempotency: initial=201 replay=201 same_reservation=True different_body=409
```

### Results

| Test | Requests | Confirmed | Blocked or declined | State audit | Result |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Hot-seat storm | 2,000 | 1 | 1,999 (`declined-seat-taken`) | `available=0`, `held=0`, `confirmed=1`, `total=1` | Passed (`valid=True`) |
| Per-user limit | 10 | 4 | 6 (`declined-per-user-limit`) | `available=6`, `held=0`, `confirmed=4`, `total=10` | Passed (`valid=True`) |
| Idempotency replay | 1 initial request plus replay | 1 | Mutated payload returned `409` | `same_reservation=True` | Passed |

### Findings

- **Concurrent seat protection:** Of 2,000 concurrent requests for the same seat, one succeeded and 1,999 were declined because the seat was already taken.
- **Per-user limit:** Four reservations were confirmed and six were declined by the per-user limit.
- **Idempotency:** Replaying the same request returned the original reservation. Reusing the key with a different payload returned `409 Conflict`.
- **Reconciliation:** The reported state audits were marked `valid=True`.

### Hosted Deployment Observation

The reservation engine was validated under high concurrency against the local
PostgreSQL deployment, including a 20,000-request burst. The same service was
deployed to Render and the correctness scenarios were reproduced successfully
at smaller hosted burst sizes. Under hosted load, elevated PostgreSQL
health-check latency was observed, consistent with infrastructure/database
capacity limitations in the free-tier environment.
