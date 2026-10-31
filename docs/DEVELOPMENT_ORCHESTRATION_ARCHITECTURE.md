# LudoMada Multi-Agent Development Architecture

**Version:** 1.0
**Prepared:** 2026-10-31
**Status:** Architecture specification and rollout plan; not a claim that every agent connector is already installed or running.

## 1. Objective

Deliver LudoMada through one controlled engineering pipeline in which specialist coding agents can work in parallel without editing the same files, duplicating old fixes, bypassing security controls, or treating a successful APK build as proof that the whole game is release-ready.

The architecture is based on the current repository layout and CI workflows. GitHub is the source of truth for source, branches, commits, reviews, checks and artifacts. A task is complete only when its code, tests and evidence are all attached to a reviewed pull request.

## 2. Verified baseline and current constraints

The current PR #156 candidate branch is `fix/3d-apk-runtime-asset-current-head-20261030` at head `87af023f44ca01261b7e77c8276a329d8667fe8c` (checked 2026-10-31 18:31Z). Its existing code/architecture map is `docs/ARCHITECTURE_IMPLEMENTATION_STATUS.md`; this document adds the multi-agent operating model. Earlier visual/Ramus evidence on predecessor SHA `2b513aa` must not be attributed to the current head. This remains a Draft/Open candidate, not an approved release or merge target.

Verified repository capabilities include:

- Backend: TypeScript/Express, Prisma/PostgreSQL, authoritative Ludo rules, server-generated dice, durable game state, command idempotency, version checks, game event storage and realtime event delivery.
- Android: native Kotlin/Java application, offline rules, online API integration, OpenGL ES renderer, runtime GLB asset loading, material/lighting/motion code and localized strings.
- QA: Jest/native Node tests, PostgreSQL-backed native tests, Android unit/instrumentation suites, Ramus game smoke, framebuffer/readback assertions, APK asset checks and release signing checks.
- CI: Android APK, emulator, UI instrumentation, framebuffer contract, backend CI, release gate, security audit and Render blueprint validation workflows.

Current evidence that must shape prioritisation:

1. The release-gate and Ramus success evidence applies to predecessor SHA `2b513aa` only. The current PR #156 head `225c0a` has a newer Release Gate run in which `android-final`, backend staging, unit tests and framebuffer-contract passed, but the UI instrumentation matrix failed on API 27; do not transfer predecessor PASS status to the current head.
2. On predecessor SHA `2b513aa`, an Android emulator/Ramus run passed the 3D GLES3/PBR renderer assertions, loaded six hero parts and 95 board parts, passed framebuffer readback, and passed the listed offline/2–4-player flows. That is valid evidence for that SHA only; run Ramus again against current head `225c0a` before treating the latest candidate as visually verified.
3. Later Ramus runs failed to observe renderer log markers (`rendererLines=0`, missing PBR/GLB/readback markers) after the board screen was reached. This is a telemetry/runtime-evidence failure until screenshots, app markers and guest logs prove an actual renderer defect; do not rewrite 3D code on that evidence alone.
4. PR #156's original API 27 failure was `UiAutomation.grantRuntimePermission(...)` at `StartupUiTest.kt:141`; that method is unavailable on API 27. The validated app-private screenshot fix is now present in the current PR #156 branch: all three affected files have exactly the same Git blob SHAs as the tested PR #161 patch (`103323be` script, `ffc91ddd` workflow, `35eeee32` instrumentation test). PR #161 UI run `41229249488` completed successfully on head `b599b7de4447d017332d86a5735504e8dbd2375f`: API 30 screenshot 718,120 bytes and API 27 screenshot 1,261,248 bytes; both 1080×1920, valid PNGs; artifact `13053137384`. PR #161 is based on earlier PR #156 head `225c0a`, so this validates the exact three shared test files but is not a substitute for CI on the full latest candidate. A new UI run on current PR #156 head `87af023f44ca01261b7e77c8276a329d8667fe8c` is queued (`41229878304`).
5. Render currently lists `nanou-browser` and `mega-project-apps`, but no LudoMada API service. The existing Supabase staging project is `ACTIVE_HEALTHY`, but its 13 application tables are empty and the staging service/DB baseline is not complete. The planned Render URL is not healthy until the owner provisions it and `/health` and `/ready` pass.
6. Earlier PR #156 head `225c0a` had backend/Android/framebuffer/security checks pass but UI instrumentation failed API 27. The fix is now byte-for-byte present in the current PR #156 head `87af023f44ca01261b7e77c8276a329d8667fe8c`; new UI run `41229878304` is queued, Release Gate `41229878333` and V6.3 `41229878311` are pending, Framebuffer Contract `41229878305` is in progress, Security Audit `41229878621` is in progress. Earlier run `41229699352` was cancelled before the full release gate completed; do not count its partial backend work as PASS. PR #161's separate UI run passed both API levels but is based on older parent SHA. PR #159's backend/Android/security checks passed, but the Nginx/SSE change still needs independent review and actual staging-proxy SSE test. MobSF has not been run. Do not describe skipped/not-run scans as passing.
7. The user's existing Termux `~/LudoMada` checkout is dirty and on `feat/gameplay-cosmetics-v1`; it contains modified and untracked files. It is a protected workspace. Do not pull, reset, clean, stash, rebase or edit it as part of this architecture rollout.
8. The implementation-status document records additional architecture boundaries: SSE fanout is process-local and does not replay missed events; multiple API instances need a shared fanout/reconnect design. RLS is disabled on the 13 Supabase application tables; current anonymous/authenticated grants are restricted and the API uses a least-privilege database role, but reviewed RLS policies remain defense-in-depth work. Do not enable RLS blindly because the API uses direct PostgreSQL access. The existing staging schema is empty, without `_prisma_migrations`; no blind migrate-deploy is allowed. Real-money/legal/payment approval remains separate from APK readiness.

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

