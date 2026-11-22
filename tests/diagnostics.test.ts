import {
  isPerformanceDiagnosticsEnabled,
  isPoolDiagnosticsEnabled
} from "../src/diagnostics";

describe("API diagnostics feature flags", () => {
  test("request timing diagnostics are disabled by default", () => {
    expect(isPerformanceDiagnosticsEnabled({})).toBe(false);
    expect(isPerformanceDiagnosticsEnabled({
      LUDOMADA_PERF_DIAGNOSTICS: "false"
    })).toBe(false);
  });

  test("request timing diagnostics require the exact true value", () => {
    expect(isPerformanceDiagnosticsEnabled({
      LUDOMADA_PERF_DIAGNOSTICS: "true"
    })).toBe(true);
    expect(isPerformanceDiagnosticsEnabled({
      LUDOMADA_PERF_DIAGNOSTICS: "1"
    })).toBe(false);
    expect(isPerformanceDiagnosticsEnabled({
      LUDOMADA_PERF_DIAGNOSTICS: "TRUE"
    })).toBe(false);
  });

  test("pool diagnostics are independent and disabled by default", () => {
    expect(isPoolDiagnosticsEnabled({})).toBe(false);
    expect(isPoolDiagnosticsEnabled({
      LUDOMADA_PG_POOL_DIAGNOSTICS: "true"
    })).toBe(true);
    expect(isPoolDiagnosticsEnabled({
      LUDOMADA_PERF_DIAGNOSTICS: "true"
    })).toBe(false);
  });

  test("pool diagnostics also require the exact true value", () => {
    expect(isPoolDiagnosticsEnabled({
      LUDOMADA_PG_POOL_DIAGNOSTICS: "1"
    })).toBe(false);
    expect(isPoolDiagnosticsEnabled({
      LUDOMADA_PG_POOL_DIAGNOSTICS: "TRUE"
    })).toBe(false);
  });
});
