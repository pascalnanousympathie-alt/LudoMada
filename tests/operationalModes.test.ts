import {
  isCapacityLoadTestEnabled,
  isGameStateReadCacheEnabled,
  isPerformanceDiagnosticsEnabled,
  isPoolDiagnosticsEnabled,
  maximumPgPoolSize
} from "../src/operationalModes";

const disposableCiEnv = {
  CI: "true",
  NODE_ENV: "staging",
  LUDOMADA_LOAD_TEST_MODE: "true",
  DATABASE_URL: "postgresql://postgres:postgres@127.0.0.1:5432/ludomada_ci?schema=public"
};

describe("operational mode guards", () => {
  test("request timing diagnostics are disabled unless explicitly enabled", () => {
    expect(isPerformanceDiagnosticsEnabled({})).toBe(false);
    expect(isPerformanceDiagnosticsEnabled({ LUDOMADA_PERF_DIAGNOSTICS: "true" })).toBe(true);
    expect(isPerformanceDiagnosticsEnabled({ LUDOMADA_PERF_DIAGNOSTICS: "1" })).toBe(false);
  });

  test("database pool logging is independently opt-in", () => {
    expect(isPoolDiagnosticsEnabled({})).toBe(false);
    expect(isPoolDiagnosticsEnabled({ LUDOMADA_PG_POOL_DIAGNOSTICS: "true" })).toBe(true);
    expect(isPoolDiagnosticsEnabled({ LUDOMADA_PG_POOL_DIAGNOSTICS: "1" })).toBe(false);
  });

  test("capacity mode accepts only the disposable local GitHub Actions database", () => {
    expect(isCapacityLoadTestEnabled(disposableCiEnv)).toBe(true);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, CI: "false" })).toBe(false);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, NODE_ENV: "production" })).toBe(false);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, DATABASE_URL: "postgresql://u:p@db.example.com:5432/ludomada_ci" })).toBe(false);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, DATABASE_URL: "postgresql://u:p@127.0.0.1:5432/postgres" })).toBe(false);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, DATABASE_URL: "postgresql://u:p@127.0.0.1:6543/ludomada_ci" })).toBe(false);
    expect(isCapacityLoadTestEnabled({ ...disposableCiEnv, LUDOMADA_LOAD_TEST_MODE: "false" })).toBe(false);
    expect(isCapacityLoadTestEnabled({ DATABASE_URL: "not-a-url" })).toBe(false);
  });
});

test("only the isolated CI capacity profile gets the larger PostgreSQL pool ceiling", () => {
  const normalCiEnv = { ...disposableCiEnv, LUDOMADA_LOAD_TEST_MODE: "false" };
  expect(maximumPgPoolSize(normalCiEnv)).toBe(20);
  const capacityEnv = { ...disposableCiEnv, LUDOMADA_LOAD_TEST_MODE: "true" };
  expect(maximumPgPoolSize(capacityEnv)).toBe(60);
  expect(maximumPgPoolSize({ ...capacityEnv, DATABASE_URL: "postgresql://u:p@db.example.com:5432/ludomada_ci" })).toBe(20);
  expect(maximumPgPoolSize({ ...capacityEnv, NODE_ENV: "production" })).toBe(20);
});

test("process-local game-state cache is restricted to disposable CI capacity mode", () => {
  expect(isGameStateReadCacheEnabled({})).toBe(false);
  expect(isGameStateReadCacheEnabled({ LUDOMADA_GAME_STATE_CACHE: "true" })).toBe(false);
  expect(isGameStateReadCacheEnabled({ ...disposableCiEnv, LUDOMADA_GAME_STATE_CACHE: "false" })).toBe(false);
  expect(isGameStateReadCacheEnabled({ ...disposableCiEnv, LUDOMADA_GAME_STATE_CACHE: "true" })).toBe(true);
  expect(isGameStateReadCacheEnabled({ ...disposableCiEnv, LUDOMADA_GAME_STATE_CACHE: "true", CI: "false" })).toBe(false);
  expect(isGameStateReadCacheEnabled({ ...disposableCiEnv, LUDOMADA_GAME_STATE_CACHE: "true", NODE_ENV: "production" })).toBe(false);
  expect(isGameStateReadCacheEnabled({ ...disposableCiEnv, LUDOMADA_GAME_STATE_CACHE: "true", DATABASE_URL: "postgresql://u:p@db.example.com:5432/ludomada_ci" })).toBe(false);
});
