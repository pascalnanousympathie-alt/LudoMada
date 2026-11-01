# LudoMada Multi-Agent Development Architecture

**Version:** 1.0
**Prepared:** 2026-10-31
**Status:** Architecture specification and rollout plan; not a claim that every agent connector is already installed or running.

## 1. Objective

Deliver LudoMada through one controlled engineering pipeline in which specialist coding agents can work in parallel without editing the same files, duplicating old fixes, bypassing security controls, or treating a successful APK build as proof that the whole game is release-ready.

The architecture is based on the current repository layout and CI workflows. GitHub is the source of truth for source, branches, commits, reviews, checks and artifacts. A task is complete only when its code, tests and evidence are all attached to a reviewed pull request.

## 2. Verified baseline and current constraints

The current PR #156 candidate branch is `fix/3d-apk-runtime-asset-current-head-20261030` at head `b26f9c4b9ab7623eb325a9b46e366a149cc58685` (checked 2026-10-31 18:36Z). Its existing code/architecture map is `docs/ARCHITECTURE_IMPLEMENTATION_STATUS.md`; this document adds the multi-agent operating model. Earlier visual/Ramus evidence on predecessor SHA `2b513aa` must not be attributed to the current head. This remains a Draft/Open candidate, not an approved release or merge target.

Verified repository capabilities include:

- Backend: TypeScript/Express, Prisma/PostgreSQL, authoritative Ludo rules, server-generated dice, durable game state, command idempotency, version checks, game event storage and realtime event delivery.
- Android: native Kotlin/Java application, offline rules, online API integration, OpenGL ES renderer, runtime GLB asset loading, material/lighting/motion code and localized strings.
- QA: Jest/native Node tests, PostgreSQL-backed native tests, Android unit/instrumentation suites, Ramus game smoke, framebuffer/readback assertions, APK asset checks and release signing checks.
- CI: Android APK, emulator, UI instrumentation, framebuffer contract, backend CI, release gate, security audit and Render blueprint validation workflows.

Current evidence that must shape prioritisation:

