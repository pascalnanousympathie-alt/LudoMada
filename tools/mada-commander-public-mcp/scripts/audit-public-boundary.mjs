#!/usr/bin/env node
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { PUBLIC_TOOLS } from "../lib/tools.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const REPO = path.resolve(ROOT, "../..");
const read = relative => fs.readFile(path.join(ROOT, relative), "utf8");
const failures = [];
const passes = [];
function check(label, condition) {
  if (condition) passes.push(label);
  else failures.push(label);
}

const packageJson = JSON.parse(await read("package.json"));
const vercel = JSON.parse(await read("vercel.json"));
const schema = await read("schema.sql");
const security = await read("lib/security.mjs");
const router = await read("api/router.mjs");
const agent = await read("agent.mjs");
const readme = await read("README.md");
const annotationNotes = await read("PUBLIC_TOOL_ANNOTATIONS.md");
const environmentExample = await read(".env.example");
const tenantTest = await read("scripts/test-tenant-isolation.mjs");
const manifest = JSON.parse(await fs.readFile(path.join(REPO, "tools/mada-commander-publication/listing-metadata.draft.json"), "utf8"));
const operations = await read("lib/agent-operations.mjs");
const mcpIntegrations = await read("lib/mcp-integrations.mjs");
const integrationExample = JSON.parse(await read("mcp-integrations.example.json"));
const integrationTests = await read("tests/mcp-integrations.test.mjs");
const publicCode = [router, security, agent, operations, mcpIntegrations, await read("lib/db.mjs"), await read("lib/tools.mjs")].join("\n");

