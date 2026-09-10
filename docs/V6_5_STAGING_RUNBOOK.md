# V6.5 Staging Runbook

1. Provision isolated PostgreSQL staging database.
2. Inject secrets from a secret manager; never commit `.env` secrets.
3. Build the container.
4. Start the stack.
5. Apply Prisma migrations.
6. Verify health/readiness.
7. Run unit + integration + concurrency + security tests.
8. Run k6 against staging only.
9. Review logs, audit events, DB locks, and error rates.
10. Do not enable real-money settlement until external security/legal/payment review gates are approved.

Rollback:
- Stop the new API deployment.
- Restore the previous application image.
- Do not roll back database migrations destructively.
- Restore from a verified backup only under an approved incident procedure.
