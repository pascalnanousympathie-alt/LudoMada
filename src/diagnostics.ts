type DiagnosticsEnvironment = {
  LUDOMADA_PERF_DIAGNOSTICS?: string;
  LUDOMADA_PG_POOL_DIAGNOSTICS?: string;
};

/** Enable detailed request timing only when explicitly requested. */
export function isPerformanceDiagnosticsEnabled(
  env: DiagnosticsEnvironment = process.env
): boolean {
  return env.LUDOMADA_PERF_DIAGNOSTICS === "true";
}

/** Enable per-second PostgreSQL pool snapshots only when explicitly requested. */
export function isPoolDiagnosticsEnabled(
  env: DiagnosticsEnvironment = process.env
): boolean {
  return env.LUDOMADA_PG_POOL_DIAGNOSTICS === "true";
}
