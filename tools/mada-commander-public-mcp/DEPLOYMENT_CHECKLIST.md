# Mada Commander deployment checklist

This checklist is deliberately fail-closed. Do not route production requests to the private Termux Commander.

## A. Create the separate service
- [ ] Push/review the current public-MCP directory on a dedicated branch. Never include private tunnel runtime keys, local config files, real `.env` files, or credentials.
- [ ] Create a new Vercel project with Root Directory `tools/mada-commander-public-mcp`. Do not overwrite `ludomada-commander-control`, which is only a diagnostic/control landing page.
- [ ] Use Node.js 20+ and the committed package lock.
- [ ] Create a dedicated Postgres database for this public service. Do not use the LudoMada game/wallet database.
- [ ] Set all environment variables in the deployment platform's secret manager only.

## B. Configure Google and MCP OAuth
- [ ] Google OAuth Web Client callback exactly matches `https://YOUR_PUBLIC_ORIGIN/oauth/google/callback`.
- [ ] Put the Google client secret only in the deployment secret manager.
- [ ] Set `MADA_OAUTH_CIMD_ENABLED=true` or explicitly allowlist the exact OAuth client ID and redirect URI shown in the OpenAI portal.
- [ ] Confirm the authorized resource is the canonical public origin, while the MCP transport is `/mcp`.
- [ ] Confirm discovery advertises the correct issuer, canonical resource, authorization/token/revocation endpoints, and PKCE S256.
- [ ] Test one-time authorization codes, expiry, invalid client/redirect, PKCE mismatch, resource mismatch, rotating refresh tokens, and revocation.

## C. Prepare the dedicated database
- [ ] Review `schema.sql`, including the agent/user composite foreign key which protects job ownership.
- [ ] Confirm the database connection is the new Mada Commander database, not the game DB.
- [ ] Run `MADA_COMMANDER_CONFIRM_SCHEMA=YES npm run migrate` in a trusted shell with credentials set privately.
- [ ] Verify all tables exist and `/api/health` reports ready.
- [ ] Confirm expired token/state/session records and old jobs are cleaned.

## D. Verify tenant isolation and local-agent boundaries
- [ ] Run `MADA_COMMANDER_CONFIRM_TENANT_TEST=YES npm run test:tenant-isolation` against a dedicated disposable Postgres QA database; the script proves same-alias project separation, job ownership, revocation and account-deletion cascades.
- [ ] Separately complete the full browser Google OAuth flow with real test account A and B; the automated DB test intentionally does not pretend to validate real Google login.
- [ ] Sign in with test account A, pair agent A, add two project aliases, and queue a diagnostic.
- [ ] Sign in with test account B; confirm it cannot list, queue, inspect or revoke A's agents, projects or jobs.
- [ ] Probe with A's agent ID/job ID using B's OAuth token; expect no data and no ownership disclosure.
- [ ] Revoke A's agent; confirm heartbeat/poll/complete are rejected and the local agent stops.
- [ ] Delete A's public-service account; confirm sessions, agents, OAuth tokens, aliases and jobs are removed by cascades.
- [ ] Send extra command/path/script/delete input fields; verify they are rejected and no arbitrary command runs locally.
- [ ] Confirm private paths, raw tokens, pairing codes, cookies, filenames and full logs do not appear in API output or server logs.
- [ ] Test rate limits, cross-origin POST checks, PKCE, unsupported profile rejection, result size bounds, CSP and secret redaction.

## E. Verify deployment and submit
- [ ] Run `npm run verify` locally and resolve every failure.
- [ ] Deploy the directory to a new stable public HTTPS origin; do not use a temporary tunnel as the public MCP endpoint.
- [ ] In the OpenAI portal, generate the domain challenge token, place it in `OPENAI_APPS_CHALLENGE_TOKEN`, and verify that the route returns only the exact token as plain text.
- [ ] Add the production endpoint ending in `/mcp`, complete OAuth, run the current tool scan, and resolve all blocking findings.
- [ ] Confirm the live HTTPS homepage, support, privacy and terms pages; finalize legal/contact details.
- [ ] Actually execute all five positive and three negative review cases with two separate test accounts.
- [ ] Record a reviewer-accessible demo video and provide any reviewer account credentials privately through the portal.
- [ ] Verify the individual/business developer identity, complete the required attestations, submit for review, and publish only after approval.

**Current completion state:** local implementation and automated tests only. No production database, Google OAuth credentials, production deployment, domain challenge, public tool scan, reviewer account/video, submission or approval has been confirmed.