1. The app-private screenshot patch is present identically in current PR #156 head `87af023`. PR #161's separate UI matrix passed API 30 and API 27 with complete screenshots, but is based on predecessor head `225c0a`; verify the new UI run `41229878304` on the current full PR #156 head before accepting the candidate. Do not weaken assertions or merge automatically.
2. Diagnose the latest Ramus renderer-marker gap against the earlier successful same-commit run. Compare exact APK SHA, Ramus session, app start, screenshot, `LudoMadaRender` logcat and shared debug markers. Add diagnostic observability only; change renderer code only after a concrete runtime defect is reproduced.
3. Complete owner-controlled Render Free staging provisioning and exact Supabase pooler configuration under the existing staging runbook. Keep all payment switches false. Do not baseline/migrate the existing 13-table schema until the documented zero-diff/empty-table/owner-approval conditions are satisfied.
4. Close security/dependency items from their latest scan evidence. Independently review PR #159's Nginx/SSE behavior through a real staging proxy before any deploy/reload. Finish remaining CI action pinning only where still flagged.
5. Run full backend and Android integration gates, Nanou Browser preview QA, Ramus, API 27/API 30 instrumentation, then build/sign/verify release artifacts.
6. Present the complete evidence pack for owner acceptance. Merge/release only after explicit approval.

MobSF is a separate high-priority static APK audit and must only run on a runner with verified capacity (the existing runbook records 8 GB+ RAM and 80 GB+ disk). Do not run it on the low-resource Android/Termux device or claim it passed without artifacts.

## 9. Protected operations

The following are owner-only or require explicit approval: production deployment/cutover; merge to the protected release branch; live payment switches; wallet/ledger mutations; real-money settlement; secret rotation; database migration/baseline/DDL; paid resource creation; destructive infrastructure operations; security-policy changes. Never read or emit `.env`, API keys, OAuth tokens, passwords, signing keys or protected environment values.

## 10. Completion definition

LudoMada is **release candidate ready** only when G0–G7 are supported by current evidence and all open P0/P1 tasks have a resolution or an explicitly accepted exception. It is **production ready** only after owner acceptance, actual production infrastructure validation, independent security/legal/payment approval and controlled release. A successful build, green partial CI run, screenshot alone or draft PR is not sufficient.

[executed on device: localhost (05f4ce01-13f1-42f4-b4b2-759ff1b90ea2)]