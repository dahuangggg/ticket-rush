#!/usr/bin/env python3
"""Aggregate repeated k6 cache comparisons without selecting a best-looking run."""

import json
import statistics
import sys
from pathlib import Path


def describe(values: list[float]) -> dict[str, float]:
    mean = statistics.fmean(values)
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0.0
    return {
        "median": statistics.median(values),
        "min": min(values),
        "max": max(values),
        "mean": mean,
        "standardDeviation": standard_deviation,
        "coefficientOfVariationPercent": standard_deviation / mean * 100 if mean else 0.0,
    }


def main(arguments: list[str]) -> int:
    samples: dict[str, list[dict]] = {"mysql": [], "redis": [], "caffeine": []}
    for argument in arguments:
        try:
            variant, filename = argument.split("=", 1)
        except ValueError as error:
            raise SystemExit(f"expected variant=summary.json, got: {argument}") from error
        if variant not in samples:
            raise SystemExit(f"unknown cache variant: {variant}")
        with Path(filename).open(encoding="utf-8") as source:
            samples[variant].append(json.load(source))

    round_counts = {len(group) for group in samples.values()}
    if len(round_counts) != 1 or not round_counts or 0 in round_counts:
        raise SystemExit("mysql, redis, and caffeine must have the same non-zero sample count")

    report = {"rounds": round_counts.pop(), "variants": {}}
    for variant, group in samples.items():
        failed = sum(int(sample["failedResponses"]) for sample in group)
        report["variants"][variant] = {
            "qps": describe([float(sample["requestsPerSecond"]) for sample in group]),
            "p99Ms": describe([float(sample["p99Ms"]) for sample in group]),
            "averageMs": describe([float(sample["averageMs"]) for sample in group]),
            "totalRequests": sum(int(sample["requests"]) for sample in group),
            "totalFailedResponses": failed,
        }
        if failed:
            raise SystemExit(f"{variant} has {failed} failed responses")

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
