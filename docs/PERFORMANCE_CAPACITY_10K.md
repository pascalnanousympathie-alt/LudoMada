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

The benchmark now configures a global shared socket pool of 4,096 and holds the 10,000 RPS arrival rate through both its warm-up and steady-state phases, so the aggregate validator can enforce the full target without a ramp-induced averaging penalty. The API PostgreSQL pool is raised to 60 **only** when the strict CI guard confirms the ephemeral loopback `ludomada_ci` database. All non-capacity environments remain capped at 20, with the normal default of five (Render's configured value remains two).

## Enforced acceptance criteria

The load profile uses the Artillery `ensure` plugin with strict conditions and a post-run JSON validator:

- Both the 5-second warm-up and 30-second steady phase now run at 10,000 RPS, so the minimum measured aggregate rate for the complete profile is 10,000 RPS.
- Failed virtual users plus non-200 responses at or below 0.1%.
- p95 at or below 250 ms.
- p99 at or below 500 ms.

The independent validator has six Node.js tests covering a valid report and deliberate failures for low throughput, high error rate, non-200 responses, high p95/p99, and missing metrics. A synthetic fixture constructed from the previous run's observed aggregates was rejected by the validator; the next CI run will independently validate its raw Artillery JSON. The Artillery report is uploaded even if the load run or validator fails. The temporary JWT CSV is always deleted and never uploaded.

## Scope and limitations

The benchmark measures authenticated participant-authorized game-state **reads**, not 10k `roll`/`move` writes. API, PostgreSQL and Artillery share a GitHub-hosted runner in this CI profile, so this is a diagnostic benchmark, not proof of production multi-instance capacity. A future passing CI profile still needs separate load generators against the actual deployment topology.

Before claiming production 10k RPS, run mixed traffic against the real multi-instance topology; collect DB pool wait, connection count, CPU, memory, event-loop delay and lock data. Add shared SSE fanout/replay and distributed rate limiting before horizontally scaling the API.

Render Free remains a single-instance staging tier. The real Render/Supabase staging target remains unconfigured, so no production or real staging performance claim is made here.

**Status:** not certified until the 10k authenticated-read benchmark and separate write/mixed-traffic benchmarks pass on the intended deployment topology.
## Second measured benchmark — 2026-11-01 (failed)

Release Gate run `41271085933` on head `627ac34988097d088cf7d7df55db5ce3a81bf1ff` used `http.pool: 4096` and held arrivals at 10,000 RPS during warm-up and steady-state. It still did not meet the SLOs:

- 326,943 virtual users created; 132,621 HTTP 200 responses; 132,720 total HTTP responses (99 HTTP 500); 197,422 virtual users failed.
- Aggregate throughput: 1,196 RPS.
- p50 347.3 ms; p95 3,011.6 ms; p99 10,617.5 ms; max 21,170 ms.
- Client errors: `EADDRNOTAVAIL` 81,351; `ERR_SOCKET_TIMEOUT` 114,365; `ECONNRESET` 1,706.
- The API's PostgreSQL pool reached its configured maximum of 60 connections. Its `waitingCount` peaked at 8,940, and the API log recorded 4,457 `pg-pool` connection acquisition timeouts (`timeout exceeded when trying to connect`).

This identifies two simultaneous pressure points, not a single server-only root cause: the generator still encountered local socket/ephemeral-port errors despite the 4,096 total-socket cap, while the API's 60-connection PostgreSQL pool accumulated a large waiting queue and timed out requests. The CI job runs Artillery, the API and PostgreSQL on one GitHub-hosted runner, so its result is not a clean isolated measurement of application capacity.

The API endpoint is a single authenticated `GET /api/games/:gameId/state` backed by a raw PostgreSQL query that reads the game row and authoritative state, checks membership, and aggregates participant IDs/entry fee. Existing access paths include the Game primary key, AuthoritativeGameState gameId primary key, and GameParticipant unique `(gameId,userId)` index; the GameParticipant `(gameId,createdAt)` ordered aggregation index is not present in the current Prisma schema. No index or migration has been applied yet. During the failing run, the app pool reached `total=60, idle=0`; `waitingCount` peaked at 8,940, and the API log recorded 4,457 `pg-pool` connection-acquisition timeouts. This is direct evidence of database pool contention under this workload, alongside generator-side socket errors.

To obtain query-level evidence before changing indexes or application behavior, the gate now runs `node --check` on the profiler and then a read-only `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` for the exact authenticated state query against the disposable CI database after fixture seeding. It also records a safe `pg_stat_activity` connection-state snapshot; the query plan JSON is uploaded with the capacity report even if the load step fails. This does not touch Supabase staging or production.

**Status remains NOT CERTIFIED.** The 10k RPS load step failed; independent SLO validation was skipped because Artillery exited non-zero. V6.3 CI and Security Audit passed, but neither is evidence of 10k capacity. A realistic final capacity test needs a dedicated/distributed generator separate from API/PostgreSQL resources; Artillery's own guidance says large, meaningful load tests should run from cloud workers rather than directly on CI nodes.