1. The earlier complete Ramus/GLES3/PBR success applies to predecessor SHA `2b513aa` only. The current PR #156 head `b26f9c4b9ab7623eb325a9b46e366a149cc58685` now has Release Gate PASS (`41230261297`), Android UI Instrumentation PASS on API 30 and API 27 (`41230261326`), V6.3 CI PASS (`41230261300`), Framebuffer Contract PASS (`41230261321`), Render Blueprint Safety PASS (`41230261344`), and Security Audit PASS (`41230261734`, with OSV Full Scan skipped). These are current full-head CI results; they do not replace Ramus visual/runtime verification or owner review.
2. On predecessor SHA `2b513aa`, an Android emulator/Ramus run passed the 3D GLES3/PBR renderer assertions, loaded six hero parts and 95 board parts, passed framebuffer readback, and passed the listed offline/2–4-player flows. That is valid evidence for that SHA only; run Ramus again against current head `225c0a` before treating the latest candidate as visually verified.
3. Later Ramus runs failed to observe renderer log markers (`rendererLines=0`, missing PBR/GLB/readback markers) after the board screen was reached. This is a telemetry/runtime-evidence failure until screenshots, app markers and guest logs prove an actual renderer defect; do not rewrite 3D code on that evidence alone.
4. PR #156's original API 27 failure was `UiAutomation.grantRuntimePermission(...)` at `StartupUiTest.kt:141`; that method is unavailable on API 27. The validated app-private screenshot fix is present byte-for-byte in the current PR #156 branch: all three affected file blobs match the tested PR #161 patch (`103323be` script, `ffc91ddd` workflow, `35eeee32` instrumentation test). PR #161 UI run `41229249488` passed API 30 and API 27, with valid 1080×1920 screenshots (artifact `13053137384`). Full current PR #156 head `b26f9c4b9ab7623eb325a9b46e366a149cc58685` subsequently passed its own API 30/API 27 UI run `41230261326` and Release Gate `41230261297`; APK/test artifact `13053576957`, UI screenshot artifact `13053567148`. No further API 27 screenshot patch is needed.
5. Render lists a Free Singapore LudoMada staging service `ludomada-api-staging` (`srv-dbj32afb9u1s73ctr880`, `https://ludomada-api-staging.onrender.com`), branch `main`, auto-deploy disabled, one free instance. Both deploy attempts failed at application startup: `dep-dbj32b7b9u1s73ctra10` (2026-10-31 18:21:50Z) and the newer `dep-dbjbtmvlk1mc738qefq0` (2026-11-01 04:24:25Z). The latest Render app log explicitly shows `/app/dist/server.js:31` throwing `DATABASE_URL_IS_REQUIRED`; Docker image build succeeded. This proves the service process did not receive `DATABASE_URL`; it does not prove any Supabase password/URI is invalid. `JWT_SECRET` status remains unverified. No protected value was read or changed. The main-branch Dockerfile uses Node 20 while dependencies emit `EBADENGINE` warnings requiring Node >=22; this is a separate compatibility warning, not the cause of these startup failures. The PR #156 candidate Dockerfile uses Node 24. Staging remains BLOCKED until the owner configures the correct database URL privately in Render Protected Environment and the branch/runtime alignment is reviewed. Supabase staging remains ACTIVE_HEALTHY with 13 empty application tables and an incomplete Prisma baseline.
6. Current PR #156 head `b26f9c4b9ab7623eb325a9b46e366a149cc58685`: Release Gate, UI instrumentation (API 27/API 30), V6.3 CI, framebuffer contract, Render Blueprint Safety and listed Security Audit checks all passed; OSV Full Scan was skipped. Artifacts: APK/test `13053576957`, UI screenshot `13053567148`. PR #159's Nginx/SSE change still needs independent review and actual staging-proxy verification. PR #162 has now completed seven authenticated-read capacity attempts. The latest Release Gate run (`41274362018`, PR head `850fa7a6`) still fails: 300,334 VUs created, 187,138 responses, 113,196 failed VUs (37.68%), aggregate 2,306 RPS, response p95 278.7 ms / p99 3,534.1 ms, TCP p95 7 ms / p99 11,274.1 ms, and 106,647 socket timeouts (plus 3,381 resets and 3,168 address-unavailable errors). The run used an 8,192-socket pool. The prior 8,192-pool attempt (`41274004862`) reached 3,029 RPS but also failed; the earlier 4,096-pool run reached 4,018 RPS, so pool-size changes are not a reliable fix. New guarded API telemetry shows the Node process used about 90–94% of one core during the load, with event-loop delay peaking at p95 248 ms / p99 362 ms. PostgreSQL waiting returned to zero after cache warm-up; SQL execution was 0.25 ms, cache hit ratio 98.35%, and API RSS peaked at 170.8 MiB. Artillery, Express and PostgreSQL still share one 4-vCPU runner, so generator contention remains coupled to server saturation. The fixture creates 1,000 two-player games and tests only authenticated state reads—not four-player payloads, roll/move writes, or mixed traffic. A read-only code review confirms CI capacity mode is fail-closed to `CI=true`, `NODE_ENV=staging`, and the disposable loopback `ludomada_ci` database; the state route retains authentication and participant checks, and cache invalidation follows successful writes. The guarded CPU profile has now identified a concrete hot path. Release Gate run `41285010711` (8th measured attempt, 8,192-socket pool) still failed: 300,334 VUs created, 212,048 HTTP 200, 88,286 VUs failed (29.40%), 4,524 aggregate RPS, response p95 347.3 ms / p99 5,378.9 ms, TCP p95/p99 11,274.1 ms, with 67,887 `ERR_SOCKET_TIMEOUT`, 1,770 `ECONNRESET`, and 18,629 `EADDRNOTAVAIL`. PostgreSQL EXPLAIN planning/execution were 0.43/0.183 ms; pool waiting peaked at 283 during cache fill and returned to zero; cache hit ratio was 98.56%. API telemetry reached 90–98.1% of one core and event-loop delay p95/p99 peaked at 232.52/266.21 ms.

