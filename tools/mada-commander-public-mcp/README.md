# Mada Commander — Public MCP (separate service)

**State: deployed at https://mada-commander-public-mcp.vercel.app, but NOT READY for protected use or OpenAI review.** The live health endpoint intentionally returns HTTP 503 until the dedicated database/schema, Google OAuth credentials, and OpenAI-issued challenge token are configured.

This is a new, least-privilege service separate from `tools/personal-mcp-commander`. Never point it at the private Commander's database, tunnel key, local HTTP token, Google session, browser state, or the LudoMada game database.

## Public feature boundary

The MCP advertises five tools:
- `mada_agent_status`: inspect the signed-in user's own paired agents
- `mada_list_projects`: list that account's registered project aliases and short Git summaries
- `mada_run_diagnostics`: queue one fixed `git_status`, `toolchain`, or `inventory` diagnostic
- `mada_job_status`: view a job owned by the authenticated account
- `mada_connection_help`: explain pairing/revocation and list that user's agents

It does **not** expose arbitrary shell, arbitrary file read/write, local browser/CDP, APK installation, GitHub merge/review, or user-supplied command steps. The local agent receives only an allowlisted profile and project alias; it resolves both against its own private local configuration and never accepts a filesystem path or command from the cloud.

## Run local structural/protocol tests

Use Node.js 20 or newer. The verification command runs a public-boundary audit and the tests; neither needs live database or Google OAuth credentials:
```sh
npm run verify
node --check api/router.mjs
node --check agent.mjs

Run a live production smoke check (no secrets are printed):

    npm run smoke:production

This verifies the current fail-closed deployment. After a dedicated database, schema, Google OAuth credentials, and the exact OpenAI domain challenge are configured, run npm run smoke:production -- --expect-ready to check the ready state.
```

The boundary audit checks the fixed tool allowlist and output schemas, local agent shell boundary, database tenant constraint, OAuth PKCE/resource checks, Vercel routing and global security headers (including static HTML), listing review-case counts, annotation-rationale coverage, and absence of the private Commander runtime/real secret files.

After creating and migrating a **separate isolated test database**, the optional end-to-end tenant test creates two uniquely tagged test accounts and verifies cross-account agent/project/job isolation, agent token revocation, job completion ownership, and account-deletion cascades:
```sh
MADA_COMMANDER_CONFIRM_TENANT_TEST=YES npm run test:tenant-isolation
```
It refuses to run without the explicit confirmation and database configuration. Never run it against the LudoMada game/wallet database or a production account database; use an isolated Mada Commander QA database. It creates and deletes its own uniquely tagged test rows only.

For a local protocol smoke server (initialize/tools/list and static pages), set a local-only origin and run:
```sh
MADA_PUBLIC_BASE_URL=http://127.0.0.1:8789 NODE_ENV=development npm start
```
The protected tools remain unavailable until a dedicated database and OAuth configuration are provisioned. This local development mode is not a public deployment.

## Dedicated production resources required

1. Create a **new, dedicated Postgres database** for Mada Commander; do not reuse the game/wallet database or the private Commander config database. Configure `MADA_COMMANDER_DATABASE_URL` in the deployment's secret manager.
2. Create a Google OAuth Web application. Register the exact callback `https://YOUR_PUBLIC_ORIGIN/oauth/google/callback` and configure `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` as server secrets.
3. The package is now deployed in the **new Vercel project** `mada-commander-public-mcp` at `https://mada-commander-public-mcp.vercel.app`; it does not overwrite `ludomada-commander-control`. The project remains fail-closed; retain the current production alias and redeploy after changing code or production environment variables.
4. Set `MADA_PUBLIC_BASE_URL` to the exact production HTTPS origin (no path). Set `MADA_OAUTH_CIMD_ENABLED=true` to support only the fixed OpenAI client metadata URL shapes implemented here, or use an explicit `MADA_OAUTH_CLIENT_IDS` plus `MADA_OAUTH_REDIRECT_URIS` allowlist. Static OAuth mode must list only exact redirect URIs from the OpenAI management portal.
5. After creating a **dedicated QA database**, run the schema once from a trusted shell after reviewing it. Run the identical schema against production only after the QA isolation test passes:
```sh
MADA_COMMANDER_DATABASE_URL='(set privately)' MADA_COMMANDER_CONFIRM_SCHEMA=YES npm run migrate
```
The example above is a placeholder only; never paste secrets in chat or commit them. Run migrations only against the dedicated database.
6. In the OpenAI submission portal, copy the exact generated domain-challenge token into `OPENAI_APPS_CHALLENGE_TOKEN`. The endpoint `/.well-known/openai-apps-challenge` returns that token as plain text. Do not invent a token.
7. Complete the Google OAuth test, MCP initialize/list/call tests, per-user isolation checks, rate-limit and token-revocation checks on the public HTTPS URL. Confirm `/api/health` returns ready before the OpenAI tool scan.
8. Publish the real HTTPS product, support, privacy and terms pages. The current pages are drafts for operator/legal review; confirm the publisher identity and contact method first.

