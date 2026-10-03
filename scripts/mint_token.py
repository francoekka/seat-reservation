#!/usr/bin/env python3
"""Create a short-lived HS256 JWT for local/manual API use."""

import base64
import hashlib
import hmac
import json
import os
import sys
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: mint_token.py <subject> [ADMIN]")

    raw_secret = os.environ.get("RESERVATION_JWT_SECRET")
    if raw_secret is None:
        raise SystemExit("set RESERVATION_JWT_SECRET to the service signing key")
    secret = raw_secret.encode("utf-8")
    if len(secret) < 32:
        raise SystemExit("RESERVATION_JWT_SECRET must be at least 32 bytes")

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {"sub": sys.argv[1], "iat": now, "exp": now + 600}
    if len(sys.argv) > 2 and sys.argv[2].upper() == "ADMIN":
        claims["roles"] = ["ADMIN"]

    unsigned = f"{b64url(json.dumps(header, separators=(',', ':')).encode())}.{b64url(json.dumps(claims, separators=(',', ':')).encode())}"
    signature = hmac.new(secret, unsigned.encode("ascii"), hashlib.sha256).digest()
    print(f"{unsigned}.{b64url(signature)}")


if __name__ == "__main__":
    main()
