# LudoMada Parallel Orchestration Runbook

**Status:** Operational procedure for the multi-agent architecture in `DEVELOPMENT_ORCHESTRATION_ARCHITECTURE.md`.

## 1. Before every task

The orchestrator must:

1. Confirm the repository is `pascalnanousympathie-alt/LudoMada` (not another Nanou/Mada project).
2. Record the exact base SHA, branch, PR, and latest relevant workflow run.
3. Inspect `git status`, recent commits, `PROJECT_STATUS`/release notes, open PRs and earlier attempts. Do not assume a remembered fix is still missing.
4. Confirm the task has one owner and a non-overlapping write scope.
5. Create an isolated worktree or clone from the exact SHA. Never use the owner's dirty Termux checkout as an agent workspace.
6. Provide only task-relevant repository context. Never include secrets or protected environment values.

## 2. Assignment contract

Use this prompt structure for every coding agent:

> You own task `<ID>` for `pascalnanousympathie-alt/LudoMada`, base SHA `<SHA>`, branch `<BRANCH>`. Edit only `<ALLOWLIST>`. Do not edit `<DENYLIST>`. First inspect history and prior attempts for this exact failure. State the root cause with file/line/log evidence before changing code. Make the smallest patch and add a targeted regression test. Preserve auth/RBAC, tenant isolation, authoritative gameplay, concurrency, idempotency, accessibility, payment-disabled defaults and all CI/security gates. Do not read or print secrets. Do not merge, deploy, migrate, enable payments or alter production. Run `<REQUIRED_TESTS>`. Return changed-file list, diff summary, test commands/results, remaining risks and exact commit SHA. If root cause is not established, stop at diagnosis rather than guessing.

## 3. Current work allocation

| Priority | Work item | Primary owner | Independent reviewer | Dependency / acceptance |
|---|---|---|---|---|
| P0 | Ramus / GLES3 / PBR validation on latest PR #156 head | Android visual-QA agent / Ramus runner | Independent 3D reviewer | PR #156 Release Gate and API 27/API 30 UI Instrumentation passed on head `b26f9c4`; capture exact APK SHA, gameplay screenshots, `LudoMadaRender` logs, GLB parts and framebuffer readback on that same commit |
| P0 | Ramus renderer diagnostics gap | Ramus/test-infrastructure agent | 3D renderer reviewer (read-only) | Same candidate APK/session evidence; identify whether marker capture or app rendering failed; full smoke and framebuffer evidence pass |
| P0 | Render Free + Supabase staging | Human owner for protected configuration; DevOps agent for code/docs | Free service `srv-dbj32afb9u1s73ctr880` exists; two deploy attempts failed. Latest runtime log (2026-11-01 04:24:20Z) proves `DATABASE_URL` is absent from the process (`DATABASE_URL_IS_REQUIRED` at `dist/server.js:31`); image build succeeded. Owner must set the session-pooler URL privately in Render Protected Environment; never paste it in chat. JWT secret status remains unverified. Review Node 20 vs dependency >=22 before next approved deploy; verify `/health` and `/ready`; no schema baseline/migration until owner approval; payment switches false |
| P0 | Dependency/security closure | DevOps/security agent | Independent security reviewer | Current Trivy/OSV/Semgrep/Gitleaks evidence; no unreviewed dependency major changes |
| P1 | Nginx/SSE compatibility review | DevOps agent | Backend/realtime reviewer | Confirm authenticated SSE still works through staging proxy after header change |
| P1 | Remaining immutable Actions references | CI agent | Security reviewer | Re-scan all scoped workflow files; preserve semantics and required checks |
| P1 | MobSF static APK audit | Security/QA agent | Owner/security reviewer | Only after a suitable runner's RAM/disk capacity is verified; attach JSON/PDF findings |
| P2 | UI/3D polish and release verification | Android/3D agent, then QA | Independent visual reviewer | No cosmetic work ahead of P0 correctness/staging blockers; Nanou Browser screenshots plus Ramus evidence |

## 4. Parallel execution rules

- Backend game rules and Android test storage may proceed in parallel because their write scopes are separate.
- Ramus script/logging and renderer implementation may not be edited by two agents at once. The Ramus agent owns `scripts/ramus-smoke.mjs`; the renderer agent owns Kotlin renderer sources. A contract change requires a jointly reviewed PR or sequential handoff.
- API/server/auth work and Prisma schema work must be separate tasks. Schema migrations require an explicit staging target and owner approval.
- CI workflow files have one designated CI owner per PR. Other agents may request changes but must not edit the same workflow in parallel.
- Figma/design work can run in parallel with code only while specifications are being prepared; implementation begins after the relevant design is approved and frozen.
- The reviewer is read-only and is not the implementer.
- A maximum of three focused correction attempts is allowed for one diagnostic. After that, escalate with the first failing step and evidence; do not repeat an identical run without a new hypothesis.

