# LudoMada — 10,000 RPS capacity gate

## First measured benchmark: failed

The first authenticated game-state benchmark (Release Gate run 351, commit `9f025ce2b18ebd3c9acb507ab9febee05a1bee37`) actually exercised the API and PostgreSQL. The Artillery artifact recorded:

- Configured arrival target: 10,000 requests/second.
- Observed aggregate throughput: 1,332 requests/second.
- 436,584 requests created; 234,569 HTTP 200 responses; 202,015 failed virtual users (46.3%).
- p95 2,725 ms; p99 11,050.8 ms.
- Errors: EADDRNOTAVAIL 95,474; ERR_SOCKET_TIMEOUT 102,340; ECONNRESET 4,201.

This is a **failed 10k capacity result**, even though the GitHub job was green. The previous Artillery test did not independently validate the JSON report, so CI status alone was not a reliable capacity gate.

## Root cause found in the load generator

Artillery's HTTP engine creates a separate TCP agent per virtual user when `config.http.pool` is omitted. At hundreds of thousands of virtual users this opened/closed too many TCP sockets and exhausted local ephemeral ports, producing EADDRNOTAVAIL. Its engine source confirms that `config.http.pool` creates shared reusable HTTP agents; the default agent uses keep-alive.

The benchmark now configures a global shared socket pool of 4,096. The API PostgreSQL pool is raised to 60 **only** when the strict CI guard confirms the ephemeral loopback `ludomada_ci` database. All non-capacity environments remain capped at 20, with the normal default of five (Render's configured value remains two).

## Enforced acceptance criteria

The load profile now uses Artillery's `ensure` plugin with strict conditions and an independent post-run JSON validator:

- Actual aggregate request rate at least 10,000 RPS.
- Failed virtual users plus non-200 responses at or below 0.1%.
- p95 at or below 250 ms.
- p99 at or below 500 ms.

The independent validator is covered by pass and deliberate-failure tests. The Artillery report is uploaded even if the load run or validator fails. The temporary JWT CSV is always deleted and never uploaded.

## Scope and limitations

The benchmark still measures authenticated participant-authorized game-state **reads**, not 10k `roll`/`move` writes. Artillery, API and PostgreSQL share a GitHub-hosted runner in this CI profile, so this is a diagnostic benchmark, not proof of production multi-instance capacity.

Before claiming production 10k RPS, run mixed traffic from separate load generators against the actual multi-instance topology; collect DB pool wait, connection count, CPU, memory, event-loop delay and lock data. Add shared SSE fanout/replay and distributed rate limiting before horizontally scaling the API.

Render Free remains a single-instance staging tier. The real Render/Supabase staging target remains unconfigured, so no production or real staging performance claim is made here.

**Status:** not certified until the 10k authenticated-read benchmark and separate write/mixed-traffic benchmarks pass on the intended deployment topology.