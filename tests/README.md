# V6.3 Test Matrix

The repository now has a reproducible PostgreSQL CI environment.

## Required gates
1. `npm ci`
2. `npx prisma generate`
3. `npx prisma migrate deploy`
4. `npm run test:all` — Jest unit suite for the TypeScript backend
5. Start the API against the disposable PostgreSQL database.
6. `npm run test:native` — Node test-runner suite for HTTP, 2–4 player game flows, settlement, authorization, concurrency and idempotency.

The native suite is intentionally serialized with `--test-concurrency=1`. It
uses randomly scoped fixtures and must run only against a disposable CI/test
database; several cases create test users, games, wallet entries and payouts.
It must never target production or the owner's Supabase/Railway databases.

The Release Gate enables a higher request budget only when `CI=true`,
`NATIVE_TEST_MODE=true`, `NODE_ENV=staging`, and the database URL points to
the loopback `ludomada_ci` PostgreSQL service. All other environments retain
the normal auth (20/15 min) and API (120/min) rate limits.

## Concurrency scenarios
Run two independent service instances against the same PostgreSQL database and
submit the same `expectedVersion`. Exactly one command may commit.

## Security scenarios
Verify unauthenticated access is rejected, role boundaries are enforced,
client-supplied dice/winner/payout values are ignored or rejected, and
idempotency keys prevent duplicate financial/game commands.

The CI setup proves reproducibility of the test environment; it is not a
substitute for an external penetration test or formal security audit.