## 5. Required evidence in each PR

Every PR must include:

- Task ID, base SHA and purpose.
- Root cause and links to previous failed/passing runs.
- Files changed and why each was necessary.
- Test matrix with `PASS`, `FAIL`, `BLOCKED` or `NOT RUN` per gate.
- Full test command and relevant log excerpt/artifact URL.
- Screenshots for visual changes; screenshots must be associated with the tested commit/APK hash.
- Security implications and rollback notes.
- Reviewer sign-off from a role other than the implementation agent.

Never mark a task PASS because a workflow was merely dispatched. `runner_id=0` or empty steps means it did not start; inspect annotations and the first failing step before classifying a failure.

## 6. Current root-cause notes — do not regress

### Android 8.1/API 27 screenshot — fix now proven on PR #158

The earlier failure sequence was `UiAutomation.grantRuntimePermission` `NoSuchMethodError`, failed shell `pm grant`, then inability to create shared `Pictures/LudoMada`. Do not repeat these patches. PR #158 latest head `e9467b33395c0d9480c76c64e2fa99fc89dc69a4` moved capture to `context.filesDir`, retrieves the image with host-side `adb run-as` before Gradle tears down the package, validates PNG completeness, and passed the connected API 30 and API 27 runs. Evidence is in UI job `124614319105` and artifact `13052591683`.

API 27 screenshot integration is now validated on full current PR #156 head `b26f9c4b9ab7623eb325a9b46e366a149cc58685`: Android UI Instrumentation run `41230261326` passed API 30 and Android 8.1/API 27, and Release Gate `41230261297` passed. Earlier PR #161 screenshots (artifact `13053137384`) remain valid evidence for the shared three-file patch; the full current-head UI artifact is `13053567148`. No further API 27 screenshot patch is needed.

### Ramus renderer evidence

The earlier same-candidate run logged `GLES3 PBR`, six hero GLB parts, 95 board parts, scene/surface readback and complete gameplay smoke. Later runs reached the board screen but produced zero renderer log lines and failed the marker assertion. Compare screenshot, APK checksum, Ramus session and complete logcat first. If the app is visible but only the filtered log stream is empty, fix capture/diagnostics. Only modify renderer code when `GLB_RUNTIME_LOAD_FAILED`, GL errors, an incomplete framebuffer or a reproducible visual defect is present.

### Staging backend

Render lists the Free staging service `ludomada-api-staging` (`srv-dbj32afb9u1s73ctr880`, Singapore, branch `main`, auto-deploy disabled, one instance). Both deploy attempts failed at startup (`dep-dbj32b7b9u1s73ctra10` and `dep-dbjbtmvlk1mc738qefq0`). The latest app log (2026-11-01 04:24:20Z) shows `/app/dist/server.js:31` throwing `DATABASE_URL_IS_REQUIRED`; the Docker image build itself succeeded. Root cause is the process not receiving `DATABASE_URL`, not a proven bad Supabase credential. `JWT_SECRET` remains unverified; no secret was read or changed. Main-branch Dockerfile uses Node 20 while dependencies require Node >=22 (EBADENGINE warning); PR candidate Dockerfile uses Node 24. Main branch production CORS requires a non-empty allowlist. Do not trigger another deploy until the owner sets the required URL privately in Render Protected Environment and branch/runtime/health-check settings are reviewed.

## 7. Daily orchestration cycle

1. **Discover:** pull current GitHub PR/check state and Linear tasks; group by dependency and write scope.
2. **Prioritise:** unblock P0 correctness and staging before P1 security/CI cleanup; defer cosmetic work until critical gates are green.
3. **Dispatch:** assign one bounded task per agent in an isolated branch/worktree.
4. **Observe:** check agent/task IDs and logs; do not say an agent is active without a confirmed run.
5. **Review:** independent reviewer inspects diff, test coverage and security boundaries.
6. **Verify:** CI + Ramus + Nanou Browser + staging checks as appropriate to the change.
7. **Integrate:** update PR/task state only from evidence; no automatic merge.
8. **Report:** concise status showing completed work, tests/evidence, root cause, blockers/owner actions and next task.

