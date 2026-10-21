"""Validate raw AndroidX Macrobenchmark samples, never percentile proxies."""

import argparse
import json
import math
import sys
from pathlib import Path

CLASS = "com.soakula.wacr.benchmark.PerformanceBenchmark"
ITERATIONS = 10


def object_value(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"Missing/invalid {label}")
    return value


def samples(value, label, *, nonnegative=False):
    if not isinstance(value, list) or not value:
        raise ValueError(f"Missing/empty {label}")
    if any(type(item) not in (int, float) or not math.isfinite(item) for item in value):
        raise ValueError(f"Non-finite/non-numeric {label}")
    if nonnegative and min(value) < 0:
        raise ValueError(f"Negative {label}")
    return value


def evaluate(report, expected_model):
    report = object_value(report, "report")
    context = object_value(report.get("context"), "context")
    build = object_value(context.get("build"), "device build")
    if not expected_model or build.get("model") != expected_model:
        raise ValueError("Device model does not match --expected-model")
    version = object_value(build.get("version"), "device version")
    if type(version.get("sdk")) is not int or version["sdk"] < 33:
        raise ValueError("API 33+ phone required")
    benchmarks = report.get("benchmarks")
    if not isinstance(benchmarks, list):
        raise ValueError("Missing benchmarks")

    def test(name):
        matches = [
            row for row in benchmarks
            if isinstance(row, dict) and row.get("className") == CLASS and row.get("name") == name
        ]
        if len(matches) != 1 or matches[0].get("repeatIterations") != ITERATIONS:
            raise ValueError(f"Expected one complete {ITERATIONS}-iteration {name} result")
        return matches[0]

    def timings(name):
        metrics = object_value(test("coldStartup").get("metrics"), "startup metrics")
        metric = object_value(metrics.get(name), name)
        runs = samples(metric.get("runs"), name, nonnegative=True)
        if len(runs) != ITERATIONS:
            raise ValueError(f"Expected {ITERATIONS} {name} samples")
        return runs

    initial = timings("timeToInitialDisplayMs")
    full = timings("timeToFullDisplayMs")
    if any(complete < first for complete, first in zip(full, initial)):
        raise ValueError("Full display cannot precede initial display")
    metrics = object_value(test("navigationAndScroll").get("sampledMetrics"), "frame metrics")
    frames = object_value(metrics.get("frameOverrunMs"), "frameOverrunMs").get("runs")
    if not isinstance(frames, list) or len(frames) != ITERATIONS:
        raise ValueError(f"Expected {ITERATIONS} raw frame runs; percentiles are insufficient")
    frames = [samples(run, "frameOverrunMs run") for run in frames]
    jank = [100 * sum(value > 0 for value in run) / len(run) for run in frames]
    count = sum(map(len, frames))
    return {
        "model": expected_model,
        "sdk": version["sdk"],
        "cold_start_max_ms": max(full),
        "frames": count,
        "janky_frames_percent": 100 * sum(value > 0 for run in frames for value in run) / count,
        "worst_run_janky_frames_percent": max(jank),
        "passed": max(full) <= 1_000 and max(jank) <= 5,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--expected-model", required=True, help="Actual F42 Build.MODEL; rejects another phone's report")
    args = parser.parse_args()
    try:
        result = evaluate(json.loads(args.report.read_text(encoding="utf-8")), args.expected_model)
    except (OSError, ValueError) as error:
        print(f"INCOMPLETE: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, allow_nan=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