The 124.6-second Node CPU profile (103,374 samples; artifact `13075310673`) shows `createPublicKey` under `jsonwebtoken/verify.js → getSecret → verifyAccessToken → auth` consumed 9.43 seconds of self CPU (7.48% of sampled time); `verifyAccessToken` was 15.24 seconds inclusive and the `auth` middleware 36.69 seconds inclusive. The same profile attributes 7.52 seconds to native `writev`, 6.91 seconds to garbage collection, and 1.82 seconds to Express JSON serialization. Upstream `node-jsonwebtoken` issue #1046 documents that version 9.0.3 tries `createPublicKey()` on an HMAC string secret and falls back to `createSecretKey()` each verification; passing a pre-parsed `KeyObject` avoids that repeated failed parse (see https://github.com/auth0/node-jsonwebtoken/issues/1046).

A bounded patch is now present on PR #162 head `6013c99`: the API creates one `SecretKeyObject` from the existing UTF-8 `JWT_SECRET` and passes it to `verifyAccessToken`; the `HS256` allowlist, signature verification, claims validation and signing path remain unchanged. `tests/security.test.ts` covers KeyObject verification, string compatibility, wrong-key rejection, HS384 rejection and invalid claims. Security Audit passed on this head; V6.3 CI and Release Gate are still running, so this fix is **pending verification**, not yet certified. The latest benchmark still tests only 1,000 two-player read fixtures; production 10k certification remains blocked on a separate load generator and approved staging topology. Next diagnostic: collect a Node CPU profile under the same disposable guard to identify hot functions, then run against a separate approved staging topology from an independent generator.
7. The user's existing Termux `~/LudoMada` checkout is dirty and on `feat/gameplay-cosmetics-v1`; it contains modified and untracked files. It is a protected workspace. Do not pull, reset, clean, stash, rebase or edit it as part of this architecture rollout.
8. The implementation-status document records: SSE fanout is process-local and does not replay missed events; multiple API instances need shared fanout/reconnect before scaling. On Supabase staging, all 13 application tables are empty and RLS is disabled; the Security Advisor labels this CRITICAL. A fresh direct privilege check confirms `anon` and `authenticated` have no `public` schema USAGE and no table SELECT/INSERT/UPDATE/DELETE, while `ludomada_render_staging` has the required table DML and `BYPASSRLS=false`. Thus no current client-role table access was found, but RLS remains defense-in-depth debt. Do not apply `ENABLE ROW LEVEL SECURITY` without reviewed backend-only policies; it can block the API. `public._prisma_migrations` is absent while Supabase has its own `supabase_migrations.schema_migrations`; do not run historical Prisma migrations blindly. Real-money/legal/payment approval remains separate from APK readiness.

## 3. Authority and orchestration

### Human owner

The owner retains final authority over release, merge, protected environment values, database baseline/migrations, production cutover, payments, wallet operations and any destructive/security-sensitive change.

### Central orchestrator — ChatGPT

Responsibilities:

- Maintain the project-level plan and dependency graph.
- Read GitHub PR/check/log evidence before assigning work.
- Split work into bounded tasks with exact write scopes and acceptance criteria.
- Route tasks to the right specialist tool/agent; prevent overlapping edits.
- Compare independent review and CI results; identify root causes rather than retrying blindly.
- Keep task state and evidence in GitHub PRs/issues and the existing Linear operations tracker.

ChatGPT is the coordinator, not an always-running background process. Any asynchronous or scheduled execution must be explicitly implemented through an available automation service and verified; never imply that an agent is running when no task/run ID exists.

