#!/usr/bin/env python3
"""Convert the small stable subset of wrk output used by this project into JSON."""

import json
import re
import sys
from pathlib import Path


text = Path(sys.argv[1]).read_text(encoding="utf-8")


def match(pattern: str):
    found = re.search(pattern, text, re.MULTILINE)
    return found.group(1) if found else None


def required(pattern: str, label: str) -> str:
    value = match(pattern)
    if value is None:
        raise SystemExit(f"wrk output is missing {label}")
    return value


non_2xx = int(match(r"Non-2xx or 3xx responses:\s+([0-9]+)") or 0)
socket_match = re.search(
    r"Socket errors:\s+connect\s+(\d+),\s+read\s+(\d+),\s+write\s+(\d+),\s+timeout\s+(\d+)",
    text,
)
socket_errors = sum(int(value) for value in socket_match.groups()) if socket_match else 0

report = {
    "requestsPerSecond": float(required(r"Requests/sec:\s+([0-9.]+)", "request rate")),
    "p50": match(r"^\s*50%\s+([^\s]+)"),
    "p75": match(r"^\s*75%\s+([^\s]+)"),
    "p90": match(r"^\s*90%\s+([^\s]+)"),
    "p99": match(r"^\s*99%\s+([^\s]+)"),
    "totalRequests": int(required(r"\s([0-9]+) requests in", "request count")),
    "non2xxOr3xxResponses": non_2xx,
    "socketErrors": socket_errors,
}
print(json.dumps(report, indent=2))

if non_2xx != 0 or socket_errors != 0:
    raise SystemExit(
        f"wrk correctness gate failed: non-2xx/3xx={non_2xx}, socket-errors={socket_errors}"
    )
