# LudoMada — 10,000 RPS capacity gate

## First measured benchmark: failed

Release Gate run `41233161474` on commit `9f025ce2b18ebd3c9acb507ab9febee05a1bee37` exercised authenticated `/api/games/:gameId/state` requests against the disposable PostgreSQL database. The raw Artillery artifact recorded:

- Configured arrival target: 10,000 requests/second.
- Aggregate observed throughput: 1,332 requests/second; highest 10-second interval: 1,984 requests/second.
- 436,584 requests / virtual users created; 234,569 HTTP 200 responses; 202,015 virtual users failed (46.27%).
- p95 2,725 ms; p99 11,050.8 ms.
- Client errors: `EADDRNOTAVAIL` 95,474; `ERR_SOCKET_TIMEOUT` 102,340; `ECONNRESET` 4,201.

This is a **failed 10k capacity result**, even though the GitHub job was green. The old load profile's legacy ensure configuration did not cause the CI job to fail on those unmet values. The new gate uses the current `config.plugins.ensure` plugin and an independent JSON validator; both must enforce the SLOs.

## Root cause found in the load generator

Artillery's HTTP engine creates a separate TCP agent per virtual user when `config.http.pool` is omitted. At hundreds of thousands of virtual users this opened/closed too many TCP sockets and exhausted local ephemeral ports, producing `EADDRNOTAVAIL`. Its engine source confirms that `config.http.pool` creates shared reusable HTTP agents; the default agent uses keep-alive.

The benchmark now configures a global shared socket pool of 4,096. The API PostgreSQL pool is raised to 60 **only** when the strict CI guard confirms the ephemeral loopback `ludomada_ci` database. All non-capacity environments remain capped at 20, with the normal default of five (Render's configured value remains two).

## Enforced acceptance criteria

The load profile uses the Artillery `ensure` plugin with strict conditions and a post-run JSON validator:

- Configured steady-state arrival target: 10,000 RPS; minimum measured aggregate rate across the warm-up plus steady-state run: 9,000 RPS. The aggregate floor is lower because the report includes the intentional 5-second ramp from 1,000 to 10,000 RPS.
- Failed virtual users plus non-200 responses at or below 0.1%.
- p95 at or below 250 ms.
- p99 at or below 500 ms.

The independent validator has six Node.js tests covering a valid report and deliberate failures for low throughput, high error rate, non-200 responses, high p95/p99, and missing metrics. The previous raw report was run through it locally and correctly failed all four performance criteria. The Artillery report is uploaded even if the load run or validator fails. The temporary JWT CSV is always deleted and never uploaded.

## Scope and limitations

The benchmark measures authenticated participant-authorized game-state **reads**, not 10k `roll`/`move` writes. API, PostgreSQL and Artillery share a GitHub-hosted runner in this CI profile, so this is a diagnostic benchmark, not proof of production multi-instance capacity. A future passing CI profile still needs separate load generators against the actual deployment topology.

Before claiming production 10k RPS, run mixed traffic against the real multi-instance topology; collect DB pool wait, connection count, CPU, memory, event-loop delay and lock data. Add shared SSE fanout/replay and distributed rate limiting before horizontally scaling the API.

Render Free remains a single-instance staging tier. The real Render/Supabase staging target remains unconfigured, so no production or real staging performance claim is made here.

**Status:** not certified until the 10k authenticated-read benchmark and separate write/mixed-traffic benchmarks pass on the intended deployment topology.