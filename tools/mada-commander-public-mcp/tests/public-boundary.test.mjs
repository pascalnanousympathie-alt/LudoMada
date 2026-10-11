import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { createServer } from "node:http";
import { PUBLIC_TOOLS, authChallenge, executePublicTool } from "../lib/tools.mjs";
import { hashToken, pkceChallenge, safeEqual, isSafeProjectAlias, isSafeProjectLabel } from "../lib/security.mjs";
import vercelHandler, { handleRequest, isTrustedCimdUrl, isApprovedChatGptRedirect, validateCimdMetadata } from "../api/router.mjs";

test("only the least-privilege public tool surface is advertised", () => {
  assert.deepEqual(PUBLIC_TOOLS.map(t => t.name), [
    "mada_agent_status", "mada_list_projects", "mada_run_diagnostics", "mada_job_status", "mada_connection_help"
  ]);
  for (const tool of PUBLIC_TOOLS) {
    assert.equal(typeof tool.description, "string");
    assert.ok(tool.description.length >= 40);
    assert.equal(tool.securitySchemes[0].type, "oauth2");
    assert.deepEqual(tool.securitySchemes[0].scopes, ["mada:workspace"]);
    assert.equal(typeof tool.annotations.readOnlyHint, "boolean");
    assert.equal(typeof tool.annotations.openWorldHint, "boolean");
    assert.equal(typeof tool.annotations.destructiveHint, "boolean");
    assert.ok(tool.outputSchema && tool.outputSchema.type === "object");
    assert.equal(tool.annotations.openWorldHint, false, "tools stay bounded to a user's paired workspace");
    if (tool.name === "mada_run_diagnostics") {
      assert.deepEqual(tool.inputSchema.required, ["projectId", "agentId", "profile"]);
      assert.equal(tool.inputSchema.properties.profile.enum.join(","), "git_status,toolchain,inventory");
    }
    const properties = Object.keys(tool.inputSchema.properties || {});
    assert.ok(!properties.some(k => /command|shell|path|script|url|token|secret|cookie/i.test(k)));
    assert.equal(tool.inputSchema.additionalProperties, false);
  }
});

test("diagnostics accept only fixed profiles and project aliases, never arbitrary commands", async () => {
  const principal = { userId: "user-test", scope: ["mada:workspace"] };
  const badProfile = await executePublicTool("mada_run_diagnostics", { projectId: "project1", profile: "exec" }, principal);
  assert.equal(badProfile.isError, true);
  assert.match(badProfile.content[0].text, /Only git_status/);
  const badProject = await executePublicTool("mada_run_diagnostics", { projectId: "../../etc", profile: "git_status" }, principal);
  assert.equal(badProject.isError, true);
  assert.match(badProject.content[0].text, /Invalid project alias/);
});

test("project aliases and labels reject path/terminal-control injection", () => {
  assert.equal(isSafeProjectAlias("ludomada_1"), true);
  assert.equal(isSafeProjectAlias("../ludomada"), false);
  assert.equal(isSafeProjectAlias("LudoMada"), false);
  assert.equal(isSafeProjectLabel("LudoMada local"), true);
  assert.equal(isSafeProjectLabel("bad\nlabel"), false);
});

