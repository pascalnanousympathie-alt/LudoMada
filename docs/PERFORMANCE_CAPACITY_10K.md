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
## Third measured benchmark — 2026-11-01 (cache enabled; failed)

Release Gate run `41271849087` on head `d76b75b11c3dc223fe5b339ab59e35f326409efd` enabled the process-local cache only for the guarded disposable CI capacity profile. It seeded 1,000 games, captured the SQL plan, warmed the cache at 1,000 arrivals/sec, then attempted 10,000 authenticated state reads/sec for 30 seconds. The profile still failed its SLO gate:

- 300,334 VUs created; 181,651 HTTP 200 responses; 118,683 VUs failed (39.52%).
- Aggregate observed request rate: 1,429 RPS; best 10-second interval: 4,412 RPS.
- p50 55.2 ms; p95 1,064.4 ms; p99 3,605.5 ms; max 15,081 ms.
- Errors: 118,023 `ERR_SOCKET_TIMEOUT`; 660 `ECONNRESET`; **zero** `EADDRNOTAVAIL`.
- Cache contained all 1,000 game entries; final snapshot was 192,267 hits / 2,567 misses (98.68% hit ratio).
- PostgreSQL pool waitingCount had median/p90 0 and maximum 9; no `STATE_TRUE_SINGLE_QUERY_ERROR` or `timeout exceeded when trying to connect` messages appeared in the API log. The pool's total/idle count returned to 14/14 at the last snapshot.
- Read-only SQL plan for the exact state query: 0.246 ms execution, 0.735 ms planning, 18 shared buffer hits and zero shared reads. It used `Game_pkey`, `AuthoritativeGameState_gameId_stateVersion_idx`, and `GameParticipant_gameId_userId_key`; the participant aggregation sort handled two rows.

This separates the remaining bottleneck from the prior run: the cache removed sustained database-pool starvation and socket ephemeral-port errors, but the combined API + Artillery job still cannot complete 10k HTTP requests/sec. The largest remaining client-side symptom is `ERR_SOCKET_TIMEOUT`; 10k virtual-user arrivals were created, but only 1,429 HTTP responses/sec were completed over the full observed window. Because the API and generator share one GitHub runner, the run still does not isolate API capacity from load-generator CPU, socket-pool or event-loop limits.

The next experiment raises Artillery's shared keep-alive pool from 2,048 to 4,096 and enables `config.http.extendedMetrics` to capture TCP/total timing phases. It retains all strict thresholds and does not change the API/database pool or bypass authorization. This is an experiment, not a capacity pass. The capacity artifact is `13069856828` and the API log artifact is `13069239526` from run `41271849087`.

**Status remains NOT CERTIFIED.** The DB query and pool are no longer the dominant observed bottleneck in this cache-enabled run; the shared CI runner / HTTP concurrency path remains limiting. A dedicated independent load generator against the intended multi-instance topology is still required before production 10k claims.
## Fourth measured benchmark — 2026-11-01 (cache enabled, 4,096-socket pool; failed)

Release Gate run `41272226027` on head `c8b8d784957ac2159d0f6aad00aad5c3c527df8f` enabled extended HTTP metrics and used `config.http.pool: 4096`. It improved aggregate throughput over the 2,048-pool run but still failed the capacity SLO:

- 300,334 VUs created; 206,130 HTTP 200 responses; 94,204 VUs failed (31.37%).
- Aggregate observed request rate: 4,018 RPS; highest 10-second interval: 6,873 RPS.
- p50 89.1 ms; p95 1,200.1 ms; p99 3,678.4 ms; max 15,199 ms.
- Errors: `ERR_SOCKET_TIMEOUT` 93,077; `ECONNRESET` 1,127; `EADDRNOTAVAIL` 0.
- Extended HTTP timing: `http.tcp` p95 4,403.8 ms / p99 11,274.1 ms; `http.total` p95 8,352 ms / p99 11,971.2 ms.
- Cache entries 1,000; final cache hits 216,033 / misses 2,101 (99.04% hit ratio). PostgreSQL pool waitingCount remained 0; max observed total/idle connections was 46; API log had no database acquisition timeouts or state-query errors.

