type Environment = Record<string, string | undefined>;

export function isPerformanceDiagnosticsEnabled(env: Environment = process.env): boolean {
  return env.LUDOMADA_PERF_DIAGNOSTICS === "true";
}

export function isPoolDiagnosticsEnabled(env: Environment = process.env): boolean {
  return env.LUDOMADA_PG_POOL_DIAGNOSTICS === "true";
}

/**
 * The capacity harness is restricted to GitHub Actions' disposable loopback
 * database. It cannot switch off request limits for staging or production.
 */
export function isDisposableNativeTestDatabase(env: Environment = process.env): boolean {
  if (
    env.CI !== "true" ||
    env.NATIVE_TEST_MODE !== "true" ||
    env.NODE_ENV !== "staging"
  ) return false;

  try {
    const database = new URL(env.DATABASE_URL ?? "");
    return (
      ["127.0.0.1", "localhost"].includes(database.hostname) &&
      database.port === "5432" &&
      database.pathname === "/ludomada_ci"
    );
  } catch {
    return false;
  }
}

export function isCapacityLoadTestEnabled(env: Environment = process.env): boolean {
  return (
    env.LUDOMADA_LOAD_TEST_MODE === "true" &&
    isDisposableNativeTestDatabase(env)
  );
}