test("PKCE S256 and hashed token helpers are deterministic and do not reveal the input", () => {
  const verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  assert.equal(pkceChallenge(verifier), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  assert.equal(hashToken("example-secret").length, 64);
  assert.notEqual(hashToken("example-secret"), "example-secret");
  assert.equal(safeEqual("abc", "abc"), true);
  assert.equal(safeEqual("abc", "abd"), false);
  assert.equal(safeEqual("abc", "longer"), false);
});

test("CIMD accepts only fixed HTTPS ChatGPT metadata URL shapes and callbacks", () => {
  assert.equal(isTrustedCimdUrl("https://chatgpt.com/oauth/client.json"), true);
  assert.equal(isTrustedCimdUrl("https://chatgpt.com/oauth/callback_123/client.json"), true);
  assert.equal(isTrustedCimdUrl("https://evil.example/oauth/client.json"), false);
  assert.equal(isTrustedCimdUrl("http://chatgpt.com/oauth/client.json"), false);
  assert.equal(isTrustedCimdUrl("https://chatgpt.com/oauth/client.json?redirect=https://evil.example"), false);
  assert.equal(isTrustedCimdUrl("https://chatgpt.com.evil.example/oauth/client.json"), false);
  assert.equal(isTrustedCimdUrl("https://chatgpt.com/oauth/callback/other/client.json"), false);
  assert.equal(isApprovedChatGptRedirect("https://chatgpt.com/connector_platform_oauth_redirect"), true);
  assert.equal(isApprovedChatGptRedirect("https://chatgpt.com/connector/oauth/callback_123"), true);
  assert.equal(isApprovedChatGptRedirect("https://evil.example/connector_platform_oauth_redirect"), false);
  assert.equal(isApprovedChatGptRedirect("https://chatgpt.com.evil.example/connector_platform_oauth_redirect"), false);
  assert.equal(isApprovedChatGptRedirect("https://chatgpt.com/connector_platform_oauth_redirect?next=https://evil.example"), false);

  // Shape matches the current official ChatGPT client metadata document.
  const clientId = "https://chatgpt.com/oauth/client.json";
  const metadata = {
    client_id: clientId,
    client_uri: "https://chatgpt.com/",
    redirect_uris: ["https://chatgpt.com/connector_platform_oauth_redirect"],
    token_endpoint_auth_methods_supported: ["none", "private_key_jwt"],
    grant_types: ["authorization_code", "refresh_token"],
    response_types: ["code"],
    client_name: "ChatGPT",
    jwks_uri: "https://chatgpt.com/oauth/jwks.json"
  };
  assert.equal(validateCimdMetadata(clientId, metadata), true);
  assert.equal(validateCimdMetadata(clientId, { ...metadata, redirect_uris: ["https://evil.example/callback"] }), false);
  assert.equal(validateCimdMetadata(clientId, { ...metadata, token_endpoint_auth_methods_supported: ["private_key_jwt"] }), false);
  assert.equal(validateCimdMetadata(clientId, { ...metadata, client_id: "https://evil.example/client.json" }), false);
});

test("OAuth consent tolerates embedded browser origins while requiring one-time nonce for approve and deny", async () => {
  const fs = await import("node:fs/promises");
  const api = await fs.readFile(new URL("../api/router.mjs", import.meta.url), "utf8");
  assert.ok(!api.includes('Cross-origin authorization is not allowed.'));
  assert.equal((api.match(/consent_nonce_hash = \$3/g) || []).length, 3);
  assert.match(api, /WHERE transaction_id = \$1 AND user_id = \$2 AND consent_nonce_hash = \$3 AND expires_at > now\(\) LIMIT 1/);
  assert.match(api, /WHERE transaction_id = \$1 AND user_id = \$2 AND consent_nonce_hash = \$3 AND expires_at > now\(\) FOR UPDATE/);
  const approveHandler = api.slice(api.indexOf("async function handleOAuthApprove"), api.indexOf("async function handleOAuthToken"));
  assert.doesNotMatch(approveHandler, /await clientIdAllowed|await clientRedirectAllowed/, "consent must not refetch remote client metadata");
  assert.match(approveHandler, /staticClientAllowed/);
  assert.match(approveHandler, /dynamicChatGptClientAllowed/);
  assert.ok(api.includes("AbortSignal.timeout(12_000)"), "CIMD fetch timeout should tolerate metadata latency");
});

test("the Vercel entrypoint exports a valid default handler and help exposes no agent secrets", async () => {
  assert.equal(typeof vercelHandler, "function");
  const { executePublicTool } = await import("../lib/tools.mjs");
  const result = await executePublicTool("mada_connection_help", {}, { userId: "user-test", scope: ["mada:workspace"] });
  assert.equal(result.isError, false);
  assert.deepEqual(Object.keys(result.structuredContent).sort(), ["pairing", "projects", "revoke"]);
  assert.ok(!JSON.stringify(result).toLowerCase().includes("tokenvalue"));
});

test("OAuth challenge directs clients to this origin's protected-resource metadata", () => {
  const challenge = authChallenge("https://mada.example", "invalid_token");
  assert.equal(challenge.isError, true);
  assert.ok(challenge._meta["mcp/www_authenticate"][0].includes('resource_metadata="https://mada.example/.well-known/oauth-protected-resource"'));
});

test("live MCP HTTP surface initializes, lists tools, and challenges unauthenticated tool calls", async t => {
  const oldBase = process.env.MADA_PUBLIC_BASE_URL;
  const oldMode = process.env.NODE_ENV;
  const oldChallenge = process.env.OPENAI_APPS_CHALLENGE_TOKEN;
  const server = createServer((req, res) => {
    handleRequest(req, res).catch(() => {
      if (!res.headersSent) { res.writeHead(500); res.end(); } else res.end();
    });
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  process.env.NODE_ENV = "test";
  process.env.MADA_PUBLIC_BASE_URL = "http://127.0.0.1:" + address.port;
  process.env.OPENAI_APPS_CHALLENGE_TOKEN = "test-challenge-token";
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    if (oldBase === undefined) delete process.env.MADA_PUBLIC_BASE_URL; else process.env.MADA_PUBLIC_BASE_URL = oldBase;
    if (oldMode === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = oldMode;
    if (oldChallenge === undefined) delete process.env.OPENAI_APPS_CHALLENGE_TOKEN; else process.env.OPENAI_APPS_CHALLENGE_TOKEN = oldChallenge;
  });

  async function rpc(message, url = process.env.MADA_PUBLIC_BASE_URL + "/mcp") {
    const r = await fetch(url, {
      method: "POST", headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: JSON.stringify(message)
    });
    return { status: r.status, headers: r.headers, body: await r.json() };
  }

  // ChatGPT/browser clients can present embedded origins unknown in advance. The public
  // /mcp transport must answer preflight with wildcard CORS, while management APIs stay restricted.
  const mcpOrigin = "https://embedded-client.example";
  const preflight = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/mcp", {
    method: "OPTIONS",
    headers: {
      origin: mcpOrigin,
      "access-control-request-method": "POST",
      "access-control-request-headers": "authorization, content-type, mcp-protocol-version, accept"
    }
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get("access-control-allow-origin"), "*");
  assert.match(preflight.headers.get("access-control-allow-headers") || "", /Authorization/);

  // ChatGPT may fetch OAuth discovery and token endpoints from an embedded origin too.
  for (const publicUrl of [
    process.env.MADA_PUBLIC_BASE_URL + "/.well-known/oauth-protected-resource",
    process.env.MADA_PUBLIC_BASE_URL + "/.well-known/oauth-authorization-server",
    process.env.MADA_PUBLIC_BASE_URL + "/oauth/token"
  ]) {
    const publicPreflight = await fetch(publicUrl, {
      method: "OPTIONS",
      headers: {
        origin: mcpOrigin,
        "access-control-request-method": "POST",
        "access-control-request-headers": "content-type"
      }
    });
    assert.equal(publicPreflight.status, 204, publicUrl);
    assert.equal(publicPreflight.headers.get("access-control-allow-origin"), "*", publicUrl);
  }

  const crossOriginResourceMetadata = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/.well-known/oauth-protected-resource", {
    headers: { origin: mcpOrigin }
  });
  assert.equal(crossOriginResourceMetadata.status, 200);
  assert.equal(crossOriginResourceMetadata.headers.get("access-control-allow-origin"), "*");

  const crossOriginInit = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/mcp", {
    method: "POST",
    headers: { origin: mcpOrigin, "content-type": "application/json", accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 77, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "cors-test", version: "1" } } })
  });
  assert.equal(crossOriginInit.status, 200);
  assert.equal(crossOriginInit.headers.get("access-control-allow-origin"), "*");

  const rejectedManagementOrigin = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/api/account/delete", {
    method: "POST",
    headers: { origin: mcpOrigin, "content-type": "application/json" },
    body: JSON.stringify({})
  });
  assert.equal(rejectedManagementOrigin.status, 403);
  assert.deepEqual(await rejectedManagementOrigin.json(), { error: "origin_not_allowed" });

  const init = await rpc({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "test", version: "1" } } });
  assert.equal(init.status, 200);
  assert.equal(init.body.result.serverInfo.name, "mada-commander-public");
  assert.equal(init.body.result.protocolVersion, "2025-06-18");

  // A caller-supplied ?route parameter must never override the canonical /mcp route.
  const initWithRouteQuery = await rpc({ jsonrpc: "2.0", id: 11, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "test", version: "1" } } }, process.env.MADA_PUBLIC_BASE_URL + "/mcp?route=health");
  assert.equal(initWithRouteQuery.status, 200);
  assert.equal(initWithRouteQuery.body.result.serverInfo.name, "mada-commander-public");

  const listed = await rpc({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} });
  assert.equal(listed.status, 200);
  assert.equal(listed.body.result.tools.length, 5);
  assert.deepEqual(listed.body.result.tools.map(t => t.name), PUBLIC_TOOLS.map(t => t.name));
  assert.ok(listed.body.result.tools.every(t => t.outputSchema && t.outputSchema.type === "object"));

  const resourceMeta = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/.well-known/oauth-protected-resource");
  assert.equal(resourceMeta.status, 200);
  const resourceDoc = await resourceMeta.json();
  assert.equal(resourceDoc.resource, process.env.MADA_PUBLIC_BASE_URL);
  assert.deepEqual(resourceDoc.authorization_servers, [process.env.MADA_PUBLIC_BASE_URL]);
  assert.ok(resourceDoc.resource_policy_uri.endsWith("/privacy.html"));

  const authMeta = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/.well-known/oauth-authorization-server");
  assert.equal(authMeta.status, 200);
  const authDoc = await authMeta.json();
  assert.equal(authDoc.issuer, process.env.MADA_PUBLIC_BASE_URL);
  assert.ok(authDoc.code_challenge_methods_supported.includes("S256"));
  assert.equal(authDoc.authorization_response_iss_parameter_supported, true);
  assert.ok(authDoc.revocation_endpoint.endsWith("/oauth/revoke"));

  const internalRewrite = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/api/router?route=oauth-resource");
  assert.equal(internalRewrite.status, 200);
  assert.equal((await internalRewrite.json()).resource, process.env.MADA_PUBLIC_BASE_URL);

  const challengeRes = await fetch(process.env.MADA_PUBLIC_BASE_URL + "/.well-known/openai-apps-challenge");
  assert.equal(challengeRes.status, 200);
  assert.equal(challengeRes.headers.get("content-type").split(";")[0], "text/plain");
  assert.equal(await challengeRes.text(), "test-challenge-token");

  const unauth = await rpc({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "mada_agent_status", arguments: {} } });
  assert.equal(unauth.status, 401);
  assert.equal(unauth.body.error.code, -32001);
  assert.match(unauth.headers.get("www-authenticate") || "", /resource_metadata="http:\/\/127\.0\.0\.1:\d+\/.well-known\/oauth-protected-resource"/);
  assert.match(unauth.headers.get("www-authenticate") || "", /scope="mada:workspace"/);
});