### Project architecture agent — read-only by default

Produces system maps, dependency decisions, API/data contracts, ADRs, implementation slices and acceptance criteria. It does not own feature implementation and cannot approve its own design changes.

## 4. Specialist agents and non-overlapping ownership

| Agent role | Preferred tool(s) | Owned scope | Explicit exclusions |
|---|---|---|---|
| Backend game/domain | Antigravity or Codex CLI | `src/game/**`, `src/realtime/**`, game-domain unit/native tests | No Android UI, payment provider activation, deployment or DB schema changes unless separately assigned |
| Backend API/security | Codex CLI or Antigravity | `src/server.ts`, `src/security.ts`, API middleware/controllers and matching API/security tests | Do not edit authoritative rules or payment live switches; database migrations require a separate reviewed task |
| Android application/UI | Antigravity | `android-app/app/src/main/java/**` except renderer-owned files; `res/**`; Android app navigation/accessibility/localisation | Do not edit GL renderer/GLB assets or test infrastructure in the same task |
| Android 3D/rendering | Antigravity | `LudoBoardView.kt`, `GlbRuntimeLoader.kt`, `LudoMaterialCalibration.kt`, `LudoMotionPhysics.kt`, `PremiumMaterialLighting.kt`, `PremiumVisualMotion.kt`, `RayTraceSupport.kt`, `SpatialGameAudio.kt`, `android-app/app/src/main/assets/3d/**` | No UI test storage work, API/backend rules or asset replacement without source/visual evidence |
| Test infrastructure / Ramus | Roo Code OR Cline (choose one), with Codex for bounded changes | `scripts/ramus-smoke.mjs`, `android-app/app/src/androidTest/**`, `android-app/app/src/test/**` and QA workflow test steps when explicitly scoped | Test agent cannot change product behavior to make its tests pass; cannot remove gates or self-approve |
| DevOps / CI / security automation | Codex CLI or Roo Code/Cline | `.github/workflows/**`, `Dockerfile`, `docker-compose*.yml`, `render.yaml`, `deploy/**`, CI-only scripts | No production environment edits, secrets rotation, schema migration or payment configuration |
| Design / UX | Figma + design/UX agent | Figma file, design tokens/specifications, approved visual references and UX acceptance criteria | Figma output is not source-of-truth code; no direct production changes |
| Independent reviewer | Roo Code OR Cline, or a separate Codex review session | Read-only review of the PR diff, architecture, security and regressions | Must not implement the same patch it reviews; must report file/line evidence |
| Visual/browser QA | Nanou Browser | Browser navigation, preview verification, screenshots, UI flow checks and browser console/network evidence | Not a coding agent; Android browser tests do not replace Ramus/emulator testing |
| Release/evidence coordinator | ChatGPT + GitHub MCP + Linear | Checklists, artifact/commit correlation, issue status and release evidence | Cannot merge, publish, deploy production or approve live-money functions |

### Use of coding products

- **Antigravity:** primary interactive coding environment for UI/Android/3D tasks and visual debugging. One agent owns one bounded branch at a time.
- **Codex CLI:** available in the Termux environment; use for bounded implementation or read-only architecture/review in a separate worktree. It must never operate in the dirty `~/LudoMada` checkout.
- **Roo Code or Cline:** select one as the independent reviewer/test-infrastructure agent for a workstream. Do not run both against the same task or files.
- **GitHub Copilot Free:** inline completion and small coding assistance only; not the project orchestrator or proof of autonomous task completion.
- **OpenHands:** optional later for isolated issue-to-PR jobs after its runtime, repo permissions, cost and artifact handling are verified. It is not a required dependency for the first delivery.
- **Replit/Lovable:** prototype or isolated UX exploration only. Their copies must not become a second source of truth for the LudoMada repository.
- **Figma:** design and design-system authority only for approved visual specifications.