check("package is isolated under its own package name", packageJson.name === "mada-commander-public-mcp");
check("Vercel deployment has a default Node handler", /export default async function vercelHandler/.test(router));
check("Vercel packages runtime-read HTML assets and allows OAuth callback duration", vercel.functions?.["api/router.mjs"]?.includeFiles === "*.html" && vercel.functions?.["api/router.mjs"]?.maxDuration >= 30);
check("public service package includes a lockfile", await fs.stat(path.join(ROOT, "package-lock.json")).then(s => s.isFile()).catch(() => false));
check("repeatable production smoke separates runtime readiness from domain publication challenge", packageJson.scripts["smoke:production"] === "node scripts/smoke-production.mjs" && /--expect-ready/.test(await read("scripts/smoke-production.mjs")) && /--expect-domain-challenge/.test(await read("scripts/smoke-production.mjs")) && /domainChallengeConfigured/.test(await read("scripts/smoke-production.mjs")));
check("static and dynamic routes receive baseline security headers from Vercel", Array.isArray(vercel.headers) && vercel.headers.some(rule => rule.source === "/(.*)" && ["Content-Security-Policy","X-Content-Type-Options","X-Frame-Options","Referrer-Policy","Permissions-Policy","Cache-Control"].every(key => rule.headers?.some(h => h.key === key))));
check("production base URL requires HTTPS", /NODE_ENV === "production" && url\.protocol !== "https:/.test(security));
check("project paths are not accepted in MCP tool inputs", PUBLIC_TOOLS.every(t => !Object.keys(t.inputSchema.properties || {}).some(k => /(^|_)(path|command|script|token|secret|cookie)($|_)/i.test(k))));
check("exactly sixteen scoped public tools are advertised", PUBLIC_TOOLS.length === 16 && PUBLIC_TOOLS.every(t => t.securitySchemes?.some(s => s.type === "oauth2")));
check("all public tools expose an output schema and explicit annotations", PUBLIC_TOOLS.every(t => t.outputSchema?.type === "object" && typeof t.annotations?.readOnlyHint === "boolean" && typeof t.annotations?.openWorldHint === "boolean" && typeof t.annotations?.destructiveHint === "boolean"));
check("local MCP integration bridge supports stdio and HTTPS transports without shell execution", mcpIntegrations.includes('transport !== "stdio" && server.transport !== "http"') && mcpIntegrations.includes("shell: false") && mcpIntegrations.includes('redirect: "error"'));
check("MCP tools are deny-by-default and write tools require explicit local opt-in", mcpIntegrations.includes("server.readOnlyTools") && mcpIntegrations.includes("server.writeEnabled ? server.allowedTools : server.readOnlyTools") && mcpIntegrations.includes("not explicitly allowlisted"));
check("MCP credentials are mapped by environment-variable names and never returned in integration status", mcpIntegrations.includes("requiredEnvNames") && mcpIntegrations.includes("missingEnvironmentVariables") && !/gh[pousr]_[A-Za-z0-9_]{20,}|sb_secret_[A-Za-z0-9_-]{20,}/.test(await read("mcp-integrations.example.json")));
check("MCP example integrations are all disabled, write-disabled, and contain only placeholders", integrationExample.version === 1 && integrationExample.servers.length >= 3 && integrationExample.servers.every(s => s.enabled === false && s.writeEnabled === false) && !/REPLACE_WITH_REAL|gh[pousr]_[A-Za-z0-9_]{20,}|sb_secret_[A-Za-z0-9_-]{20,}/.test(JSON.stringify(integrationExample)));
const githubMcpExample = integrationExample.servers.find(s => s.id === "github");
const githubReadOnlyAllowlist = ["get_file_contents", "search_code", "search_repositories", "list_commits", "list_pull_requests", "issue_read", "actions_get", "actions_list", "get_job_logs"];
check("GitHub MCP template uses the hosted HTTPS endpoint and a closed nine-tool read-only allowlist with the Actions toolset",
  githubMcpExample?.transport === "http" &&
  githubMcpExample.url === "https://api.githubcopilot.com/mcp/" &&
  githubMcpExample.authEnv === "GITHUB_PERSONAL_ACCESS_TOKEN" &&
  githubMcpExample.requestHeaders?.["X-MCP-Toolsets"] === "repos,issues,pull_requests,actions" &&
  githubMcpExample.enabled === false && githubMcpExample.writeEnabled === false &&
  JSON.stringify(githubMcpExample.allowedTools) === JSON.stringify(githubReadOnlyAllowlist) &&
  JSON.stringify(githubMcpExample.readOnlyTools) === JSON.stringify(githubReadOnlyAllowlist) &&
  !("command" in githubMcpExample));
check("HTTP MCP request headers are limited to a validated static GitHub toolset hint", mcpIntegrations.includes("validateHttpRequestHeaders") && mcpIntegrations.includes("only support the X-MCP-Toolsets header") && mcpIntegrations.includes("headers[name.toLowerCase()] = value"));
check("MCP integration tests cover stdio, HTTP session negotiation, allowlist denial, and config permissions", integrationTests.includes("stdio bridge") && integrationTests.includes("HTTP bridge") && integrationTests.includes("allowlist") && integrationTests.includes("world-readable config"));
check("paired agent admits only fixed MCP bridge operation types", agent.includes('"mcp_integrations"') && agent.includes('"mcp_tools"') && agent.includes('"mcp_call"') && agent.includes("mcpIntegrationCall"));
check("OpenAI submission rationale documents every tool and each safety annotation", PUBLIC_TOOLS.every(t => annotationNotes.includes("`" + t.name + "`")) && ["readOnlyHint", "openWorldHint", "destructiveHint"].every(label => annotationNotes.includes(label)) && annotationNotes.includes("rationale"));
check("diagnostic profile list is a closed allowlist", PUBLIC_TOOLS.find(t => t.name === "mada_run_diagnostics")?.inputSchema.properties.profile.enum.join(",") === "git_status,toolchain,inventory");
check("diagnostic tool rejects extra input properties", PUBLIC_TOOLS.find(t => t.name === "mada_run_diagnostics")?.inputSchema.additionalProperties === false);
check("local agent never spawns a shell", /shell:\s*false/.test(agent) && !/shell:\s*true/.test(agent));
check("local agent does not execute user-supplied remote commands", !/body\.(command|cmd|shell|script|path)|job\.(command|cmd|shell|script|path)/i.test(agent));
check("local agent supports only fixed diagnostic program/argument sites", ["git", "process.execPath", "npm", "java"].every(program => agent.includes(program)));
check("workspace access blocks credentials, generated directories, symlinks and non-text files", operations.includes("BLOCKED_DIRS") && operations.includes("BLOCKED_NAMES") && operations.includes("isSymbolicLink") && operations.includes("Only recognised text/source files"));
check("local build tasks are a fixed shell-free allowlist with timeouts", operations.includes("shell: false") && operations.includes("android_assemble_debug") && operations.includes("android_test") && operations.includes("npm_build") && operations.includes("timeoutMs"));
check("Opera commands use a validated loopback browser-level CDP session and never expose raw socket URLs", operations.includes("127.0.0.1") && operations.includes("version.webSocketDebuggerUrl") && operations.includes("Target.attachToTarget") && operations.includes("flatten: true") && operations.includes("Unexpected Opera browser debugging socket"));
check("Opera navigation rejects arbitrary protocols and sensitive query parameters", operations.includes("validateOperaAddress") && operations.includes("Credential-like query parameters are not allowed") && operations.includes("Only HTTP, HTTPS, and approved Opera settings pages"));
check("Opera DOM automation is structured and blocks account identity and destructive UI controls", operations.includes("const allowed = [") && operations.includes("password") && operations.includes("sensitive_action_blocked"));
check("Opera clicks return before page navigation can tear down the CDP command", (operations.match(/setTimeout\(\(\) => \{ try \{ if \(el\.isConnected && !el\.disabled\) el\.click\(\); \} catch \{\} \}, 750\);/g) || []).length === 2 && (operations.match(/scheduled:true/g) || []).length === 2);
check("jobs enforce a database-level owner-agent composite foreign key", /FOREIGN KEY \(agent_id, user_id\) REFERENCES mada_public_agents\(id, user_id\) ON DELETE CASCADE/.test(schema));
check("agent/user composite key prevents cross-tenant job ownership", /UNIQUE \(id, user_id\)/.test(schema));
check("agent paths are stored only in a local owner-only file", agent.includes('path.join(HOME, ".config", "mada-commander")') && agent.includes('"agent.json"') && /chmod\(STATE_DIR, 0o700\)/.test(agent) && /chmod\(CONFIG_FILE, 0o600\)/.test(agent));
check("OAuth token exchange requires PKCE S256 and exact resource match", /safeEqual\(row\.code_challenge, pkceChallenge\(verifier\)\)/.test(router) && /body\.resource !== row\.resource/.test(router));
check("OAuth access tokens are checked for user scope and exact audience resource", /t\.resource !== resourceUrl/.test(router) && /t\.scope\.includes\(REQUIRED_SCOPE\)/.test(router));
check("CIMD trusts only official ChatGPT metadata URL and callback shapes", /hostname !== "chatgpt\.com"/.test(router) && /isApprovedChatGptRedirect\(uri\)/.test(router));
check("OAuth authorization failures can return issuer-tagged errors to validated redirects", /function oauthErrorRedirect/.test(router) && /target\.searchParams\.set\("iss", getPublicBase\(\)\)/.test(router));
check("public health requires all runtime prerequisites but keeps domain publication challenge separate", /const ready = Object\.entries\(checks\)\.filter\(\(\[key\]\) => key !== "domainChallengeConfigured"\)\.every\(\(\[, value\]\) => value\)/.test(router) && /domainChallengeConfigured: Boolean\(process\.env\.OPENAI_APPS_CHALLENGE_TOKEN\)/.test(router));
check("MCP and public OAuth endpoints allow cross-origin clients while account and agent-management APIs remain origin-restricted", router.includes("const CROSS_ORIGIN_PUBLIC_ROUTES = new Set([") && router.includes("const publicCrossOriginRoute = CROSS_ORIGIN_PUBLIC_ROUTES.has(route)") && router.includes('res.setHeader("Access-Control-Allow-Origin", publicCrossOriginRoute ? "*" : origin)') && router.includes("if (!publicCrossOriginRoute && !allowed.has(origin))"));
check("OAuth consent permits embedded browser origins only behind session-bound one-time nonces for both approve and deny", !router.includes("Cross-origin authorization is not allowed.") && router.split("consent_nonce_hash = $3").length === 4);
const oauthApproveHandler = router.slice(router.indexOf("async function handleOAuthApprove"), router.indexOf("async function handleOAuthToken"));
check("OAuth consent uses local allowlist or validated ChatGPT redirect shape and avoids remote fetches while holding the transaction lock", !/await clientIdAllowed|await clientRedirectAllowed/.test(oauthApproveHandler) && /staticClientAllowed/.test(oauthApproveHandler) && /dynamicChatGptClientAllowed/.test(oauthApproveHandler));
check("CIMD metadata lookup tolerates slower upstream responses and records redacted failure reasons", router.includes("AbortSignal.timeout(12_000)") && /Mada Commander CIMD metadata lookup failed/.test(router));
check("OAuth grants and dashboard sessions persist without automatic expiry and connected-app revocation is available", schema.includes("ALTER TABLE public.mada_oauth_refresh_tokens ALTER COLUMN expires_at DROP NOT NULL") && schema.includes("UPDATE public.mada_oauth_refresh_tokens SET expires_at = NULL") && /INSERT INTO mada_oauth_refresh_tokens .*VALUES \(\$1,\$2,\$3,\$4,\$5,NULL\)/.test(router) && /!isRefreshGrant && !await clientIdAllowed\(clientId\)/.test(router) && router.includes('case "oauth-connections-revoke"') && router.includes("DELETE FROM mada_oauth_access_tokens WHERE user_id = $1 AND client_id = $2"));
check("private Commander runtime and config are not imported", !/personal-mcp-commander|runtime\.key|tunnel-client.*runtime/i.test(publicCode));
check("public package excludes environment files from Git", (await read(".gitignore")).includes(".env.*") && (await read(".gitignore")).includes("!.env.example"));
check("real deployment secrets are not in the public package", !await fs.stat(path.join(ROOT, ".env")).then(s => s.isFile()).catch(() => false));
check("OpenAI listing has five positive and three negative cases", manifest.extensions?.["com.openai"]?.review?.test_cases?.positive?.length === 5 && manifest.extensions?.["com.openai"]?.review?.test_cases?.negative?.length === 3);
check("Vercel rewrites include MCP, OAuth, challenge and account routes", ["/mcp", "/oauth/authorize", "/oauth/token", "/oauth/revoke", "/.well-known/oauth-protected-resource", "/.well-known/oauth-authorization-server", "/.well-known/openai-apps-challenge", "/api/account/delete", "/api/agent/heartbeat", "/api/agent/poll", "/api/agent/complete"].every(route => vercel.rewrites.some(r => r.source === route)));
check("README clearly distinguishes a live deployment from OpenAI approval/readiness", (/not deployed|not approved|not ready/i.test(readme) && /deployed at https:\/\/mada-commander-public-mcp\.vercel\.app/i.test(readme)) && /Dedicated Postgres|dedicated database/i.test(readme));
check("local database migration is fail-closed", /MADA_COMMANDER_CONFIRM_SCHEMA !== "YES"/.test(await read("scripts/migrate.mjs")));
check("tenant integration test requires explicit confirmation and refuses implicit production runs", tenantTest.includes('MADA_COMMANDER_CONFIRM_TENANT_TEST !== "YES"') && tenantTest.includes('MADA_COMMANDER_ALLOW_PRODUCTION_TEST !== "YES"') && packageJson.scripts["test:tenant-isolation"] === "node scripts/test-tenant-isolation.mjs");

console.log(JSON.stringify({ audit: "mada-commander-public-boundary", passed: passes.length, failed: failures.length, checks: passes, failures }, null, 2));
if (failures.length) process.exitCode = 1;
