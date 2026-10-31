import {
  isCapacityLoadTestEnabled,
  isDisposableNativeTestDatabase,
  isPerformanceDiagnosticsEnabled,
  isPoolDiagnosticsEnabled
} from "../src/operationalModes";

const disposableCiEnv = {
  CI: "true",
  NATIVE_TEST_MODE: "true",
  NODE_ENV: "staging",
  DATABASE_URL: "postgresql://postgres:postgres@127.0.0.1:5432/ludomada_ci?schema=public"
};

describe("operational mode guards", () => {
  test("request diagnostics are disabled by default and require explicit opt-in", () => {
    expect(isPerformanceDiagnosticsEnabled({})).toBe(false);
    expect(isPerformanceDiagnosticsEnabled({ LUDOMADA_PERF_DIAGNOSTICS: "true" })).toBe(true);
    expect(isPerformanceDiagnosticsEnabled({ LUDOMADA_PERF_DIAGNOSTICS: "1" })).toBe(false);
  });

  test("pool logging is disabled by default and independently opt-in", () => {
    expect(isPoolDiagnosticsEnabled({})).toBe(false);
    expect(isPoolDiagnosticsEnabled({ LUDOMADA_PG_POOL_DIAGNOSTICS: "true" })).toBe(true);
  });

  test("native test mode accepts only the dedicated disposable CI database", () => {
    expect(isDisposableNativeTestDatabase(disposableCiEnv)).toBe(true);
    expect(isDisposableNativeTestDatabase({ ...disposableCiEnv, DATABASE_URL: "postgresql://u:p@db.example.com:5432/ludomada_ci" })).toBe(false);
    expect(isDisposableNativeTestDatabase({ ...disposableCiEnv, DATABASE_URL: "postgresql://u:p@127.0.0.1:5432/postgres" })).toBe(false);
    expect(isDisposableNativeTestDatabase({ ...disposableCiEnv, CI: "false" })).toBe(false);
    expect(isDisposableNativeTestDatabase({ ...disposableCiEnv, NODE_ENV: "production" })).toBe(false);
  });

  test("10k load mode requires explicit opt-in and the disposable CI database", () => {
    expect(isCapacityLoadTestEnabled(disposableCiEnv)).toBe(false);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, LUDOMADA_LOAD_TEST_MODE: "true" })).toBe(true);
    expect(isCapacityLoadTestEnabled({
      ...disposableCiEnv,
      LUDOMADA_LOAD_TEST_MODE: "true",
      DATABASE_URL: "postgresql://u:p@db.example.com:5432/ludomada_ci"
    })).toBe(false);
  });
});