No local Ollama runtime is part of this architecture. Nanou Browser replaces TinyFish for browser automation. Railway is retired and excluded.

## 5. MCP and service responsibilities

| Tool/service | Responsibility | Default permission |
|---|---|---|
| Existing personal Mada MCP / Termux tunnel | Preferred local workspace, task and controlled command bridge, and MCP federation where its tools are exposed | Least-privilege project scope; never pass secrets into prompts |
| GitHub MCP | Repository metadata, branches, PRs, reviews, Actions, artifacts and commit evidence | Read/write PR branch only when specifically assigned; no merge by agent |
| Linear | Work ownership, priority, dependencies and state | Task/issue updates; preserve the existing operations masterplan |
| Context7 | Version-correct library/framework documentation | Read-only |
| Figma MCP | Design files, design context, components and handoff | Design scope only |
| Nanou Browser | Browser UI validation and web automation | Staging/preview/test identities only |
| Supabase MCP | Read-only schema/project diagnostics and approved staging administration | No migration/baseline write without explicit owner approval |
| Render MCP | Staging service diagnostics, build/deploy state, logs and health checks | Free staging only; no production cutover or paid resource |
| Vercel MCP | Preview/deployment diagnostics, logs, traces and project observability | Preview/diagnostic first; no production promotion without owner approval |
| PostHog MCP | Event analytics, session/error trends and feature usage | Read-only analytics by default |
| GitHub Actions | Repeatable CI, emulator testing, security scanning and APK artifacts | CI automation; release workflow cannot bypass approval gates |
| Automations | Scheduled status reports/checks only when explicitly configured and confirmed | Report/diagnostics first; no autonomous code merge or deployment |

The personal Mada MCP is the preferred bridge. If its tool catalog is not present in a particular ChatGPT session, that is a session/tool-availability limitation, not proof that the already-used server has been uninstalled. Use direct connected GitHub/service MCP tools for their own functions; do not silently replace the personal MCP with a different desktop tool.

## 6. Task and branch model

One task = one owner = one branch/worktree = one PR = one acceptance contract.

Branch convention:

- `feat/<area>-<short-task>`
- `fix/<area>-<root-cause>`
- `test/<area>-<test-infra>`
- `docs/<topic>`

Every task must specify:

1. Exact repository and baseline commit SHA.
2. Exact files/directories the agent may edit.
3. Files explicitly out of scope.
4. Root cause and previous attempts/evidence.
5. Acceptance criteria and required tests.
6. Rollback/recovery approach.
7. Required reviewer role.

Parallel agents may work simultaneously only when their write scopes do not overlap. If two tasks require the same file, sequence them or make one owner responsible for a single combined PR. No shared working directory across coding agents.

## 7. Quality gates

| Gate | Required evidence | Failure handling |
|---|---|---|
| G0 — Baseline | Clean isolated worktree; branch, HEAD, recent history, no unrelated changes | Stop that task; protect existing work |
| G1 — Static/build | Formatting/lint where configured, TypeScript build/typecheck, Kotlin compile, APK asset contract | Fix the first real failing step; keep all gates |
| G2 — Domain/API | Ludo rules, authoritative state, auth/RBAC, validation, concurrency, idempotency and native PostgreSQL tests | No client-trusted dice/winner/balance/payout |
| G3 — Android | JVM unit tests and connected UI instrumentation on API 27 and API 30; accessibility/navigation assertions | Test-storage failure must be fixed in test harness, not hidden by dropping the device/API gate |
| G4 — Ramus / visual | Offline 2/3/4-player, turn rotation, dice/move/replay, online entry/lobby flow, GLES/PBR/GLB markers, framebuffer readback, screenshots and raw log evidence | Distinguish absent telemetry from renderer failure; do not patch renderer on missing logs alone |
| G5 — Staging | Actual Render service exists; exact URL returns `/health` and `/ready`; Supabase connection/schema/auth/CORS/rate-limits verified; payment switches remain disabled | BLOCKED until owner-controlled environment is configured; do not build/distribute APK with an unverified base URL |
| G6 — Security | Trivy, OSV, Semgrep, Gitleaks, dependency review, auth/RBAC and reverse-proxy/SSE review | No gate removal; record skipped scans as NOT RUN |
| G7 — Release | Debug/release APK, signature verification, SHA-256, artifact-to-commit correlation, physical-device install/smoke, owner acceptance | No merge, production deployment, public release or live-money enablement without owner approval |

