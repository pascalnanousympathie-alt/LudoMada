# V6.4 Release Gate

A real-money deployment is blocked unless all items below are satisfied.

## Automated
- Prisma migrations succeed on a clean PostgreSQL database.
- Unit + integration + concurrency + security tests pass.
- k6 staging smoke/load test meets latency/error thresholds.
- Duplicate command tests prove idempotency.
- Concurrent moves prove exactly one state transition per version.

## Manual security review
- JWT/refresh-token lifecycle reviewed.
- RBAC boundaries reviewed.
- Rate limiting and abuse controls reviewed.
- Input validation and error handling reviewed.
- Secrets are outside source control and APK.
- Database credentials use least privilege.
- Audit log retention and access controls reviewed.

## Financial safety
- Entry fee, commission, prize pool and payout are calculated server-side.
- Settlement is atomic and idempotent.
- Payment webhooks are authenticated and reconciled.
- No client-provided balance, winner, payout, dice, or commission is trusted.

## Legal/compliance
Obtain qualified legal/compliance review for the jurisdictions in which the
real-money game and payment services will operate before enabling real-money
play.
