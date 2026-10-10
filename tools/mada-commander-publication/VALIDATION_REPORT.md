# Mada Commander validation report

Date: 2026-10-09. Scope: separate public MCP implementation, automated local tests, and production Vercel HTTP smoke. The production endpoint is deployed but still fails closed pending database and OAuth setup.

## Automated checks
- Node syntax checks: PASS for server, agent, API router, DB, security, tools, migration, boundary audit, tenant-isolation integration script, and unit tests.
- Boundary/security audit: **32 passed, 0 failed**.
- Public MCP automated test suite: **10 passed, 0 failed**.
- Package lock consistency: PASS; package name `mada-commander-public-mcp`, lockfile v3, one production dependency (`pg`).
- Production dependency security audit: `npm audit --omit=dev --audit-level=high` reported **0 vulnerabilities**.
- Main repository `git diff --check`: PASS.

## Public production HTTP smoke

- Vercel deployment state: **READY**; production alias: https://mada-commander-public-mcp.vercel.app.
- Home page and Privacy, Terms, Support pages: HTTP 200.
- The homepage login control remains inaccessible while health is not ready; it is disabled for pointer and keyboard activation until the service reports ready.
- Latest production deployment `dpl_FE2Dy7EVx7mHtgzf41KaNCFtgknX`: **READY**. A global Vercel header rule now applies security headers to static HTML and routed API/MCP responses. Live curl verification passed on `/`, privacy, terms, support, `/api/health`, and `/mcp` for CSP, X-Content-Type-Options, Referrer-Policy, X-Frame-Options, Permissions-Policy, Cache-Control and HSTS. Static homepage defaults say `NOT READY` and disable keyboard/pointer login activation until readiness is confirmed.
- `/.well-known/oauth-protected-resource`: HTTP 200; reports canonical resource origin `https://mada-commander-public-mcp.vercel.app`.
- `/.well-known/oauth-authorization-server`: HTTP 200; reports matching issuer, authorization/token/revocation routes, PKCE S256, and CIMD support.
- `/mcp` `initialize`: HTTP 200; server ID `mada-commander-public`, version `0.1.0`.
- `/mcp` `tools/list`: HTTP 200; exactly five bounded tools and their input/output schemas are present.
- Unauthenticated `tools/call`: HTTP 401 with JSON-RPC `Unauthorized`. No protected job executes anonymously.
- `/api/health`: HTTP 503 and `ready:false`; production reports `databaseConfigured:false`, `schemaReady:false`, `googleOAuthConfigured:false`, and `domainChallengeConfigured:false`. The canonical base URL and CIMD mode are configured.
- `/.well-known/openai-apps-challenge`: HTTP 503 / `domain_challenge_not_configured` until the exact portal-issued value is stored. This is intentional; no challenge value was fabricated.

## Local HTTP smoke
- Home page: HTTP 200, title “Mada Commander — Your workspace, your control”.
- Privacy, Terms and Support pages: HTTP 200.
- Security headers: CSP, X-Frame-Options DENY, nosniff, Referrer-Policy no-referrer, Permissions-Policy, no-store, and HSTS present.
- MCP initialize: HTTP 200; server ID `mada-commander-public`, version `0.1.0`.
- MCP tools/list: HTTP 200; exactly five tools, each with output schema and explicit annotations.
- Unauthenticated protected tool call: HTTP 401 with a `WWW-Authenticate` resource-metadata challenge.
- Protected-resource metadata: HTTP 200; canonical configured resource origin and policy/terms locations.
- Authorization-server metadata: HTTP 200; issuer, authorization/token/revocation endpoints, PKCE S256, CIMD enabled under test configuration, and issuer identification.
- Domain challenge route: returned the configured **local test token** exactly as plain text. This is not the real OpenAI-issued production token and does not constitute domain verification.
- Health: HTTP 503 and `ready:false` when dedicated database and real Google OAuth credentials are missing. That is the expected fail-closed result.

## Listing draft
- Listing metadata JSON parsed successfully; display name is Mada Commander.
- Five positive and three negative review cases are present.
- Structural draft issues: 0.
- Descriptions match the actual five tools and do not claim arbitrary build/test/remote-shell capabilities. The FR/MG translations no longer claim build/test tracking that is not implemented.
- The listing has production URLs for website, support, privacy and terms, but the privacy/terms/support content still requires legal and operator review. The developer identity and demo video remain placeholders.
- A per-tool/per-hint safety-annotation rationale matrix is documented and guarded by the 31-check audit; those explanations must still be entered/verified inside the OpenAI submission portal.
- The validator continues to list real production blockers; the listing is not ready to submit.

## Repeatable production smoke
- Added npm run smoke:production and ran it against the production domain: **12 passed, 0 failed** in fail-closed mode. It verifies homepage disabled-login defaults, static/API security headers, all policy pages, OAuth metadata JSON, health/challenge expectations, MCP initialize/tool schemas, unauthenticated denial, and Google sign-in gating.
- Run npm run smoke:production -- --expect-ready only after database/schema, Google OAuth, and domain challenge are configured.

## Provisioning blocker
- Railway refused to create a new project with "Free plan resource provision limit exceeded"; no paid upgrade was performed and no new DB was created. A separate Supabase connector can provision/test an isolated PostgreSQL DB once connected and only if the account still has a free project slot.

## End-to-end tenant isolation
- An optional PostgreSQL integration script now exists as `npm run test:tenant-isolation`. It uses uniquely tagged disposable test accounts to test account A/B agent, project and job isolation; job completion ownership; agent revocation; and account-deletion cascades.
- The script’s fail-closed preflight was tested: it refuses to run because `MADA_COMMANDER_DATABASE_URL` is absent.
- The integration itself has **not** run, because no dedicated QA Postgres database has been configured. Do not point it at the LudoMada game/wallet database or a production account database.

## Not verified / hard blockers
- A public Vercel production deployment now exists at https://mada-commander-public-mcp.vercel.app; it is reachable but not ready for protected operations or OpenAI review.
- No dedicated QA/production database or Google OAuth client credentials are configured.
- Real Google sign-in, OAuth code exchange, refresh-token rotation and revocation have not been exercised end-to-end against a database.
- Two-account database isolation has not yet run against a provisioned test DB.
- No portal-issued production domain challenge is configured or verified.
- The product/support/privacy/terms URLs now point to the deployed HTTPS origin, but the privacy/terms/support pages are still draft copy requiring operator/legal review; verified publisher identity and demo video are still missing.
- The review cases have not run against a public reviewer account.
- No OpenAI tool scan, review submission, approval, or publication has occurred.

## Interpretation
The public service code is separate from the private 44-tool Commander and exposes only five bounded diagnostics/control-summary tools. Local tests establish protocol and configuration behavior; they do not establish production readiness. Keep the private Termux MCP/tunnel separate until database, Google OAuth, end-to-end tenant isolation, HTTPS deployment, domain verification and OpenAI review prerequisites pass.