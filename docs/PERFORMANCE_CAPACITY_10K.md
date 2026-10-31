# LudoMada — 10,000 RPS capacity gate

## Existing 80 RPS test

`loadtests/artillery/loadtest-health-80rps.yml` calls only `GET /health` for 30 seconds at 80 arrivals/second. It is a liveness smoke check, not a gameplay capacity benchmark: no player authentication, game-state SQL, or command transaction is exercised.

The Render Free service has not started because its protected `DATABASE_URL` is not configured. Its observed Render metrics are empty, so there is no measured staging throughput, latency, CPU, memory, or DB saturation.

## New opt-in CI profile

The Release Gate workflow_dispatch input `run_capacity_test=true` will:

- Seed 1,000 active two-player games in the ephemeral GitHub Actions PostgreSQL service.
- Generate a short-lived player JWT with the CI-only test secret and write the fixtures to a mode-0600 temporary CSV; the CSV is removed after the run and is never uploaded.
- Exercise authenticated `GET /api/games/:gameId/state` across the games.
- Ramp 250 → 1,000 → 2,500 → 5,000 → 10,000 arrivals/second, then hold 10,000 for 30 seconds.
- Enforce error rate no higher than 0.1%, p95 no higher than 250 ms, and p99 no higher than 500 ms.

The load-test switch is fail-closed. It is allowed only with `CI=true`, `NATIVE_TEST_MODE=true`, `NODE_ENV=staging`, and a database URL pointing to `127.0.0.1:5432/ludomada_ci` (or localhost). It cannot disable rate limits against Supabase, Render staging, or production. No staging/production database, test user, wallet, payment, or secret is used.

## What this result means

This profile measures authenticated game-state reads only. It is a single-runner diagnostic baseline because the API, Postgres, and Artillery generator share a GitHub-hosted runner. Even a pass is not a production 10k RPS certification; a fail must be diagnosed rather than hidden by relaxing thresholds.

It does not claim 10k `roll`/`move` writes per second. Those commands append idempotency, event and audit rows and lock one authoritative game-state row using `SELECT ... FOR UPDATE`; commands for one game are intentionally serialized to preserve ordering. Aggregate command capacity must be benchmarked separately across many games.

## Production prerequisites

1. Use compute with sufficient CPU/RAM, multiple instances and autoscaling. Render Free is a one-instance, no-autoscaling staging tier (0.1 CPU and 512 MB RAM), not a 10k production API platform.
2. Budget total PostgreSQL connections across all API instances and background workers. Supabase Nano Free documents a 60-connection DB limit; multiplying per-instance pools without a budget will exhaust it.
3. Implement shared SSE fanout and reconnect/replay before using multiple API instances. The current event hub is process-local.
4. Use a shared/distributed rate-limit store; do not remove authentication or public rate limits.
5. Benchmark mixed real traffic from a separate load generator against the real topology: authorized state reads, lobby/list, legal moves, roll/move writes and SSE streams. Record p50/p95/p99, error/429 rate, CPU, memory, event-loop delay, pool wait, DB active connections and lock waits.
6. Run soak, burst, restart/failure and horizontal-scaling tests while validating idempotency, game isolation and command ordering.

Until those checks pass against the real multi-instance deployment, status remains **NOT CERTIFIED FOR 10,000 PRODUCTION RPS**.