## 8. Critical path for the current candidate

Priority order:

1. API 27 screenshot integration is now verified on the full current PR #156 head `b26f9c4`: Android UI Instrumentation run `41230261326` passed API 30 and Android 8.1/API 27 and preserved screenshots. The full Release Gate `41230261297` also passed. Keep all UI assertions; do not re-open or duplicate the earlier `grantRuntimePermission`/shared-Pictures fixes.
2. Obtain Ramus/GLES3/PBR runtime evidence against the exact current PR #156 APK and commit. The prior renderer-marker gap must be diagnosed from screenshots, APK SHA, `LudoMadaRender` logcat and guest logs before any renderer change. This connected Termux host currently has no `ramus` executable in PATH and `adb devices` lists no attached device; no local Ramus result was produced. Use a verified Ramus/cloud runner or connect the intended device; do not treat the missing local tool as a renderer failure.
3. Complete Render staging environment setup under owner control: add the exact Supabase Session Pooler `DATABASE_URL` for `ludomada_render_staging` only in Render Protected Environment, ensure `JWT_SECRET` is at least 32 characters, `TRUST_PROXY=true`, and configure `CORS_ORIGINS` compatible with the deployed code (the current `main` server requires a non-empty allowlist in production, while the candidate branch permits fail-closed empty origins for native clients). Reconcile Node runtime: `main` Dockerfile uses Node 20 but current candidate Dockerfile uses Node 24 and dependency engine requirements include Node >=22. Do not trigger another deploy until environment configuration and target branch are approved. Keep payment switches false. Do not baseline/migrate the 13-table schema until zero-diff/empty-table/owner-approval conditions are satisfied.
4. Close security/dependency items from their latest scan evidence. Independently review PR #159's Nginx/SSE behavior through a real staging proxy before any deploy/reload. Finish remaining CI action pinning only where still flagged.
5. PR #156's full backend/Android CI and API 27/API 30 UI instrumentation gates have passed on its current candidate head. Complete Ramus visual/runtime validation, bring up the protected Render/Supabase staging endpoint, run Nanou Browser preview QA and real staging-proxy SSE tests, finish the separate MobSF audit on a suitable runner, and correlate signed release artifacts with the exact reviewed commit.
6. Present the complete evidence pack for owner acceptance. Merge/release only after explicit approval.

MobSF is a separate high-priority static APK audit and must only run on a runner with verified capacity (the existing runbook records 8 GB+ RAM and 80 GB+ disk). Do not run it on the low-resource Android/Termux device or claim it passed without artifacts.

## 9. Protected operations

The following are owner-only or require explicit approval: production deployment/cutover; merge to the protected release branch; live payment switches; wallet/ledger mutations; real-money settlement; secret rotation; database migration/baseline/DDL; paid resource creation; destructive infrastructure operations; security-policy changes. Never read or emit `.env`, API keys, OAuth tokens, passwords, signing keys or protected environment values.

## 10. Completion definition

LudoMada is **release candidate ready** only when G0–G7 are supported by current evidence and all open P0/P1 tasks have a resolution or an explicitly accepted exception. It is **production ready** only after owner acceptance, actual production infrastructure validation, independent security/legal/payment approval and controlled release. A successful build, green partial CI run, screenshot alone or draft PR is not sufficient.

[executed on device: localhost (05f4ce01-13f1-42f4-b4b2-759ff1b90ea2)]