test("dashboard offers sign-out, account deletion, and owned-agent revocation", async () => {
  const fs = await import("node:fs/promises");
  const path = await import("node:path");
  const root = path.resolve(new URL("..", import.meta.url).pathname);
  const html = await fs.readFile(path.join(root, "index.html"), "utf8");
  const api = await fs.readFile(path.join(root, "api/router.mjs"), "utf8");
  assert.ok(html.includes('id="logout"'));
  assert.ok(html.includes('class="btn primary disabled" id="login" href="#readiness" aria-disabled="true" tabindex="-1"'));
  assert.ok(html.includes("login.tabIndex=ready?0:-1"));
  assert.ok(html.includes("function setLoginAvailability(ready)"));
  assert.ok(html.includes('id="readinessBadge">NOT READY</span>'));
  assert.ok(html.includes("Protected operations remain disabled until secure setup is complete."));
  assert.ok(html.includes("Sign-in is disabled until setup is complete."));
  assert.ok(html.includes("serviceReady=Boolean(response.ok&&d.ready)"));
  assert.ok(html.includes("Sign-in will be available after secure setup is complete."));
  assert.ok(html.includes("Protected features remain unavailable until the operator completes the secure service setup."));
  assert.ok(html.includes('id="deleteAccount"'));
  assert.ok(html.includes('id="oauthConnections"'));
  assert.ok(html.includes('/api/oauth/connections'));
  assert.ok(html.includes('/api/oauth/connections/revoke'));
  assert.ok(html.includes("These grants do not expire automatically."));
  assert.ok(html.includes('/api/account/delete'));
  assert.ok(html.includes('confirm:"DELETE"'));
  assert.ok(api.includes('case "account-delete": return await handleAccountDelete(req, res);'));
  assert.ok(api.includes('DELETE FROM mada_public_users WHERE id = $1'));
  assert.ok(api.includes('UPDATE mada_public_agents SET revoked_at = now()'));
});

