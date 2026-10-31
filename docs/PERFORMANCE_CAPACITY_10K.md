# LudoMada — 10,000 RPS capacity gate

## Existing 80 RPS check

`loadtests/artillery/loadtest-health-80rps.yml` calls only `GET /health` for 30 seconds at 80 arrivals/second. It is a liveness smoke check, not a gameplay capacity benchmark: no player authentication, game-state SQL, or command transaction is exercised.

The Render Free staging service has not started because its protected `DATABASE_URL` is not configured. Its observed Render metrics are empty, so no real staging throughput, latency, CPU, memory, or database saturation has been measured.

## New opt-in CI benchmark

The Release Gate `workflow_dispatch` input `run_capacity_test=true` will:

- Seed 1,000 active two-player games in the ephemeral GitHub Actions PostgreSQL service.
- Generate a short-lived JWT with the CI-only secret; store game IDs and JWTs in a mode-0600 fixture file which is deleted after the load test and never uploaded.
- Send authenticated `GET /api/games/:gameId/state` requests across the seeded games.
- Ramp 250 → 1,000 → 2,500 → 5,000 → 10,000 arrivals/second, then hold 10,000 for 30 seconds.
- Enforce an error rate at or below 0.1%, p95 at or below 250 ms, and p99 at or below 500 ms.

The high-volume switch is fail-closed: it requires `CI=true`, `NODE_ENV=staging`, `LUDOMADA_LOAD_TEST_MODE=true`, and a database URL pointing only to `127.0.0.1:5432/ludomada_ci` (or localhost). It cannot disable request limits against Render, Supabase, or production. No live database, wallet, payment, or user is touched.

## Interpretation

This first profile measures authenticated game-state reads only. API, PostgreSQL, and Artillery share one GitHub Actions runner, so the result is a diagnostic baseline and not a production 10k certification. A failed test must be diagnosed from request rate, errors, latency, CPU/memory and pool evidence; thresholds must not be relaxed to manufacture a pass.

It does not claim 10k `roll`/`move` writes per second. Those are transaction-heavy and append command, event and audit rows while locking one authoritative game-state row with `SELECT ... FOR UPDATE`. Per-game command serialization is intentional to preserve game order; aggregate write capacity must be tested separately across many games.

## Production requirements before certification

1. Deploy on multi-instance compute with sufficient CPU/RAM and autoscaling; Render Free's 0.1 CPU/512 MB single-instance tier is for staging only.
2. Budget PostgreSQL connections globally across all API instances and workers. Supabase Nano Free documents a 60-connection DB limit; increasing each Node pool independently risks exhausting it.
3. Implement shared SSE fanout and reconnect/replay before horizontal API scaling. The current event hub is process-local.
4. Use shared/distributed rate-limit storage; do not remove auth or production limits.
5. Benchmark mixed traffic from a separate load generator against the actual staging topology: authenticated state reads, legal moves, lobby/list, roll/move transactions and SSE. Record p50/p95/p99, error/429 rates, CPU, memory, event-loop delay, pool waiting, DB connections and lock waits.
6. Run soak, burst, restart/failure and multi-instance tests while checking game isolation, idempotency and command ordering.

Until these checks pass against the real deployment topology, status remains **NOT CERTIFIED FOR 10,000 PRODUCTION RPS**.