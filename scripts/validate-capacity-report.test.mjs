import test from "node:test";
import assert from "node:assert/strict";
import { evaluateCapacityReport } from "./validate-capacity-report.mjs";

function passingReport(overrides = {}) {
  return {
    aggregate: {
      counters: {
        "vusers.created": 1_000_000,
        "vusers.failed": 10,
        "http.responses": 999_990,
        "http.codes.200": 999_990
      },
      rates: { "http.request_rate": 10_000 },
      summaries: {
        "http.response_time": { p95: 120, p99: 300 }
      },
      ...overrides
    }
  };
}

test("accepts a capacity report only when throughput, error rate and latency pass", () => {
  const result = evaluateCapacityReport(passingReport());
  assert.equal(result.passed, true);
  assert.deepEqual(result.failures, []);
});

test("accepts the minimum aggregate rate and rejects anything below 9k", () => {
  const report = passingReport();
  report.aggregate.rates["http.request_rate"] = 9_000;
  const boundary = evaluateCapacityReport(report);
  assert.equal(boundary.passed, true);
  report.aggregate.rates["http.request_rate"] = 8_999;
  const result = evaluateCapacityReport(report);
  assert.equal(result.passed, false);
  assert.match(result.failures.join(" "), /Throughput 8999 RPS is below 9000 RPS/);
});

test("rejects a report when virtual users fail above the 0.1 percent ceiling", () => {
  const report = passingReport();
  report.aggregate.counters["vusers.failed"] = 2_000;
  const result = evaluateCapacityReport(report);
  assert.equal(result.passed, false);
  assert.match(result.failures.join(" "), /Error rate .* exceeds 0.1%/);
});

test("rejects non-200 responses even when virtual users completed", () => {
  const report = passingReport();
  report.aggregate.counters["http.codes.200"] = 998_000;
  const result = evaluateCapacityReport(report);
  assert.equal(result.passed, false);
  assert.ok(result.observed.non200Responses > 0);
});

test("rejects p95 and p99 latency above their SLOs", () => {
  const report = passingReport();
  report.aggregate.summaries["http.response_time"] = { p95: 251, p99: 501 };
  const result = evaluateCapacityReport(report);
  assert.equal(result.passed, false);
  assert.match(result.failures.join(" "), /p95 251 ms exceeds 250 ms/);
  assert.match(result.failures.join(" "), /p99 501 ms exceeds 500 ms/);
});

test("rejects reports with missing aggregate metrics", () => {
  const result = evaluateCapacityReport({});
  assert.equal(result.passed, false);
  assert.match(result.failures.join(" "), /aggregate counters\/rates\/summaries are missing/);
});
