#!/usr/bin/env python3
"""Generate benchmark-only users and matching HS256 JWTs without external packages."""

import argparse
import base64
import hashlib
import hmac
import json
import time
from pathlib import Path

PHONE_CAPACITY = 100_000_000


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def token(secret: str, issuer: str, user_id: int, phone: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({
        "iss": issuer,
        "sub": str(user_id),
        "userId": user_id,
        "phone": phone,
        "role": "user",
        "iat": now,
        "exp": now + 7200,
    }, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = b64url(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--user-id-base", type=int, required=True)
    parser.add_argument("--secret", required=True)
    parser.add_argument("--issuer", default="ticket-rush")
    parser.add_argument("--sql", type=Path, required=True)
    parser.add_argument("--tokens", type=Path, required=True)
    args = parser.parse_args()

    if args.count < 1:
        raise SystemExit("--count must be positive")
    if args.count > PHONE_CAPACITY:
        raise SystemExit(f"--count must not exceed {PHONE_CAPACITY} unique benchmark phones")
    if len(args.secret.encode()) < 32:
        raise SystemExit("--secret must contain at least 32 bytes for HS256")

    rows = []
    tokens = []
    for offset in range(args.count):
        user_id = args.user_id_base + offset
        # Each run owns a separate MySQL database, so an offset-scoped 11-digit phone is unique.
        phone = f"139{offset:08d}"
        rows.append(f"({user_id},'{phone}','bench-{offset}','', 'user',0,NOW(),NOW())")
        tokens.append(token(args.secret, args.issuer, user_id, phone))

    sql = (
        "INSERT INTO tb_user "
        "(id,phone,nick_name,icon,role,deleted,create_time,update_time) VALUES\n"
        + ",\n".join(rows)
        + ";\n"
    )
    args.sql.write_text(sql, encoding="utf-8")
    args.tokens.write_text(json.dumps(tokens), encoding="utf-8")


if __name__ == "__main__":
    main()
