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
5. **Migration history must be reconciled before running Prisma migrations.**
   The separate `ludomada-cloudflare-staging` Supabase project was created on
   2026-10-18 and its empty database was initialized from the current
   `prisma/schema.prisma` model: 13 tables, 104 columns, 5 enums, 13 foreign
   keys, and zero data rows were verified. This was applied through the Supabase
   migration API, not through Prisma Migrate; the Prisma
   `_prisma_migrations` baseline is therefore not yet reconciled. **Do not run
   `npx prisma migrate deploy` against this already-initialized database** until
   a reviewed Prisma baseline and its applied-history procedure are prepared.
   Do not run migrations automatically on every request or against production.
6. **Security gate:** Supabase currently reports Row Level Security (RLS)
   disabled for all 13 public tables. Do not expose this database through the
   Supabase Data API, add test/user data, or connect a public client until an
   intentional RLS/grant policy has been reviewed and applied. See the project's
   Security section in the deployment notes; enabling RLS without policies
   blocks normal anon/authenticated table access. Do not add user-selected
   policies without deciding the intended access model first.
7. Deploy only to a staging Worker after the database security and migration
   gates are resolved. Verify `GET /health`, `GET /ready`, register/login,
   refresh-token rotation, lobby, game-state concurrency, wallet ledger
   invariants, and payment-provider-disabled behavior.
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

## Staging database access-control state (verified 2026-10-18)

The separate staging database now has a second Supabase migration named
`ludomada_backend_only_privileges`. It revokes table, sequence, and function
privileges from `anon` and `authenticated`, plus default privileges for
objects created by the `postgres` role used by the migration connector. This is
an interim backend-only restriction; **it does not enable RLS**.

Read-only verification confirmed:
- 13 application tables remain present and total data rows remain 0.
- Direct table grants to `anon` / `authenticated`: 0.
- Table grants to the PostgreSQL `PUBLIC` role: 0.
- `anon` cannot SELECT from `User` or UPDATE `Wallet`.
- `authenticated` cannot SELECT from `Transaction` or INSERT into
  `GameCommand`.

A third staging migration, `ludomada_disable_public_schema_usage`, has now
revoked schema `USAGE` from PostgreSQL's `PUBLIC` pseudo-role while preserving
explicit `USAGE` for `postgres` and `service_role`. Read-only verification
confirmed `postgres` and `service_role` still have schema usage, while
`anon` and `authenticated` do not. Therefore client roles cannot reach
application tables through the public schema, including tables created later
with unsafe table ACL defaults.

A separate default-ACL entry for the `supabase_admin` role still includes
`anon` / `authenticated` grants. An attempt to alter those defaults failed
with PostgreSQL `42501 permission denied to change default privileges`, because
the connector's `postgres` role is not a member of `supabase_admin` and is not
a superuser. This did not change the database. Do not re-grant schema
`USAGE` to `PUBLIC` or to the client roles until the `supabase_admin`
default ACL is secured by an owner with the required role membership. The
verified migration path runs as `postgres`, whose defaults exclude those
client roles.


## Required database security decision

The staging database currently has RLS disabled on its 13 application tables.
For reference, the Supabase advisor's baseline RLS statements are:

```sql
ALTER TABLE "public"."User" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."Wallet" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."Game" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."Transaction" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."GameParticipant" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."LedgerEntry" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."GameMove" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."RefreshSession" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."AuditLog" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."IdempotencyRecord" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."GameEvent" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."AuthoritativeGameState" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."GameCommand" ENABLE ROW LEVEL SECURITY;
```

These statements have **not** been applied. Enabling RLS without policies is a
deliberate fail-closed state for Supabase anon/authenticated access and may
block direct app behavior depending on the database role. Confirm the intended
access model, keep the service credential server-side only, and add/review any
policies before exposing or seeding the database. Never put a database password
or service-role key in Git.

## Local dry-run

- Create a local `.dev.vars` file with `DATABASE_URL`, `JWT_SECRET`, and `CORS_ORIGINS` for a disposable DB. The file is git-ignored.
- Run `npm ci`, `npx prisma generate`, then
  `npx wrangler@4 deploy --dry-run --config wrangler.jsonc`.
- Never commit `.dev.vars`, database URLs, JWT secrets, payment credentials, or
  Hyperdrive connection strings.

This branch is migration preparation; it is not a claim that a Cloudflare
account, database, secret set, live deployment, or production smoke test has
already been configured or verified.
