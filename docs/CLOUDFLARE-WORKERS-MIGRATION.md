# LudoMada on Cloudflare Workers (safe migration track)

## Architecture decision

This deploys the existing Express/TypeScript backend as a Cloudflare Worker while
retaining PostgreSQL and the existing Prisma schema. **It deliberately does not
move wallet/game data to D1.** The current application relies on PostgreSQL enums,
BigInt and JSON columns, PostgreSQL-specific SQL, row locking/serializable
transactions, and atomic wallet/game ledger updates. A D1 conversion would be a
database and transaction-logic migration, not a drop-in hosting replacement.

Use Cloudflare Hyperdrive in front of a reachable PostgreSQL database when
possible. The Worker also accepts a PostgreSQL URL via the `DATABASE_URL`
secret, but a pooled endpoint/Hyperdrive is preferred for serverless traffic.

The Worker creates a fresh Prisma client for each request and uses the existing
Express routes through Cloudflare's Node HTTP bridge. Do not introduce a global
`pg.Pool` or global Prisma client in the Worker runtime: Worker I/O connections
are invocation-scoped, while Hyperdrive keeps the database-side pool warm.

## Important current blocker

Railway's `LudoMada` and `Postgres` services were observed as Offline after the
Railway trial expired. A volume still being listed in Railway is **not** proof
that its database can currently be queried or that a current backup exists.
Do not point the Worker at the Railway URL until the database service is
reachable again. Before moving real users, obtain a verified backup and restore
it to a reachable PostgreSQL database. Do not initialize an empty production
database or run migrations against the old volume blindly.

## Cloudflare setup

1. Connect `pascalnanousympathie-alt/LudoMada` to Cloudflare Workers Builds, or
   use Wrangler from a trusted local/CI shell. Select branch
   `feat/cloudflare-workers-runtime-20261018` for the initial dry-run.
2. Ensure the target PostgreSQL database is reachable and restored from a
   verified backup. A fresh database is suitable only for isolated staging.
3. Set these as **Worker secrets**, not plain-text committed values:
   - `JWT_SECRET`: a random secret with at least 32 characters.
   - `CORS_ORIGINS`: comma-separated, exact HTTPS origins of approved clients.
   - `DATABASE_URL`: required if Hyperdrive is not configured.
4. For Hyperdrive, create it using the database's connection string:
   ```sh
   npx wrangler hyperdrive create ludomada-postgres --connection-string="postgresql://USER:PASSWORD@HOST:5432/DATABASE"
   ```
   Keep the output Hyperdrive ID private to the deployment configuration. Add a
   `hyperdrive` binding to `wrangler.jsonc` using the returned ID:
   ```json
   "hyperdrive": [
     {
       "binding": "HYPERDRIVE",
       "id": "REPLACE_WITH_THE_REAL_HYPERDRIVE_ID",
       "localConnectionString": "postgresql://USER:PASSWORD@127.0.0.1:5432/ludomada_test"
     }
   ]
   ```
   When this binding exists, the Worker uses its `connectionString`; otherwise
   it uses the `DATABASE_URL` secret. Never put a real password in the repo.
5. Run Prisma migrations from a trusted migration runner against the restored
   staging database (`npx prisma migrate deploy`). Do not run migrations
   automatically on every request or against production before verifying the
   backup.
6. Deploy only to a staging Worker first. Verify `GET /health`, `GET /ready`,
   register/login, refresh-token rotation, lobby, game-state concurrency, wallet
   ledger invariants, and payment-provider-disabled behavior.
7. Keep `PAYMENTS_MVOLA_LIVE_ENABLED=false` and
   `PAYMENTS_BINANCE_LIVE_ENABLED=false` until provider credentials/contracts,
   webhook verification, and operational approval have been verified. Do not
   enable payments as part of this migration.
8. Add Cloudflare edge rate-limiting/WAF rules before public testing. The
   existing Express rate-limit memory store is process-local and is not a global
   limit across Worker isolates.
9. Change the Android/API base URL only after staging and regression gates pass.
   Keep the Railway deployment/data intact until backup, rollback, and cutover
   have all been verified.

## Local dry-run

- Create a local `.dev.vars` file with `DATABASE_URL`, `JWT_SECRET`, and `CORS_ORIGINS` for a disposable DB. The file is git-ignored.
- Run `npm ci`, `npx prisma generate`, then
  `npx wrangler@4 deploy --dry-run --config wrangler.jsonc`.
- Never commit `.dev.vars`, database URLs, JWT secrets, payment credentials, or
  Hyperdrive connection strings.

This branch is migration preparation; it is not a claim that a Cloudflare
account, database, secret set, live deployment, or production smoke test has
already been configured or verified.
