type Environment = Record<string, string | undefined>;

export function isPerformanceDiagnosticsEnabled(env: Environment = process.env): boolean {
  return env.LUDOMADA_PERF_DIAGNOSTICS === "true";
}

export function isPoolDiagnosticsEnabled(env: Environment = process.env): boolean {
  return env.LUDOMADA_PG_POOL_DIAGNOSTICS === "true";
}

/**
 * The high-volume mode is permitted only in a GitHub Actions job connected to
 * its disposable local PostgreSQL service. It cannot disable API limits on
 * Render, Supabase, production, or any network-reachable database.
 */
export function isCapacityLoadTestEnabled(env: Environment = process.env): boolean {
  if (
    env.LUDOMADA_LOAD_TEST_MODE !== "true" ||
    env.CI !== "true" ||
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
