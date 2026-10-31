# LudoMada — architecture implementation status

**Purpose:** distinguish code that is implemented from infrastructure and release steps that are still pending. This document describes the candidate branch; it is not a production-readiness declaration.

## Architecture map

```text
Android app (Kotlin)
  ├─ Native gameplay renderer (GLES3 / PBR; GLB rev5 candidate)
  ├─ Offline rules for local play
  └─ Authenticated intent API (roll / tokenIndex + expectedVersion + Idempotency-Key)
                         |
                         v
Node.js 24 LTS / TypeScript / Express API
  ├─ Authentication, validation, rate limits and audit events
  ├─ Authoritative Ludo rules engine and game command service
  ├─ PostgreSQL state repository and event append store
  └─ SSE notifications (process-local fanout; fetch authoritative snapshot)
                         |
                         v
PostgreSQL (Supabase staging target)
  ├─ AuthoritativeGameState snapshots
  ├─ GameCommand idempotency records
  ├─ GameEvent append records
  ├─ Game / participants / wallet / ledger records
  └─ Prisma schema and separately operated migration history

Container: Dockerfile -> node dist/server.js
Staging blueprint: Render Free (service created; protected DB/CORS environment and readiness setup still pending) -> existing Supabase staging
Edge/VPS: Nginx SSE proxy configuration (candidate PR; not deployed)
CI: backend/native tests, Android build/UI tests, release gate, security scans
```

## Implemented in source

| Layer | Evidence in source | Status |
|---|---|---|
| Android client | `android-app/app/src/main/java/com/ludomadagascar/MainActivity.kt`, `LudoBoardView.kt`, `OfflineLudoRules.kt` | Native Android app; 3D renderer and rev5 GLB are in PR #156 candidate, not yet in `main` |
| Backend API | `src/server.ts` | Express API routes call the durable game command service for authenticated roll/move |
| Rules engine | `src/game/ludoEngine.ts`, `ruleset.ts` | Server-owned 2–4 player Ludo state/rules |
| State consistency | `src/game/gameStateRepository.ts` | Per-game `SELECT ... FOR UPDATE` in a PostgreSQL `READ COMMITTED` transaction; bounded retry for serialization conflicts. It does not set transaction isolation to `SERIALIZABLE` |
| Idempotency/events | `src/game/authoritativeCommandService.ts`, `gameEventStore.ts`, Prisma models `GameCommand`, `GameEvent` | Commands and event records are persisted in the same database transaction as the authoritative state update |
| Security controls | `src/security.ts`, `src/server.ts` | Auth, request validation, roles, rate limits and audit logging are wired into API routes |
| Container/CI | `Dockerfile`, `.github/workflows/*` | Reproducible container build and test workflows; workflow results apply to their exact commit only |

## Explicitly incomplete / not live

1. **3D release integration:** `LudoBoardView.kt` and `assets/3d/ludomada-gameplay-rev5.glb` are on PR #156, which remains Draft/Open. They are not yet part of the current `main` baseline. Do not call this released.
2. **Staging host:** Render Free service `ludomada-api-staging` exists in Singapore (`srv-dbj32afb9u1s73ctr880`) with automatic deploy disabled. Its first Docker image build succeeded, but the container exited with `DATABASE_URL_IS_REQUIRED`; no protected `DATABASE_URL`, `CORS_ORIGINS` or `JWT_SECRET` has been set. The health check path also needs `/ready`. The service is not healthy and must not be treated as a live API.
3. **Database baseline:** the existing Supabase staging schema is present and empty, but `public._prisma_migrations` is absent. The exact read-only `prisma migrate diff` must be run with the real Session Pooler URL by the database owner before the six existing migrations are recorded. Do not run `prisma migrate deploy` blindly on the restored schema.
4. **SSE horizontal fanout:** `src/realtime/gameEventHub.ts` uses an in-process Map, capped at 64 streams per game and 4 per user. It does not distribute events between multiple API instances and does not replay missed events. The durable PostgreSQL state remains authoritative; clients refetch the state snapshot. Before multi-instance deployment, add and test shared fanout (for example, Redis pub/sub or PostgreSQL LISTEN/NOTIFY with a defined reconnect/replay policy).
5. **RLS defense-in-depth:** Supabase advisor flags RLS disabled on the 13 application tables. Fresh read-only checks confirmed `anon` and `authenticated` lack `public` schema USAGE and SELECT/INSERT privileges on `public.User`; the dedicated `ludomada_render_staging` role has required DML, has no `BYPASSRLS`, and all 13 tables remain empty. A proposed backend-only RLS policy model is documented in `docs/SUPABASE_RLS_STAGING_POLICY_REVIEW.md`; it has not been applied. Owner approval is required before adding policies and enabling RLS.
6. **Security APK audit:** the MobSF workflow exists but has not been run; a runner meeting the documented memory/disk requirements is still needed.
7. **Release boundary:** PR #156 and PR #158/#159 remain Draft/Open. No production deployment, Nginx reload, live payment activation, live wallet mutation or staging schema write is implied by their CI passes.

## Database migration lifecycle

The API container runs `node dist/server.js`; it does **not** apply Prisma migrations at startup. `npm run db:migrate` is a separate CLI operation. Render is configured with auto-deploy disabled and a runtime role without schema CREATE. Follow `docs/PRISMA_STAGING_BASELINE.md` for the existing empty staging schema; never give the application runtime role permanent DDL authority.

## CI evidence interpretation

- A passing workflow validates only the exact commit and steps shown in that run.
- Android UI test success and screenshot-export success are separate assertions.
- Release Gate against ephemeral CI PostgreSQL does not prove the existing Supabase staging DB has been baselined or that Render is healthy.
- A signed APK artifact is not a deployed or distributed release.
- OSV Full Scan is skipped for pull-request events and must not be described as passed.

## Next release sequence

1. The dual-API screenshot fix has passed in PR #158 (API 30 and API 27 each 2/2, with separate valid 1080×1920 artifacts) and has been carried into the PR #156 candidate. Confirm the rerun on the latest PR #156 head.
2. Confirm the Node 24 LTS Docker image build inside the Release Gate; then complete review of PR #156, PR #158 and PR #159. Merge only after explicit owner approval.
3. In Render Protected Environment, set the exact Supabase IPv4 Session Pooler host/port 5432 URL with the existing least-privilege role, the exact UI `CORS_ORIGINS`, and a generated `JWT_SECRET`; set health check path `/ready`. Do not paste values into chat, GitHub or Linear.
4. Run the read-only Prisma schema diff against that exact staging URL. Only after zero diff and all tables empty, perform the separately approved six-migration baseline.
5. Obtain approval for the backend-only RLS policy model in `docs/SUPABASE_RLS_STAGING_POLICY_REVIEW.md`, apply it as a reviewed migration, then test access using the runtime role and verify public roles remain blocked.
6. Verify staging `/health` and `/ready`, authentication, lobby/game flows, CORS, logs and all payment switches remain false.
7. Run MobSF on an eligible runner; address findings. Before horizontal scaling, implement and test shared SSE fanout. Keep production/payment/live-wallet activation behind separate explicit approvals.

**Scope note:** Railway is retired and is not part of this architecture or release path.
