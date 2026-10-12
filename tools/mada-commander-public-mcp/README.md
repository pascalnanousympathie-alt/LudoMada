# Mada Commander — Public MCP (separate service)

**State: deployed at https://mada-commander-public-mcp.vercel.app.** The live health endpoint reports `ready: true` and `schemaReady: true` after the dedicated database/schema and Google OAuth setup. OpenAI review is still **not ready** until the exact portal-issued `OPENAI_APPS_CHALLENGE_TOKEN` is configured and the public MCP tool scan, OAuth flow, and owner/device pairing are verified end to end.

This is a new, least-privilege service separate from `tools/personal-mcp-commander`. Never point it at the private Commander's database, tunnel key, local HTTP token, Google session, browser state, or the LudoMada game database.

## Public feature boundary

The MCP advertises sixteen OAuth-scoped tools:
- `mada_agent_status`: inspect the signed-in user's own paired agents
- `mada_list_projects`: list that account's registered project aliases and short Git summaries
- `mada_run_diagnostics`: queue fixed Git/toolchain/inventory diagnostics
- `mada_job_status`: view a job owned by the authenticated account
- `mada_connection_help`: explain local pairing/revocation
- `mada_workspace_list` and `mada_workspace_read`: inspect a registered workspace's non-secret source tree and text files
- `mada_workspace_write`: create or replace a recognised source/text file using a project-relative path
- `mada_run_task`: run one fixed Git, npm test/lint/build, or Android Gradle test/build task
- `mada_opera_status` and `mada_opera_tabs`: inspect the paired Termux/noVNC Opera instance without returning cookies or debugging sockets
- `mada_opera_navigate`: navigate a selected tab to HTTP(S) or approved Opera settings/about pages
- `mada_opera_action`: inspect visible controls and make bounded non-secret setting changes through a fixed action allowlist
- `mada_mcp_integrations`: inspect the paired device's owner-managed local MCP registry without returning secrets
- `mada_mcp_tools`: discover only the exact MCP tool names in the local read-only allowlist
- `mada_mcp_call`: invoke one locally allowlisted tool; write-capable tools remain hidden unless the owner deliberately enables them


It still does **not** expose free-form shell commands, arbitrary filesystem paths, passwords, raw CDP sockets, arbitrary JavaScript, APK installation, or GitHub merge/review. Source changes are constrained to registered project-relative text files; tasks are fixed allowlisted invocations; Opera commands target only the paired agent's loopback CDP port. File writes, navigation, and UI actions can change local state and should be followed by tests and Git review.

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
The agent token and registered absolute project paths stay on the local device in `~/.config/mada-commander/agent.json`, permissions 0600. The service receives only project alias/label and short Git metadata during heartbeat; job payloads contain bounded, project-relative file requests, fixed task names, or structured Opera actions. Local source contents are returned only for explicit read requests, redacted and size-limited; write operations are recorded as queued jobs and produce a SHA-256 result. Opera control connects only to loopback CDP port 9223, never uploads cookies or debugging socket URLs, and excludes password/account identity fields from UI interaction. The agent polls every 15 seconds and can be stopped with Ctrl+C. Revoke the agent from the website to block further requests.


## Optional MCP server integrations (local agent)

The public server does not download or run third-party MCP server packages inside Vercel. Instead, the paired local agent can bridge owner-configured MCP servers over **STDIO** or **HTTPS Streamable HTTP**. Third-party credentials stay in the local agent process, sourced from named environment variables; the cloud service receives a server alias, an allowlisted tool name, bounded arguments and the resulting sanitized output.

Initialize the local integration registry once:
```sh
node tools/mada-commander-public-mcp/agent.mjs integration-init
node tools/mada-commander-public-mcp/agent.mjs integration-list
```
This creates `~/.config/mada-commander/mcp-integrations.json` with owner-only file permissions. All template integrations start disabled and all write-capable tools are off. Review the upstream server, install it locally, replace only placeholders, set the required credentials in the agent's local environment, and explicitly enable an integration after verifying its tool names. Keep the config at mode `0600`; the agent refuses a group/world-readable file.

The template includes:
- **GitHub MCP:** the official hosted HTTPS endpoint `https://api.githubcopilot.com/mcp/`, with a token sourced from a local environment variable. The local bridge exposes only six exact read-only tools (`get_file_contents`, `search_code`, `search_repositories`, `list_commits`, `list_pull_requests`, `issue_read`); every other remote tool is blocked by the local allowlist. No separate binary or Docker installation is required. This HTTP configuration was verified from Termux using the current GitHub CLI token; the token itself is never written into the config.
- **Playwright MCP:** pinned to `@playwright/mcp@0.0.83` with a separate isolated/headless browser. It does **not** attach to the existing Opera noVNC profile and may need a supported browser runtime; leave disabled on Android/Termux unless it passes a local smoke test.
- **Supabase MCP and Vercel MCP:** remote HTTPS placeholders, disabled by default. This bridge supports bearer credentials from a local environment variable, not an interactive OAuth consent flow. Do not enable these entries unless the provider explicitly supports the configured bearer-token method, the project is correctly scoped, and a non-mutating tool list has been verified.

Only names present in the local `readOnlyTools` list are exposed by default. Adding a name to `allowedTools` alone does not enable it while `writeEnabled` is false. Setting `writeEnabled: true` permits the full local `allowedTools` list, including any mutations, and should be a deliberate operator decision. External tool descriptions and results are untrusted data; the bridge limits message sizes, sanitizes returned text, hides image/binary content, rejects arbitrary remote commands, and never accepts a server URL or executable path from a remote tool call. The remote MCP server may still act on external accounts using the local token's permissions, so use least-privilege credentials.

The bridge and its configuration are source changes on a separate branch until reviewed and merged. The deployed production endpoint does not gain these tools merely because a branch exists.

## OpenAI public submission

Review current official instructions before submission:
- https://developers.openai.com/plugins/build/mcp-server
- https://developers.openai.com/plugins/build/auth
- https://developers.openai.com/plugins/deploy/app-review
- https://developers.openai.com/plugins/deploy/submission
- https://developers.openai.com/plugins/deploy/submission-errors

Submission remains blocked until the service is actually deployed with real credentials, domain verification succeeds, the production MCP tool scan passes, the identity is verified, and the required five positive/three negative review cases and demo video are completed. This repository being ready to deploy is not the same as being published or approved.