test("OAuth refresh grants persist until revoked and can be managed from the website", async () => {
  const fs = await import("node:fs/promises");
  const path = await import("node:path");
  const root = path.resolve(new URL("..", import.meta.url).pathname);
  const [router, schema, vercel, security] = await Promise.all([
    fs.readFile(path.join(root, "api/router.mjs"), "utf8"),
    fs.readFile(path.join(root, "schema.sql"), "utf8"),
    fs.readFile(path.join(root, "vercel.json"), "utf8").then(JSON.parse),
    fs.readFile(path.join(root, "lib/security.mjs"), "utf8")
  ]);
  assert.match(router, /INSERT INTO mada_oauth_refresh_tokens .*VALUES \(\$1,\$2,\$3,\$4,\$5,NULL\)/);
  assert.match(router, /expires_at IS NULL OR expires_at > now\(\)\) FOR UPDATE/);
  assert.match(router, /DELETE FROM mada_oauth_refresh_tokens WHERE user_id = \$1 AND client_id = \$2/);
  assert.match(router, /DELETE FROM mada_oauth_access_tokens WHERE user_id = \$1 AND client_id = \$2/);
  const tokenHandler = router.slice(router.indexOf("async function handleOAuthToken"), router.indexOf("async function handleOAuthRevoke"));
  assert.match(tokenHandler, /const isRefreshGrant = body\.grant_type === "refresh_token"/);
  assert.match(tokenHandler, /!isRefreshGrant && !await clientIdAllowed\(clientId\)/);
  assert.match(router, /expires_at IS NULL OR s\.expires_at > now\(\)/);
  assert.match(router, /now\(\) \+ interval '60 minutes'/);
  assert.match(schema, /ALTER TABLE public\.mada_oauth_refresh_tokens ALTER COLUMN expires_at DROP NOT NULL/);
  assert.match(schema, /UPDATE public\.mada_oauth_refresh_tokens SET expires_at = NULL/);
  assert.match(schema, /ALTER TABLE public\.mada_web_sessions ALTER COLUMN expires_at DROP NOT NULL/);
  assert.match(security, /WEB_SESSION_TTL_SECONDS = 400 \* 24 \* 60 \* 60/);
  assert.ok(vercel.rewrites.some(r => r.source === "/api/oauth/connections"));
  assert.ok(vercel.rewrites.some(r => r.source === "/api/oauth/connections/revoke"));
});

