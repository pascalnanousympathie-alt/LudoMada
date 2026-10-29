# Baseline Prisma migration history for the empty LudoMada staging database

## Scope

This runbook is only for the isolated Supabase staging project `ludomada-cloudflare-staging` (`jliatljqvvbibcibnrjk`). It is **not** for Railway, production, or any database with user/game/payment data. The current staging schema already matches the 13 Prisma models; it currently has zero rows and no `public._prisma_migrations` table. The original migrations cannot be replayed safely over this existing schema because they create objects and enum values that already exist and include older schema states.

Do not run these steps until the staging owner has set the password for the least-privilege `ludomada_render_staging` role and has copied the exact IPv4 **Session Pooler** connection string from Supabase Dashboard → Connect. Use port `5432`, TLS (`sslmode=require`), and keep credentials only in a protected local environment. Never commit or paste the URL into tickets or chat.

## 1. Verify you are targeting staging

From a private terminal, configure `DATABASE_URL` locally. Confirm the URL uses the session pooler, the project reference is `jliatljqvvbibcibnrjk`, and the username is `ludomada_render_staging.jliatljqvvbibcibnrjk`. Do not use the `postgres` superuser for the app connection.

Run this read-only SQL in the staging SQL Editor to verify the migration table is absent:

```sql
SELECT to_regclass('public._prisma_migrations') AS migration_table;
```

Expected before baselining: `NULL`. If it already exists, stop and inspect `SELECT migration_name, finished_at, rolled_back_at FROM public._prisma_migrations ORDER BY started_at;`; do not blindly repeat the baseline.

## 2. Confirm the schema exactly matches this checkout

Use the matching checkout of `main`, with dependencies installed and the repository's `prisma/schema.prisma`:

```sh
npx prisma migrate diff \
  --from-schema-datamodel prisma/schema.prisma \
  --to-url "$DATABASE_URL" \
  --exit-code
```

Expected: exit code `0` and no schema diff. If Prisma prints any proposed DDL or returns a non-zero code, stop. Do not baseline a schema that differs from this exact schema. This check is read-only.

## 3. Confirm every application table is empty

Run this read-only SQL in the same staging database. Expected: all counts are zero.

```sql
SELECT 'User' AS table_name, count(*) AS rows FROM public."User"
UNION ALL SELECT 'Wallet', count(*) FROM public."Wallet"
UNION ALL SELECT 'Transaction', count(*) FROM public."Transaction"
UNION ALL SELECT 'Game', count(*) FROM public."Game"
UNION ALL SELECT 'GameParticipant', count(*) FROM public."GameParticipant"
UNION ALL SELECT 'LedgerEntry', count(*) FROM public."LedgerEntry"
UNION ALL SELECT 'GameMove', count(*) FROM public."GameMove"
UNION ALL SELECT 'RefreshSession', count(*) FROM public."RefreshSession"
UNION ALL SELECT 'AuditLog', count(*) FROM public."AuditLog"
UNION ALL SELECT 'IdempotencyRecord', count(*) FROM public."IdempotencyRecord"
UNION ALL SELECT 'GameEvent', count(*) FROM public."GameEvent"
UNION ALL SELECT 'AuthoritativeGameState', count(*) FROM public."AuthoritativeGameState"
UNION ALL SELECT 'GameCommand', count(*) FROM public."GameCommand"
ORDER BY table_name;
```

If **any** count is non-zero, stop; this runbook is deliberately limited to the empty staging schema. Do not delete or truncate rows to make the check pass.

## 4. Record the already-present schema as migrated

Only after steps 1–3 pass, and only against the staging URL, mark the migrations that produced the current schema as applied. This writes Prisma migration-history metadata; it does **not** execute their historical SQL or alter application tables.

Run in order from the repo root:

```sh
npx prisma migrate resolve --applied 0001_v6_1_persistence
npx prisma migrate resolve --applied 0002_lobby_creator_waiting
npx prisma migrate resolve --applied 0002_payment_provider_metadata
npx prisma migrate resolve --applied 0003_lobby_creator_schema
npx prisma migrate resolve --applied 20260911203000_add_email_phone_auth
```

These are the five migration directories in this repository at the time this runbook was written. Recheck `prisma/migrations/` first if the branch changed. If any command fails, stop and inspect migration status/history before continuing; do not drop `_prisma_migrations` or try to repair the issue by replaying DDL.

## 5. Verify the baseline

```sh
npx prisma migrate status
```

Expected: all five listed migrations are applied, none are pending, and Prisma exits successfully. Repeat the read-only schema diff from step 2; it should still return code `0`. Confirm the 13 application tables remain empty and all live-payment flags remain disabled.

After this baseline is validated, future migrations can be introduced normally and `prisma migrate deploy` will apply only newly-added migrations. Do not add automatic migration execution to the Render startup command during the baseline operation.

## Rollback boundary

The baseline steps only add/maintain migration-history metadata. If verification fails after a partial baseline, stop and inspect the exact rows in `_prisma_migrations`; use Prisma's documented resolve workflow to finish the intended metadata state. Do not manually delete migration history, drop tables, replay the historical SQL, or touch the Railway volume. Keep Render `autoDeployTrigger: off` until a separate owner-approved staging deployment and smoke test succeeds.
