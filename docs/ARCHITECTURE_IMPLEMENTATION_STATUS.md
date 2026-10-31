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
Staging blueprint: Render Free (not provisioned) -> existing Supabase staging
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
2. **Staging host:** `render.yaml` and `docs/RENDER_FREE_STAGING.md` define the intended Render Free service, but no LudoMada Render service currently exists. The planned `ludomada-api-staging.onrender.com` endpoint returned 404 when checked. The Render environment still requires the owner's protected `DATABASE_URL` and exact `CORS_ORIGINS`.
3. **Database baseline:** the existing Supabase staging schema is present and empty, but `public._prisma_migrations` is absent. The exact read-only `prisma migrate diff` must be run with the real Session Pooler URL by the database owner before the six existing migrations are recorded. Do not run `prisma migrate deploy` blindly on the restored schema.
4. **SSE horizontal fanout:** `src/realtime/gameEventHub.ts` uses an in-process Map, capped at 64 streams per game and 4 per user. It does not distribute events between multiple API instances and does not replay missed events. The durable PostgreSQL state remains authoritative; clients refetch the state snapshot. Before multi-instance deployment, add and test shared fanout (for example, Redis pub/sub or PostgreSQL LISTEN/NOTIFY with a defined reconnect/replay policy).
5. **RLS defense-in-depth:** Supabase advisor flags RLS disabled on the 13 application tables. Current read-only checks found `anon` and `authenticated` lack public-schema usage and table DML grants, and the app uses a dedicated least-privilege database role. This reduces present direct access but does not replace reviewed RLS policies. Do not enable RLS blindly without policies matching the API's direct PostgreSQL access model.
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

1. Complete PR #158 API 30 and API 27 UI runs, including distinct valid PNG evidence.
2. Complete review of PR #156, PR #158 and PR #159; merge only after explicit owner approval.
3. Owner provisions the Free Render staging service and supplies its protected environment values through the Render dashboard.
4. Owner executes the read-only Prisma schema diff against the exact Supabase Session Pooler URL; only after zero diff and zero-row confirmation, perform the separately approved migration baseline.
5. Verify staging `/health` and `/ready`, authentication, lobby/game flows, CORS, logs, and that every payment switch remains false.
6. Run MobSF on an eligible runner; address findings.
7. Before horizontal scaling, implement and test shared SSE fanout. Keep production/payment/live-wallet activation behind separate explicit approvals.

**Scope note:** Railway is retired and is not part of this architecture or release path.
