# V6.2 Production Hardening Checklist

## Database
- Use a dedicated PostgreSQL database and least-privilege application role.
- Run `prisma migrate deploy` in the deployment pipeline.
- Enable automated backups and point-in-time recovery.
- Test restore procedures.
- Keep game state and ledger writes inside transactions.

## Concurrency / reliability
- Run the integration suite against real PostgreSQL.
- Run two or more API instances against the same DB.
- Verify only one concurrent command with a given stateVersion can commit.
- Retry transient PostgreSQL serialization failures with bounded exponential backoff.
- Add connection-pool limits and health/readiness checks.

## Security
- HTTPS only in production.
- Short-lived access tokens + secure refresh-token rotation.
- Password hashing with a modern adaptive password hash.
- Strict RBAC: PLAYER / ADMIN / GAME_SERVER.
- Rate-limit authentication and game-command endpoints.
- Validate all request bodies server-side.
- Never trust client dice, winner, payout, balance, or commission values.
- Store secrets only in a secret manager/environment, never in Android.
- Sanitize and minimize audit metadata.
- Add alerting for repeated failed auth, command conflicts, and suspicious wallet activity.

## Real-money gate
Do not enable real-money settlement until:
1. migrations are applied and verified,
2. integration and concurrency tests pass,
3. security review is completed,
4. payment-provider webhooks are authenticated and reconciled,
5. applicable gaming/payment/KYC/AML requirements are reviewed for the target market.
