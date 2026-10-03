#!/usr/bin/env python3
"""Concurrent hot-seat, per-user-limit, and idempotency smoke test."""

import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def token(subject: str, secret: bytes, roles: list[str] | None = None) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {"sub": subject, "iat": now, "exp": now + 600}
    if roles:
        claims["roles"] = roles
    unsigned = f"{b64url(json.dumps(header, separators=(',', ':')).encode())}.{b64url(json.dumps(claims, separators=(',', ':')).encode())}"
    signature = hmac.new(secret, unsigned.encode("ascii"), hashlib.sha256).digest()
    return f"{unsigned}.{b64url(signature)}"


def request(base: str, method: str, path: str, bearer: str,
            body: dict | None = None, idempotency_key: str | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Authorization": f"Bearer {bearer}", "Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            payload = {"message": raw.decode("utf-8", errors="replace")}
        return error.code, payload
    except Exception as error:  # Includes connection and timeout errors; count as transport failures.
        return 599, {"message": str(error)}


def create_show(base: str, admin_token: str, name: str, seats: list[str]) -> str:
    status, body = request(base, "POST", "/shows", admin_token,
                           {"name": name, "seats": seats, "price_paise": 25000})
    if status != 201:
        raise RuntimeError(f"show creation failed ({status}): {body}")
    return body["show_id"]


def print_distribution(label: str, results: list[tuple[int, dict]]) -> None:
    counts: dict[str, int] = {}
    for status, body in results:
        if status == 201:
            bucket = "confirmed"
        elif status == 409:
            message = str(body.get("message", "conflict")).lower()
            if "limit" in message:
                bucket = "declined-per-user-limit"
            elif "idempotency" in message:
                bucket = "declined-idempotency"
            else:
                bucket = "declined-seat-taken"
        elif status >= 500:
            bucket = "5xx-or-transport"
        else:
            bucket = f"http-{status}"
        counts[bucket] = counts.get(bucket, 0) + 1
    print(f"{label}: " + json.dumps(counts, sort_keys=True))


def check_reconciliation(base: str, show_id: str, admin_token: str) -> bool:
    status, state = request(base, "GET", f"/shows/{show_id}", admin_token)
    if status != 200:
        print(f"reconciliation GET failed ({status}): {state}")
        return False
    total = state["total_seats"]
    available = state["available"]
    held = state["held"]
    confirmed = state["confirmed"]
    valid = available + held + confirmed == total
    print(f"reconciliation: available={available} held={held} confirmed={confirmed} total={total} valid={valid}")
    return valid


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: ./burst.sh <BASE_URL>", file=sys.stderr)
        return 2
    base = sys.argv[1].rstrip("/")
    raw_secret = os.environ.get("RESERVATION_JWT_SECRET")
    if raw_secret is None:
        print("Set RESERVATION_JWT_SECRET to the service signing key", file=sys.stderr)
        return 2
    secret = raw_secret.encode("utf-8")
    if len(secret) < 32:
        print("RESERVATION_JWT_SECRET must be at least 32 bytes", file=sys.stderr)
        return 2

    requests_count = int(os.environ.get("BURST_REQUESTS", "20000"))
    workers = max(1, int(os.environ.get("BURST_WORKERS", "200")))
    admin_token = token("burst-admin", secret, ["ADMIN"])

    hot_show = create_show(base, admin_token, f"hot-seat-{uuid.uuid4()}", ["A12"])
    hot_tasks = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for index in range(requests_count):
            user_token = token(f"buyer-{index}", secret)
            hot_tasks.append(pool.submit(request, base, "POST", f"/shows/{hot_show}/reserve",
                                         user_token, {"seats": ["A12"]}, f"hot-{uuid.uuid4()}"))
        hot_results = [task.result() for task in hot_tasks]

    print_distribution("hot-seat storm", hot_results)
    hot_success = sum(status == 201 for status, _ in hot_results)
    hot_5xx = sum(status >= 500 for status, _ in hot_results)
    hot_valid = hot_success == 1 and hot_5xx == 0 and check_reconciliation(base, hot_show, admin_token)

    limit_show = create_show(base, admin_token, f"limit-{uuid.uuid4()}", [f"L{i}" for i in range(1, 11)])
    same_user = token("limit-user", secret)
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        limit_tasks = [pool.submit(request, base, "POST", f"/shows/{limit_show}/reserve", same_user,
                                   {"seats": [f"L{i}"]}, f"limit-{i}-{uuid.uuid4()}")
                       for i in range(1, 11)]
        limit_results = [task.result() for task in limit_tasks]
    print_distribution("per-user limit", limit_results)
    limit_success = sum(status == 201 for status, _ in limit_results)
    limit_5xx = sum(status >= 500 for status, _ in limit_results)
    limit_valid = limit_success <= 4 and limit_5xx == 0 and check_reconciliation(base, limit_show, admin_token)

    retry_show = create_show(base, admin_token, f"idempotency-{uuid.uuid4()}", ["R1", "R2"])
    retry_user = token("retry-user", secret)
    idem_key = f"retry-{uuid.uuid4()}"
    first = request(base, "POST", f"/shows/{retry_show}/reserve", retry_user, {"seats": ["R1"]}, idem_key)
    replay = request(base, "POST", f"/shows/{retry_show}/reserve", retry_user, {"seats": ["R1"]}, idem_key)
    mismatch = request(base, "POST", f"/shows/{retry_show}/reserve", retry_user, {"seats": ["R2"]}, idem_key)
    idem_valid = (first[0] == 201 and replay[0] == 201
                  and first[1].get("reservation_id") == replay[1].get("reservation_id")
                  and mismatch[0] == 409)
    print(f"idempotency: initial={first[0]} replay={replay[0]} same_reservation="
          f"{first[1].get('reservation_id') == replay[1].get('reservation_id')} different_body={mismatch[0]}")

    return 0 if hot_valid and limit_valid and idem_valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