test("Vercel rewrites and migration guards are explicit", async () => {
  const fs = await import("node:fs/promises");
  const path = await import("node:path");
  const root = path.resolve(new URL("..", import.meta.url).pathname);
  const vercel = JSON.parse(await fs.readFile(path.join(root, "vercel.json"), "utf8"));
  const sourcePaths = new Set(vercel.rewrites.map(r => r.source));
  assert.equal(vercel.functions["api/router.mjs"].includeFiles, "*.html");
  const globalSecurityHeaders = vercel.headers?.find(rule => rule.source === "/(.*)")?.headers || [];
  for (const key of ["Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Permissions-Policy", "Cache-Control"]) assert.ok(globalSecurityHeaders.some(header => header.key === key), "missing global security header: " + key);
  assert.ok(vercel.functions["api/router.mjs"].maxDuration >= 30);
  for (const route of ["/mcp", "/.well-known/openai-apps-challenge", "/.well-known/oauth-protected-resource", "/.well-known/oauth-authorization-server", "/oauth/authorize", "/oauth/token", "/oauth/revoke", "/oauth/google/start", "/oauth/google/callback", "/oauth/approve", "/api/auth/session", "/api/auth/logout", "/api/account/delete", "/api/agent/list", "/api/agent/revoke", "/api/agent/pair-code", "/api/agent/enroll", "/api/agent/heartbeat", "/api/agent/poll", "/api/agent/complete"]) {
    assert.ok(sourcePaths.has(route), "missing Vercel rewrite: " + route);
  }
  const schema = await fs.readFile(path.join(root, "schema.sql"), "utf8");
  assert.ok(schema.includes("mada_public_users"));
  assert.ok(schema.includes("mada_public_agents"));
  assert.ok(schema.includes("mada_public_jobs"));
  assert.ok(!schema.includes("game_wallet"));
});
