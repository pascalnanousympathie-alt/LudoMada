# V6.3 Test Matrix

The repository now has a reproducible PostgreSQL CI environment.

## Required gates
1. `npm ci`
2. `npx prisma generate`
3. `npx prisma migrate deploy`
4. `npm run test:all`

## Concurrency scenarios
Run two independent service instances against the same PostgreSQL database and
submit the same `expectedVersion`. Exactly one command may commit.

## Security scenarios
Verify unauthenticated access is rejected, role boundaries are enforced,
client-supplied dice/winner/payout values are ignored or rejected, and
idempotency keys prevent duplicate financial/game commands.

The CI setup proves reproducibility of the test environment; it is not a
substitute for an external penetration test or formal security audit.
