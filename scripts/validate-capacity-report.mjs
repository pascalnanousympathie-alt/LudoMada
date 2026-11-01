import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const CAPACITY_LIMITS = Object.freeze({
  minimumRps: 10_000,
  maximumErrorRatePercent: 0.1,
  maximumP95Ms: 250,
  maximumP99Ms: 500
});

export function evaluateCapacityReport(report, limits = CAPACITY_LIMITS) {
  const aggregate = report?.aggregate;
  const counters = aggregate?.counters;
  const rates = aggregate?.rates;
  const summaries = aggregate?.summaries;
  if (!counters || !rates || !summaries) {
    return {
      passed: false,
      failures: ["Artillery aggregate counters/rates/summaries are missing"],
      observed: {}
    };
  }

  const created = Number(counters["vusers.created"]);
  const failedVusers = Number(counters["vusers.failed"] ?? 0);
  const responses = Number(counters["http.responses"] ?? 0);
  const successfulResponses = Number(counters["http.codes.200"] ?? 0);
  const requestRate = Number(rates["http.request_rate"]);
  const p95 = Number(summaries["http.response_time"]?.p95);
  const p99 = Number(summaries["http.response_time"]?.p99);

  const non200Responses = Math.max(0, responses - successfulResponses);
  const errorRatePercent = created > 0
    ? ((failedVusers + non200Responses) / created) * 100
    : Number.POSITIVE_INFINITY;

  const observed = {
    requestRate,
    created,
    failedVusers,
    responses,
    successfulResponses,
    non200Responses,
    errorRatePercent,
    p95,
    p99
  };

  const failures = [];
  if (!Number.isFinite(requestRate) || requestRate < limits.minimumRps) {
    failures.push(`Throughput ${requestRate} RPS is below ${limits.minimumRps} RPS`);
  }
  if (!Number.isFinite(errorRatePercent) || errorRatePercent > limits.maximumErrorRatePercent) {
    failures.push(`Error rate ${errorRatePercent.toFixed(4)}% exceeds ${limits.maximumErrorRatePercent}%`);
  }
  if (!Number.isFinite(p95) || p95 > limits.maximumP95Ms) {
    failures.push(`p95 ${p95} ms exceeds ${limits.maximumP95Ms} ms`);
  }
  if (!Number.isFinite(p99) || p99 > limits.maximumP99Ms) {
    failures.push(`p99 ${p99} ms exceeds ${limits.maximumP99Ms} ms`);
  }
  if (created <= 0 || responses <= 0) {
    failures.push("No completed HTTP load-test samples were recorded");
  }

  return { passed: failures.length === 0, failures, observed, limits };
}

function main() {
  const reportPath = process.argv[2];
  if (!reportPath) {
    throw new Error("Usage: node scripts/validate-capacity-report.mjs <artillery-report.json>");
  }

  const report = JSON.parse(readFileSync(resolve(reportPath), "utf8"));
  const result = evaluateCapacityReport(report);
  console.log("CAPACITY_REPORT_VALIDATION", JSON.stringify(result, null, 2));

  if (!result.passed) {
    console.error("CAPACITY_GATE_FAILED");
    process.exitCode = 1;
    return;
  }

  console.log("CAPACITY_GATE_PASS");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