The larger pool improved measured aggregate throughput from 1,429 to 4,018 RPS and eliminated ephemeral-port errors, but TCP-phase latency still grew to multiple seconds and the strict error/latency thresholds failed. This points to client-side shared-pool/runner connection pressure and/or event-loop contention; the database/cache no longer appears to be the dominant saturated component in this run.

The next experiment raises the shared socket pool to 8,192 and records runner limits (`ulimit -n`, ephemeral-port range, CPU count, file-max, load average and memory) in the same Artillery step. It keeps the same 10k RPS target and strict SLO thresholds. If this shared-runner experiment still cannot achieve the target, the next valid test must use a separate load-generator host from the API/database; do not claim the application itself is certified from a co-located generator.

Artifacts: capacity report `13069817523`, API logs `13069962253`, run `41272226027`.

**Status remains NOT CERTIFIED.**

## Fifth measured benchmark — 2026-11-01 (cache enabled, 8,192-socket pool; failed)

Release Gate run `41272731448` on head `1358c9be1a916d9dde0711099906fab578aa48bc` used `config.http.pool: 8192`, extended HTTP timing metrics, and recorded GitHub-hosted runner limits. The 10,000 arrivals/sec target and strict SLO gates were unchanged.

- 300,334 VUs created; 195,856 HTTP 200 responses; 104,478 VUs failed (34.78%).
- Aggregate observed request rate: 2,663 RPS; highest reported interval: 3,569 RPS.
- HTTP response time: p50 147 ms, p95 391.6 ms, p99 6,838 ms, max 14,999 ms.
- End-to-end `http.total`: p95 3,828.5 ms / p99 13,770.3 ms. TCP connect phase: p95 5,065.6 ms / p99 11,274.1 ms.
- Errors: `ERR_SOCKET_TIMEOUT` 102,345; `ECONNRESET` 2,133.
- Cache snapshot: 199,965 hits / 2,889 misses (98.58% hit ratio), with all 1,000 game entries populated.
- PostgreSQL pool reached 60 connections and briefly peaked at 184 waiting requests during cache fill; waiting returned to zero after warm-up. API logs contained no pool-acquisition timeout or state-query error. The read-only SQL plan measured 0.202 ms execution / 0.58 ms planning.
- Runner snapshot before load: 4 vCPUs, 15 GiB RAM, soft/hard file-descriptor limit 65,536, ephemeral port range 32,768–60,999 (28,232 ports), and file-max `9223372036854775807`. After load, `ss -s` reported 6,460 orphaned TCP sockets and 10,390 TIME_WAIT sockets; the TCP summary showed 60 established connections.

The 8,192-pool result was worse than the preceding 4,096-pool run (4,018 RPS), so a larger configured pool is not a monotonic improvement. The late-run TCP and total-latency tails, socket timeouts, and post-run orphaned/TIME_WAIT sockets show connection-path pressure. Because Artillery, the Express API and PostgreSQL still share the same 4-vCPU runner, these data do **not** prove whether the dominant limit is the generator, API event loop, or their contention. Do not attribute the failure solely to PostgreSQL: the cache hit ratio was 98.58%, SQL execution was 0.202 ms, and there were no DB acquisition timeouts.

The correct next diagnostic is an independently hosted load generator against an approved, separately hosted staging target. A second job on GitHub-hosted Actions cannot reach the first job's private loopback API without an explicit network path, so do not split the jobs and pretend that alone isolates the load generator. The real Render/Supabase staging target is not yet validated for this test; the current run only used disposable CI PostgreSQL and a loopback API. Keep the cache disabled by default and do not enable this process-local cache across replicas until shared invalidation exists.

**Status remains NOT CERTIFIED.** The load step failed and independent SLO validation was skipped. V6.3 CI and the listed Security Audit checks passed on this exact head; OSV Full Scan was skipped. No production or staging deployment was performed. Artifacts: capacity report `13069644457`, API log `13070167432`, run `41272731448`.