## 8. State model

Use the following states in GitHub/Linear:

`BACKLOG → READY → ASSIGNED → IN_PROGRESS → REVIEW → TESTING → READY_FOR_OWNER → DONE`

Terminal alternatives: `BLOCKED` (must name evidence and owner action), `REJECTED` (reason and remediation), `DEFERRED` (explicit priority reason). A task cannot enter `DONE` until the PR is merged by an authorized owner or the owner explicitly records that the task is complete without merge; never infer completion from an open draft PR.

## 9. Live PR/check snapshot (2026-10-31)

- PR #156 — current 3D integration candidate, Draft/Open, head `b26f9c4b9ab7623eb325a9b46e366a149cc58685`. Release Gate `41230261297` PASS; UI Instrumentation `41230261326` PASS on API 30/API 27; V6.3 CI `41230261300` PASS; Framebuffer Contract `41230261321` PASS; Render Blueprint Safety `41230261344` PASS; Security Audit `41230261734` PASS for Gitleaks, Trivy, Semgrep and OSV PR Scan (OSV Full Scan skipped). APK artifacts `13053576957`; UI screenshot artifact `13053567148`. Ramus visual/runtime evidence still required for this exact head.
- PR #157 — earlier API 27 storage-permission attempt; its run failed. Do not merge or repeat it.
- PR #158 — source of the validated UI screenshot fix, Draft/Open, head `e9467b33395c0d9480c76c64e2fa99fc89dc69a4`; API 30 and API 27 instrumentation plus both PNG captures passed (run `41227405095`, artifact `13052591683`). The fix has been ported to PR #156's exact head in PR #161.
- PR #159 — Nginx/SSE proxy fix, Draft/Open, head `03aaf92504b17b58b18c602d44c0a5726d2974a0`; backend, Android and listed security checks passed, while OSV Full Scan was skipped. Static review confirms HTTP/1.1 upstream and no Upgrade forwarding, while the backend keeps SSE unbuffered with a 25-second heartbeat. Real staging-proxy SSE verification is still required; no production Nginx reload is implied.
- PR #161 — API 27 screenshot integration on predecessor PR #156 parent `225c0a`, Draft/Open, head `b599b7de4447d017332d86a5735504e8dbd2375f`; its UI run `41229249488` passed API 30/API 27, screenshots artifact `13053137384`. The three changed files are already identical in current PR #156 and the full PR #156 head's own UI run has since passed. Keep PR #161 as validation history; do not merge the stale stack separately.
- PR #162 — authenticated 10k RPS capacity test, Draft/Open, current head `850fa7a6aee9987e523f90e11b06e51bb3741ab3`. Seventh run `41274362018` (guarded telemetry enabled): 2,306 aggregate RPS; 113,196/300,334 VUs failed (37.68%); response p95 278.7 ms / p99 3,534.1 ms; TCP p95 7 ms / p99 11,274.1 ms; 106,647 `ERR_SOCKET_TIMEOUT`, 3,381 `ECONNRESET`, 3,168 `EADDRNOTAVAIL`. API telemetry: 90–94% of one core during load; event-loop delay max p95 248 ms / p99 362 ms; RSS max 170.8 MiB. Cache hit 98.35%; SQL execution 0.25 ms; DB pool waiting returned to zero after warm-up. Prior eighth? no—sixth run `41274004862` reached 3,029 RPS and failed; fifth `41272731448` reached 2,663 RPS; fourth `41272226027` reached 4,018 RPS. All use 8,192 sockets after the fourth? The 4,018 result used 4,096 sockets. The CI job co-locates Artillery, API and PostgreSQL on one 4-vCPU host. Fixture is 1,000 two-player games and tests state reads only, not four-player/mixed/write capacity. Code review confirms capacity guard is restricted to disposable loopback CI and participant authorization remains active. **NOT CERTIFIED**; next step is guarded Node CPU profiling, then independent generator against approved staging. V6.3 CI/Security checks pass on this head; OSV Full Scan skipped. No deployment.

These are separate branches/PRs. Passing checks apply only to their exact head SHA; do not assume they have been merged into one release candidate.

## 10. Release commandment

No merge, production deploy, production DB migration, wallet mutation, payment switch, real-money test, or public distribution is performed by an agent without explicit owner approval. The release operator must correlate the final APK with the exact reviewed commit, verify signing and SHA-256, install it on supported devices, and preserve the final evidence pack.

[executed on device: localhost (05f4ce01-13f1-42f4-b4b2-759ff1b90ea2)]