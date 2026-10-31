# Supabase staging RLS policy review — LudoMada

**Status: review required; no database change has been applied.**

## Read-only evidence (2026-10-31)

Project: `ludomada-cloudflare-staging` (`jliatljqvvbibcibnrjk`), PostgreSQL 17.11.

The 13 application tables exist and all are empty. Supabase's advisor reports RLS disabled on all 13 tables. A read-only privilege check returned:

- `anon`: no `USAGE` on schema `public`; no `SELECT` or `INSERT` on `public."User"`.
- `authenticated`: no `USAGE` on schema `public`; no `SELECT` or `INSERT` on `public."User"`.
- `ludomada_render_staging`: `SELECT`, `INSERT`, `UPDATE`, and `DELETE` privileges on all 13 application tables; role is `LOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, and `NOBYPASSRLS`.
- A per-table privilege matrix confirmed all 13 tables return `false` for `anon SELECT` and `authenticated SELECT`, while the backend role has all four DML privileges. RLS is disabled on every one of the 13 tables.

All 13 row counts were individually checked and are zero. No DDL or data write was performed.

## Application authorization model

LudoMada currently uses its own JWT authentication in the Express API; it does not use Supabase Auth's `auth.uid()` claims for per-player database authorization. The backend validates the caller, game participation, roles, state version and idempotency before it issues database statements. All API database operations use the dedicated `ludomada_render_staging` role.

For this architecture, a policy that allows the dedicated backend role to access the application rows is the compatible RLS boundary. It is **not** a substitute for application authorization and must not be granted to `anon`, `authenticated`, `PUBLIC`, or a client key.

## Candidate policy SQL — not applied

After explicit owner approval, add RLS and one backend-only policy on each of these tables:

```sql
ALTER TABLE public."User" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."User"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."Wallet" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."Wallet"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."Game" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."Game"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."Transaction" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."Transaction"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."GameParticipant" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."GameParticipant"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."LedgerEntry" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."LedgerEntry"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."GameMove" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."GameMove"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."RefreshSession" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."RefreshSession"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."AuditLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."AuditLog"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."IdempotencyRecord" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."IdempotencyRecord"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."GameEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."GameEvent"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."AuthoritativeGameState" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."AuthoritativeGameState"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);

ALTER TABLE public."GameCommand" ENABLE ROW LEVEL SECURITY;
CREATE POLICY ludomada_backend_access ON public."GameCommand"
  FOR ALL TO ludomada_render_staging USING (true) WITH CHECK (true);
```

This gives the trusted backend role the same table operations it already has by GRANT, while RLS denies access to every role without an explicit matching policy. It intentionally does not add `auth.uid()` policies: LudoMada's identity claims are currently verified by its own API, not Supabase Auth.

## Required verification if approved

1. Apply the SQL only to this staging project and record it as an explicit reviewed migration.
2. Confirm all 13 tables have RLS enabled and exactly the intended backend policy.
3. Confirm `anon` and `authenticated` still have no schema USAGE or table privileges.
4. Confirm `ludomada_render_staging` still has no `BYPASSRLS`, and test register/login, lobby/game commands, event streams, and safe reads using staging test accounts.
5. Run the backend integration, concurrency and security suites. Confirm the Supabase advisor no longer reports RLS disabled.

**Do not run the candidate SQL until the owner approves the backend-only policy model.** Enabling RLS without the matching policy will block the backend. Do not grant table access to public/client roles to silence the advisor.