## Required environment variables

- `MADA_PUBLIC_BASE_URL`: exact public HTTPS origin
- `MADA_COMMANDER_DATABASE_URL`: dedicated Postgres connection string
- `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`: server-side Google OAuth credentials
- `MADA_OAUTH_CIMD_ENABLED=true` **or** `MADA_OAUTH_CLIENT_IDS` and `MADA_OAUTH_REDIRECT_URIS`
- `OPENAI_APPS_CHALLENGE_TOKEN`: exact portal-generated token
- `NODE_ENV=production`
- optional `MADA_CORS_ORIGINS`: comma-separated explicit origins

Never define values for the private runtime tunnel key in this project's environment. The public service has its own authentication and database.

## Persistent authorization and account management

The account dashboard at https://mada-commander-public-mcp.vercel.app manages both paired local agents and connected OAuth applications. Under **Connected applications**, the owner can revoke a client's authorization; revocation deletes both that client's refresh tokens and current access tokens immediately.

OAuth access tokens remain short-lived (15 minutes). Refresh grants do not expire automatically: they rotate during refresh and remain valid until the owner revokes the application, signs out only from the website (which does not revoke the MCP connection), or deletes the Mada Commander account. They are stored as hashes, never as plaintext. Existing refresh grants are migrated to this persistent policy.

Dashboard sessions remain active until explicit sign-out/account deletion; the browser cookie is renewed as the dashboard is used. The browser itself can still clear stored cookies.

Only the saved authorization is persistent. The initial OAuth handshake remains time-bounded (the authorization request is valid for 60 minutes, and the Google sign-in state is separately bounded) and the consent nonce is one-time/session-bound. Those temporary values must not be made permanent: they protect against replay and account takeover.

## Local agent pairing

After the public origin and Google sign-in/database work:
```sh
node tools/mada-commander-public-mcp/agent.mjs pair "https://YOUR_PUBLIC_ORIGIN" "ONE_TIME_CODE_FROM_THE_SITE"
node tools/mada-commander-public-mcp/agent.mjs project-add ludomada LudoMada "$HOME/LudoMada"
node tools/mada-commander-public-mcp/agent.mjs run
```
The agent token and registered absolute project paths stay on the local device in `~/.config/mada-commander/agent.json`, permissions 0600. The service receives only project alias/label, branch, short hash, and allowlisted diagnostic results. The agent polls every 15 seconds and can be stopped with Ctrl+C. Revoke the agent from the website to block further requests.

## OpenAI public submission

Review current official instructions before submission:
- https://developers.openai.com/plugins/build/mcp-server
- https://developers.openai.com/plugins/build/auth
- https://developers.openai.com/plugins/deploy/app-review
- https://developers.openai.com/plugins/deploy/submission
- https://developers.openai.com/plugins/deploy/submission-errors

Submission remains blocked until the service is actually deployed with real credentials, domain verification succeeds, the production MCP tool scan passes, the identity is verified, and the required five positive/three negative review cases and demo video are completed. This repository being ready to deploy is not the same as being published or approved.