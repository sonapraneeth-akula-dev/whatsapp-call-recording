import copy
import math
import unittest

from check_performance import CLASS, ITERATIONS, evaluate


def report():
    return {
        "context": {"build": {"model": "Synthetic phone", "version": {"sdk": 33}}},
        "benchmarks": [
            {
                "className": CLASS, "name": "coldStartup", "repeatIterations": ITERATIONS,
                "metrics": {
                    "timeToInitialDisplayMs": {"runs": [500] * ITERATIONS},
                    "timeToFullDisplayMs": {"runs": [1_000] * ITERATIONS},
                },
            },
            {
                "className": CLASS, "name": "navigationAndScroll", "repeatIterations": ITERATIONS,
                "sampledMetrics": {"frameOverrunMs": {"runs": [[1] * 5 + [-1] * 95 for _ in range(ITERATIONS)]}},
            },
        ],
    }


class PerformanceReportTest(unittest.TestCase):
    def test_exact_limits_pass(self):
        result = evaluate(report(), "Synthetic phone")
        self.assertTrue(result["passed"])
        self.assertEqual(1_000, result["frames"])
        self.assertEqual(5, result["janky_frames_percent"])

    def test_one_slow_start_fails_even_when_median_is_fast(self):
        data = report()
        data["benchmarks"][0]["metrics"]["timeToFullDisplayMs"]["runs"][-1] = 1_000.01
        self.assertFalse(evaluate(data, "Synthetic phone")["passed"])

    def test_one_janky_run_fails_even_when_aggregate_passes(self):
        data = report()
        data["benchmarks"][1]["sampledMetrics"]["frameOverrunMs"]["runs"][-1] = [1] * 6 + [-1] * 94
        self.assertFalse(evaluate(data, "Synthetic phone")["passed"])

    def test_zero_overrun_is_not_jank(self):
        data = report()
        data["benchmarks"][1]["sampledMetrics"]["frameOverrunMs"]["runs"] = [[0] * 100 for _ in range(ITERATIONS)]
        self.assertEqual(0, evaluate(data, "Synthetic phone")["janky_frames_percent"])

    def test_invalid_and_incomplete_reports_fail_closed(self):
        invalid = []
        data = report()
        data["context"]["build"]["model"] = "Different phone"
        invalid.append(data)
        data = report()
        data["context"]["build"]["version"]["sdk"] = 32
        invalid.append(data)
        data = report()
        del data["benchmarks"][0]["metrics"]["timeToFullDisplayMs"]
        invalid.append(data)
        data = report()
        data["benchmarks"][0]["metrics"]["timeToFullDisplayMs"]["runs"][-1] = 100
        invalid.append(data)
        data = report()
        data["benchmarks"].append(copy.deepcopy(data["benchmarks"][0]))
        invalid.append(data)
        for value in (math.nan, math.inf, -1, True, "1000"):
            data = report()
            data["benchmarks"][0]["metrics"]["timeToFullDisplayMs"]["runs"][-1] = value
            invalid.append(data)
        for value in ([], None, [[0]] * 9, [[0]] * 9 + [[]], [[0]] * 9 + [[math.nan]]):
            data = report()
            data["benchmarks"][1]["sampledMetrics"]["frameOverrunMs"]["runs"] = value
            invalid.append(data)
        for value in (None, [], {}, {"context": None}):
            invalid.append(value)
        for data in invalid:
            with self.subTest(data=data), self.assertRaises(ValueError):
                evaluate(data, "Synthetic phone")


if __name__ == "__main__":
    unittest.main()
