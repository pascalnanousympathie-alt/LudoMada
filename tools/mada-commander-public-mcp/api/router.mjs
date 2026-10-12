import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { query, withTransaction, databaseConfigured } from "../lib/db.mjs";
import {
  ACCESS_TOKEN_TTL_SECONDS, AUTH_CODE_TTL_SECONDS, GOOGLE_STATE_TTL_SECONDS,
  PAIR_CODE_TTL_SECONDS, WEB_SESSION_TTL_SECONDS,
  REQUIRED_SCOPE, allowedList, escapeHtml, getPublicBase, hashToken,
  isSafeProjectAlias, isSafeProjectLabel, mcpResourceUrl, parseBearer, parseBodyText,
  parseCookies, pkceChallenge, randomToken, redactSensitive,
  safeEqual, setSecurityHeaders
} from "../lib/security.mjs";
import { PUBLIC_TOOLS, authChallenge, executePublicTool } from "../lib/tools.mjs";

const COOKIE_NAME = "mada_commander_session";
const KNOWN_PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const HERE = path.dirname(fileURLToPath(import.meta.url));
const PACKAGE_ROOT = path.resolve(HERE, "..");

function send(res, status, body, contentType = "application/json; charset=utf-8", extraHeaders = {}) {
  setSecurityHeaders(res);
  res.writeHead(status, { "Content-Type": contentType, ...extraHeaders });
  res.end(contentType.startsWith("application/json") ? JSON.stringify(body) : body);
}

function html(res, status, text, extraHeaders = {}) {
  return send(res, status, text, "text/html; charset=utf-8", extraHeaders);
}

function jsonError(res, status, error, description) {
  return send(res, status, { error, error_description: description || error });
}

function redirect(res, target, extraHeaders = {}) {
  setSecurityHeaders(res);
  res.writeHead(302, { Location: target, ...extraHeaders });
  res.end();
}

function oauthErrorRedirect(res, redirectUri, state, error, description) {
  const target = new URL(redirectUri);
  target.searchParams.set("error", error);
  if (state && String(state).length <= 1024) target.searchParams.set("state", String(state));
  if (description) target.searchParams.set("error_description", String(description).slice(0, 200));
  target.searchParams.set("iss", getPublicBase());
  return redirect(res, target.toString());
}

function setSessionCookie(res, token, base) {
  const secure = new URL(base).protocol === "https:" ? "; Secure" : "";
  res.setHeader("Set-Cookie", COOKIE_NAME + "=" + encodeURIComponent(token) + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + WEB_SESSION_TTL_SECONDS + secure);
}

function clearSessionCookie(res, base) {
  const secure = new URL(base).protocol === "https:" ? "; Secure" : "";
  res.setHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + secure);
}

async function readBody(req) {
  if (req.body !== undefined && req.body !== null) {
    if (typeof req.body === "string" || Buffer.isBuffer(req.body)) {
      return parseBodyText(req.body.toString(), req.headers["content-type"] || "");
    }
    if (typeof req.body === "object") return req.body;
  }
  return await new Promise((resolve, reject) => {
    let text = "";
    req.on("data", chunk => {
      text += chunk.toString();
      if (text.length > 64_000) {
        reject(Object.assign(new Error("Body too large."), { statusCode: 413 }));
        req.destroy?.();
      }
    });
    req.on("end", () => resolve(parseBodyText(text, req.headers["content-type"] || "")));
    req.on("error", reject);
  });
}

function requestUrl(req) {
  // Routing only needs path/query. The configured public origin is authoritative; never
  // trust Host or X-Forwarded-Proto supplied by a caller to construct security-sensitive URLs.
  const base = publicBaseOrNull() || ("http://127.0.0.1:" + String(process.env.PORT || 8789));
  return new URL(req.url || "/", base);
}

function routeFor(url) {
  const direct = {
    "/": "home",
    "/mcp": "mcp",
    "/.well-known/openai-apps-challenge": "domain-challenge",
    "/.well-known/oauth-protected-resource": "oauth-resource",
    "/.well-known/oauth-authorization-server": "oauth-server",
    "/oauth/authorize": "oauth-authorize",
    "/oauth/token": "oauth-token",
    "/oauth/revoke": "oauth-revoke",
    "/oauth/google/start": "google-start",
    "/oauth/google/callback": "google-callback",
    "/oauth/approve": "oauth-approve",
    "/api/auth/session": "auth-session",
    "/api/auth/logout": "auth-logout",
    "/api/oauth/connections": "oauth-connections",
    "/api/oauth/connections/revoke": "oauth-connections-revoke",
    "/api/account/delete": "account-delete",
    "/api/agent/list": "agent-list",
    "/api/agent/revoke": "agent-revoke",
    "/api/agent/pair-code": "agent-pair-code",
    "/api/agent/enroll": "agent-enroll",
    "/api/agent/heartbeat": "agent-heartbeat",
    "/api/agent/poll": "agent-poll",
    "/api/agent/complete": "agent-complete",
    "/api/health": "health",
    "/privacy.html": "privacy",
    "/terms.html": "terms",
    "/support.html": "support"
  };
  if (direct[url.pathname]) return direct[url.pathname];
  // Vercel rewrites the public URLs to this single serverless handler. Never allow a caller
  // to override the route of a canonical URL by supplying ?route=... in the query string.
  if (url.pathname === "/api/router") return url.searchParams.get("route") || "";
  return "";
}

function publicBaseOrNull() {
  try { return getPublicBase(); } catch { return null; }
}

function requireDatabase() {
  if (!databaseConfigured) {
    const error = new Error("The public service database is not configured.");
    error.statusCode = 503;
    error.publicCode = "service_not_configured";
    throw error;
  }
}

const CIMD_ENABLED = String(process.env.MADA_OAUTH_CIMD_ENABLED || "").toLowerCase() === "true";
const cimdCache = new Map();
const CIMD_CACHE_MS = 5 * 60_000;

function oauthReady() {
  const clientIdsConfigured = allowedList("MADA_OAUTH_CLIENT_IDS").length > 0;
  const redirectsConfigured = allowedList("MADA_OAUTH_REDIRECT_URIS").length > 0;
  const staticClientModeReady = clientIdsConfigured && redirectsConfigured;
  const clientModeReady = staticClientModeReady || CIMD_ENABLED;
  return Boolean(process.env.GOOGLE_OAUTH_CLIENT_ID && process.env.GOOGLE_OAUTH_CLIENT_SECRET
    && process.env.MADA_PUBLIC_BASE_URL && clientModeReady && databaseConfigured);
}

function baseSafeHttpsUrl(uri) {
  try {
    const u = new URL(uri);
    return u.protocol === "https:" && !u.username && !u.password && !u.hash && !u.search;
  } catch { return false; }
}

export function isTrustedCimdUrl(clientId) {
  try {
    const u = new URL(clientId);
    if (u.protocol !== "https:" || u.hostname !== "chatgpt.com" || u.port || u.username || u.password || u.search || u.hash) return false;
    return u.pathname === "/oauth/client.json" || /^\/oauth\/[A-Za-z0-9_-]+\/client\.json$/.test(u.pathname);
  } catch { return false; }
}

export function isApprovedChatGptRedirect(uri) {
  if (!baseSafeHttpsUrl(uri)) return false;
  try {
    const u = new URL(uri);
    return u.origin === "https://chatgpt.com"
      && (u.pathname === "/connector_platform_oauth_redirect"
        || /^\/connector\/oauth\/[A-Za-z0-9_-]+$/.test(u.pathname));
  } catch { return false; }
}

export function validateCimdMetadata(clientId, metadata) {
  if (!isTrustedCimdUrl(clientId) || !metadata || metadata.client_id !== clientId
    || !Array.isArray(metadata.redirect_uris) || !metadata.redirect_uris.length
    || !Array.isArray(metadata.response_types) || !metadata.response_types.includes("code")) return false;
  if (metadata.grant_types && (!Array.isArray(metadata.grant_types) || !metadata.grant_types.includes("authorization_code"))) return false;
  const tokenAuthMethods = Array.isArray(metadata.token_endpoint_auth_methods_supported)
    ? metadata.token_endpoint_auth_methods_supported
    : [metadata.token_endpoint_auth_method || ""];
  // ChatGPT publishes both methods; this server accepts public-client token exchange with "none".
  if (!tokenAuthMethods.includes("none")) return false;
  if (metadata.redirect_uris.some(uri => typeof uri !== "string" || !isApprovedChatGptRedirect(uri))) return false;
  return true;
}

async function getCimdMetadata(clientId) {
  if (!CIMD_ENABLED || !isTrustedCimdUrl(clientId)) return null;
  const cached = cimdCache.get(clientId);
  if (cached && cached.expiresAt > Date.now()) return cached.metadata;
  try {
    const response = await fetch(clientId, { redirect: "error", headers: { accept: "application/json" }, signal: AbortSignal.timeout(12_000) });
    if (!response.ok) {
      console.warn("Mada Commander CIMD metadata lookup failed", { reason: "http_status", status: response.status });
      return null;
    }
    const text = await response.text();
    if (text.length > 32_768) {
      console.warn("Mada Commander CIMD metadata lookup failed", { reason: "metadata_too_large" });
      return null;
    }
    const metadata = JSON.parse(text);
    if (!validateCimdMetadata(clientId, metadata)) {
      console.warn("Mada Commander CIMD metadata lookup failed", { reason: "metadata_validation_failed" });
      return null;
    }
    cimdCache.set(clientId, { metadata, expiresAt: Date.now() + CIMD_CACHE_MS });
    return metadata;
  } catch (error) {
    console.warn("Mada Commander CIMD metadata lookup failed", { reason: error?.name === "TimeoutError" ? "timeout" : "fetch_or_parse_error" });
    return null;
  }
}

async function clientRedirectAllowed(clientId, redirectUri) {
  if (!baseSafeHttpsUrl(redirectUri)) return false;
  if (allowedList("MADA_OAUTH_CLIENT_IDS").includes(clientId)
    && allowedList("MADA_OAUTH_REDIRECT_URIS").includes(redirectUri)) return true;
  const metadata = await getCimdMetadata(clientId);
  return Boolean(metadata && metadata.redirect_uris.includes(redirectUri));
}

async function clientIdAllowed(clientId) {
  if (allowedList("MADA_OAUTH_CLIENT_IDS").includes(clientId)) return true;
  return Boolean(await getCimdMetadata(clientId));
}

function parseScopes(raw) {
  const scopes = String(raw || REQUIRED_SCOPE).split(/\s+/).filter(Boolean);
  const unique = [...new Set(scopes)];
  if (unique.length !== 1 || unique[0] !== REQUIRED_SCOPE) return null;
  return unique;
}

let lastCleanupAt = 0;
async function cleanupExpiredRecords() {
  const now = Date.now();
  if (now - lastCleanupAt < 15 * 60_000) return;
  lastCleanupAt = now;
  await query("DELETE FROM mada_google_states WHERE expires_at < now()");
  await query("DELETE FROM mada_oauth_authorization_requests WHERE expires_at < now()");
  await query("DELETE FROM mada_oauth_codes WHERE expires_at < now()");
  await query("DELETE FROM mada_oauth_access_tokens WHERE expires_at < now() OR revoked_at IS NOT NULL");
  await query("DELETE FROM mada_oauth_refresh_tokens WHERE expires_at < now()");
  await query("DELETE FROM mada_web_sessions WHERE expires_at < now()");
  await query("DELETE FROM mada_agent_pair_codes WHERE expires_at < now()");
  await query("UPDATE mada_public_jobs SET status = 'failed', error_summary = 'Job expired before completion.', finished_at = now() WHERE status IN ('queued','running') AND created_at < now() - interval '1 day'");
  await query("DELETE FROM mada_public_jobs WHERE finished_at < now() - interval '30 days'");
  await query("DELETE FROM mada_public_rate_limits WHERE updated_at < now() - interval '1 day'");
}

async function rateLimit(key, maxHits, windowSeconds) {
  requireDatabase();
  await cleanupExpiredRecords();
  const r = await query(
    "INSERT INTO mada_public_rate_limits (key_hash, window_start, hit_count) VALUES ($1, now(), 1) " +
    "ON CONFLICT (key_hash) DO UPDATE SET " +
    "hit_count = CASE WHEN mada_public_rate_limits.window_start <= now() - ($2 * interval '1 second') THEN 1 ELSE mada_public_rate_limits.hit_count + 1 END, " +
    "window_start = CASE WHEN mada_public_rate_limits.window_start <= now() - ($2 * interval '1 second') THEN now() ELSE mada_public_rate_limits.window_start END, updated_at = now() RETURNING hit_count",
    [hashToken(key), windowSeconds]
  );
  return Number(r.rows[0].hit_count) <= maxHits;
}

function requesterKey(req, prefix) {
  // Hash the address immediately; raw client addresses are not stored.
  const forwarded = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  const address = forwarded || String(req.socket?.remoteAddress || "unknown");
  return prefix + ":" + address;
}

async function sessionUser(req) {
  const cookies = parseCookies(req.headers.cookie);
  const token = cookies[COOKIE_NAME];
  if (!token) return null;
  requireDatabase();
  const r = await query(
    "SELECT u.id, u.email, u.display_name FROM mada_web_sessions s JOIN mada_public_users u ON u.id = s.user_id WHERE s.token_hash = $1 AND (s.expires_at IS NULL OR s.expires_at > now()) LIMIT 1",
    [hashToken(token)]
  );
  return r.rows[0] ? { userId: r.rows[0].id, email: r.rows[0].email, displayName: r.rows[0].display_name } : null;
}

async function renewPersistentWebSession(req, res, route, base) {
  const sessionRoutes = new Set([
    "home", "auth-session", "auth-logout", "account-delete", "agent-list",
    "agent-revoke", "agent-pair-code", "oauth-connections", "oauth-connections-revoke"
  ]);
  if (!base || !sessionRoutes.has(route)) return;
  const token = parseCookies(req.headers.cookie)[COOKIE_NAME];
  if (!token) return;
  requireDatabase();
  const active = await query(
    "SELECT 1 FROM mada_web_sessions WHERE token_hash = $1 AND (expires_at IS NULL OR expires_at > now()) LIMIT 1",
    [hashToken(token)]
  );
  if (active.rowCount) setSessionCookie(res, token, base);
}

async function agentUser(req) {
  const token = parseBearer(req.headers.authorization);
  if (!token) return null;
  requireDatabase();
  const r = await query(
    "SELECT id, user_id, name FROM mada_public_agents WHERE token_hash = $1 AND revoked_at IS NULL LIMIT 1",
    [hashToken(token)]
  );
  return r.rows[0] ? { agentId: r.rows[0].id, userId: r.rows[0].user_id, name: r.rows[0].name } : null;
}

async function accessPrincipal(req, resourceUrl) {
  const token = parseBearer(req.headers.authorization);
  if (!token) return { principal: null, reason: "missing" };
  requireDatabase();
  const r = await query(
    "SELECT user_id, client_id, scope, resource FROM mada_oauth_access_tokens WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now() LIMIT 1",
    [hashToken(token)]
  );
  if (!r.rowCount) return { principal: null, reason: "invalid" };
  const t = r.rows[0];
  if (t.resource !== resourceUrl) return { principal: null, reason: "invalid" };
  if (!Array.isArray(t.scope) || !t.scope.includes(REQUIRED_SCOPE)) return { principal: null, reason: "scope" };
  return { principal: { userId: t.user_id, clientId: t.client_id, scope: t.scope }, reason: "" };
}

function mcpError(id, code, message) {
  return { jsonrpc: "2.0", id: id ?? null, error: { code, message } };
}

function mcpResult(id, result) {
  return { jsonrpc: "2.0", id, result };
}

async function handleMcp(req, res, url) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST, OPTIONS");
    return send(res, 405, { error: "method_not_allowed" });
  }
  const body = await readBody(req);
  if (!body || body.jsonrpc !== "2.0" || typeof body.method !== "string") {
    return send(res, 400, mcpError(body?.id, -32600, "Invalid JSON-RPC request."));
  }
  if (body.id === undefined && body.method.startsWith("notifications/")) {
    res.writeHead(202, { "Cache-Control": "no-store" });
    return res.end();
  }
  if (body.method === "initialize") {
    const requested = body.params?.protocolVersion;
    const protocolVersion = KNOWN_PROTOCOLS.includes(requested) ? requested : "2025-06-18";
    return send(res, 200, mcpResult(body.id, {
      protocolVersion,
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "mada-commander-public", version: "0.1.0" },
      instructions: "Mada Commander only works on projects registered by the signed-in user's own paired local agent. mada_run_command supports an explicit allowlist of command executables with separately passed arguments and no shell parsing; the commands can still modify files or use the paired user's network permissions. The registered workspace is the starting directory, not a sandbox."
    }));
  }
  if (body.method === "ping") return send(res, 200, mcpResult(body.id, {}));
  if (body.method === "tools/list") {
    return send(res, 200, mcpResult(body.id, { tools: PUBLIC_TOOLS }));
  }
  if (body.method !== "tools/call") {
    return send(res, 200, mcpError(body.id, -32601, "Method not found."));
  }

  const name = body.params?.name;
  const known = PUBLIC_TOOLS.find(t => t.name === name);
  if (!known) {
    return send(res, 200, mcpResult(body.id, {
      content: [{ type: "text", text: "Unknown public Mada Commander tool." }], isError: true
    }));
  }

  const base = getPublicBase();
  let auth;
  try {
    auth = await accessPrincipal(req, mcpResourceUrl());
  } catch (error) {
    if (error.statusCode === 503) {
      return send(res, 200, mcpResult(body.id, {
        content: [{ type: "text", text: "Mada Commander public authentication is not configured yet." }], isError: true
      }));
    }
    throw error;
  }
  if (!auth.principal) {
    const error = auth.reason === "scope" ? "insufficient_scope" : auth.reason === "invalid" ? "invalid_token" : "";
    const challenge = 'Bearer resource_metadata="' + base + '/.well-known/oauth-protected-resource", scope="' + REQUIRED_SCOPE + '"' +
      (error ? ', error="' + error + '"' : "");
    return send(res, 401, mcpError(body.id, -32001, "Unauthorized. Connect Mada Commander and retry."), "application/json; charset=utf-8", {
      "WWW-Authenticate": challenge
    });
  }

  try {
    if (!await rateLimit("mcp-tool:" + auth.principal.userId, 120, 60)) {
      return send(res, 200, mcpResult(body.id, { content: [{ type: "text", text: "Mada Commander request rate limit reached. Please retry shortly." }], isError: true }));
    }
    const result = await executePublicTool(name, body.params?.arguments || {}, auth.principal);
    return send(res, 200, mcpResult(body.id, result));
  } catch (error) {
    console.error("Mada Commander tool failure:", String(error.code || "TOOL_ERROR"));
    return send(res, 200, mcpResult(body.id, {
      content: [{ type: "text", text: "The requested operation could not be completed. Check service status and try again." }],
      isError: true
    }));
  }
}

function consentPage(txnId, nonce) {
  return '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Authorize Mada Commander</title>' +
    '<body style="margin:0;background:#071019;color:#edf6ff;font:16px system-ui"><main style="max-width:520px;margin:12vh auto;padding:24px;background:#0e1925;border:1px solid #29465c;border-radius:16px">' +
    '<h1>Mada Commander</h1><h2>Authorize workspace and command access?</h2><p>Mada Commander can inspect and edit registered project files and run allowlisted command executables on your paired device using separate arguments. Commands run with the local agent user's OS permissions and can modify or delete files or access the network. Continue only if this is your device and you trust the requesting ChatGPT connection.</p>' +
    '<form method="post" action="/oauth/approve"><input type="hidden" name="transaction_id" value="' + escapeHtml(txnId) + '"><input type="hidden" name="consent_nonce" value="' + escapeHtml(nonce) + '">' +
    '<button name="decision" value="approve" style="padding:12px 18px;background:#b3873b;color:#160f05;border:0;border-radius:10px;font-weight:700">Approve</button> ' +
    '<button name="decision" value="deny" style="padding:12px 18px;background:#142839;color:#edf6ff;border:1px solid #29465c;border-radius:10px">Cancel</button></form></main></body></html>';
}

async function handleOAuthAuthorize(req, res, url) {
  if (req.method !== "GET") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (!oauthReady()) return jsonError(res, 503, "temporarily_unavailable", "Mada Commander OAuth is not configured for public clients yet.");
  if (!await rateLimit(requesterKey(req, "oauth-authorize"), 12, 300)) return jsonError(res, 429, "rate_limited");
  const clientId = url.searchParams.get("client_id") || "";
  const redirectUri = url.searchParams.get("redirect_uri") || "";
  const state = url.searchParams.get("state") || "";
  const responseType = url.searchParams.get("response_type") || "";
  const verifierChallenge = url.searchParams.get("code_challenge") || "";
  const challengeMethod = url.searchParams.get("code_challenge_method") || "";
  const resource = url.searchParams.get("resource") || "";
  const scopes = parseScopes(url.searchParams.get("scope") || REQUIRED_SCOPE);
  if (responseType !== "code") return jsonError(res, 400, "unsupported_response_type");
  if (!await clientIdAllowed(clientId)) return jsonError(res, 400, "unauthorized_client", "Client ID is neither on the configured allowlist nor a valid OpenAI client metadata document.");
  if (!await clientRedirectAllowed(clientId, redirectUri)) return jsonError(res, 400, "invalid_request", "Redirect URI is not registered for this OAuth client.");
  if (state.length < 16 || state.length > 1024) return jsonError(res, 400, "invalid_request", "A strong OAuth state value is required.");
  if (challengeMethod !== "S256" || !/^[A-Za-z0-9_-]{43}$/.test(verifierChallenge)) return oauthErrorRedirect(res, redirectUri, state, "invalid_request", "PKCE S256 is required.");
  if (!resource || resource !== mcpResourceUrl()) return oauthErrorRedirect(res, redirectUri, state, "invalid_target", "The resource must match the public MCP service.");
  if (!scopes) return oauthErrorRedirect(res, redirectUri, state, "invalid_scope", "Only the mada:workspace scope is supported.");
  const txnId = crypto.randomUUID();
  await query(
    "INSERT INTO mada_oauth_authorization_requests (transaction_id, client_id, redirect_uri, client_state, code_challenge, scope, resource, expires_at) VALUES ($1,$2,$3,$4,$5,$6,$7,now() + interval '60 minutes')",
    [txnId, clientId, redirectUri, state, verifierChallenge, scopes, resource]
  );
  const start = new URL(getPublicBase() + "/oauth/google/start");
  start.searchParams.set("purpose", "mcp");
  start.searchParams.set("transaction_id", txnId);
  return redirect(res, start.toString());
}

async function handleGoogleStart(req, res, url) {
  if (req.method !== "GET") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (!process.env.GOOGLE_OAUTH_CLIENT_ID || !process.env.GOOGLE_OAUTH_CLIENT_SECRET || !process.env.MADA_PUBLIC_BASE_URL) {
    return jsonError(res, 503, "temporarily_unavailable", "Google OAuth credentials are not configured.");
  }
  if (!await rateLimit(requesterKey(req, "google-start"), 10, 900)) return jsonError(res, 429, "rate_limited");
  const purpose = url.searchParams.get("purpose") === "mcp" ? "mcp" : "dashboard";
  const transactionId = url.searchParams.get("transaction_id") || null;
  if (purpose === "mcp") {
    const txn = await query("SELECT transaction_id FROM mada_oauth_authorization_requests WHERE transaction_id = $1 AND expires_at > now() LIMIT 1", [transactionId]);
    if (!txn.rowCount) return jsonError(res, 400, "invalid_request", "The authorization request expired. Start the connection again.");
  }
  const state = randomToken(32);
  await query(
    "INSERT INTO mada_google_states (state_hash, purpose, transaction_id, expires_at) VALUES ($1,$2,$3,now() + interval '15 minutes')",
    [hashToken(state), purpose, transactionId]
  );
  const base = getPublicBase();
  const redirectUri = base + "/oauth/google/callback";
  const google = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  google.searchParams.set("client_id", process.env.GOOGLE_OAUTH_CLIENT_ID);
  google.searchParams.set("redirect_uri", redirectUri);
  google.searchParams.set("response_type", "code");
  google.searchParams.set("scope", "openid email profile");
  google.searchParams.set("state", state);
  google.searchParams.set("include_granted_scopes", "true");
  google.searchParams.set("prompt", "select_account");
  return redirect(res, google.toString());
}

async function handleGoogleCallback(req, res, url) {
  if (req.method !== "GET") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const state = url.searchParams.get("state") || "";
  const googleCode = url.searchParams.get("code") || "";
  if (!state || !googleCode || url.searchParams.get("error")) return jsonError(res, 400, "access_denied", "Google sign-in was cancelled or failed.");
  const stateResult = await query(
    "DELETE FROM mada_google_states WHERE state_hash = $1 AND expires_at > now() RETURNING purpose, transaction_id",
    [hashToken(state)]
  );
  if (!stateResult.rowCount) return jsonError(res, 400, "invalid_request", "The sign-in session expired. Start again.");
  const stateRow = stateResult.rows[0];
  const base = getPublicBase();
  const redirectUri = base + "/oauth/google/callback";
  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code: googleCode,
      client_id: process.env.GOOGLE_OAUTH_CLIENT_ID || "",
      client_secret: process.env.GOOGLE_OAUTH_CLIENT_SECRET || "",
      redirect_uri: redirectUri,
      grant_type: "authorization_code"
    }),
    signal: AbortSignal.timeout(10_000)
  });
  const tokenPayload = await tokenResponse.json().catch(() => ({}));
  if (!tokenResponse.ok || !tokenPayload.access_token) {
    console.error("Google OAuth code exchange failed:", tokenResponse.status);
    return jsonError(res, 401, "access_denied", "Google could not verify this sign-in.");
  }
  const profileResponse = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
    headers: { authorization: "Bearer " + tokenPayload.access_token },
    signal: AbortSignal.timeout(10_000)
  });
  const profile = await profileResponse.json().catch(() => ({}));
  if (!profileResponse.ok || !profile.sub || !profile.email || profile.email_verified !== true) {
    return jsonError(res, 403, "access_denied", "A verified Google email is required.");
  }
  const userId = crypto.randomUUID();
  const user = await query(
    "INSERT INTO mada_public_users (id, google_sub, email, display_name, email_verified) VALUES ($1,$2,$3,$4,true) " +
    "ON CONFLICT (google_sub) DO UPDATE SET email = EXCLUDED.email, display_name = EXCLUDED.display_name, email_verified = true, updated_at = now() RETURNING id,email,display_name",
    [userId, String(profile.sub).slice(0, 255), String(profile.email).slice(0, 320), String(profile.name || "").slice(0, 120)]
  );
  const sessionToken = randomToken(32);
  await query(
    "INSERT INTO mada_web_sessions (token_hash, user_id, expires_at) VALUES ($1,$2,NULL)",
    [hashToken(sessionToken), user.rows[0].id]
  );
  setSessionCookie(res, sessionToken, base);
  if (stateRow.purpose === "mcp") {
    const txnResult = await query(
      "SELECT transaction_id, expires_at FROM mada_oauth_authorization_requests WHERE transaction_id = $1 AND expires_at > now() LIMIT 1",
      [stateRow.transaction_id]
    );
    if (!txnResult.rowCount) return html(res, 410, "<!doctype html><title>Request expired</title><p>This authorization request expired. Reconnect Mada Commander from your client.</p>");
    const nonce = randomToken(24);
    await query(
      "UPDATE mada_oauth_authorization_requests SET user_id = $2, consent_nonce_hash = $3 WHERE transaction_id = $1",
      [stateRow.transaction_id, user.rows[0].id, hashToken(nonce)]
    );
    return html(res, 200, consentPage(stateRow.transaction_id, nonce));
  }
  return redirect(res, "/", { "Set-Cookie": res.getHeader("Set-Cookie") });
}

async function handleOAuthApprove(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const body = await readBody(req);
  // The embedded ChatGPT OAuth flow can submit consent from an opaque or non-canonical browser origin.
  // Protect this state-changing endpoint with the authenticated session, user-bound transaction,
  // and one-time consent nonce instead of a brittle Origin equality check.
  const cookies = parseCookies(req.headers.cookie);
  const sessionToken = cookies[COOKIE_NAME];
  if (!sessionToken) return jsonError(res, 401, "login_required");
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required");
  const txnId = String(body.transaction_id || "");
  const nonce = String(body.consent_nonce || "");
  if (body.decision !== "approve" && body.decision !== "deny") return jsonError(res, 400, "invalid_request", "An explicit approve or deny decision is required.");
  if (body.decision === "deny") {
    const tx = await query("SELECT redirect_uri, client_state, client_id FROM mada_oauth_authorization_requests WHERE transaction_id = $1 AND user_id = $2 AND consent_nonce_hash = $3 AND expires_at > now() LIMIT 1", [txnId, user.userId, hashToken(nonce)]);
    if (tx.rowCount) {
      await query("DELETE FROM mada_oauth_authorization_requests WHERE transaction_id = $1", [txnId]);
      const u = new URL(tx.rows[0].redirect_uri);
      u.searchParams.set("error", "access_denied");
      u.searchParams.set("state", tx.rows[0].client_state);
      u.searchParams.set("iss", getPublicBase());
      return redirect(res, u.toString());
    }
    return html(res, 400, "<!doctype html><title>Cancelled</title><p>The request expired.</p>");
  }
  const code = randomToken(32);
  let consentFailureReason = "transaction_expired_or_nonce_mismatch";
  const created = await withTransaction(async db => {
    const tx = await db.query(
      "SELECT * FROM mada_oauth_authorization_requests WHERE transaction_id = $1 AND user_id = $2 AND consent_nonce_hash = $3 AND expires_at > now() FOR UPDATE",
      [txnId, user.userId, hashToken(nonce)]
    );
    if (!tx.rowCount) return null;
    const row = tx.rows[0];

    // The authorization endpoint already validated this client ID + redirect URI before
    // creating the transaction. Do not re-fetch remote CIMD metadata while holding a DB lock;
    // transient network/timeout failures here incorrectly reject an otherwise valid consent.
    const staticClientAllowed = allowedList("MADA_OAUTH_CLIENT_IDS").includes(row.client_id)
      && allowedList("MADA_OAUTH_REDIRECT_URIS").includes(row.redirect_uri);
    const dynamicChatGptClientAllowed = CIMD_ENABLED
      && isTrustedCimdUrl(row.client_id)
      && isApprovedChatGptRedirect(row.redirect_uri);
    if (!staticClientAllowed && !dynamicChatGptClientAllowed) {
      consentFailureReason = "client_redirect_not_allowed";
      return null;
    }

    await db.query(
      "INSERT INTO mada_oauth_codes (code_hash, user_id, client_id, redirect_uri, code_challenge, scope, resource, expires_at) VALUES ($1,$2,$3,$4,$5,$6,$7,now() + ($8 * interval '1 second'))",
      [hashToken(code), user.userId, row.client_id, row.redirect_uri, row.code_challenge, row.scope, row.resource, AUTH_CODE_TTL_SECONDS]
    );
    await db.query("DELETE FROM mada_oauth_authorization_requests WHERE transaction_id = $1", [txnId]);
    consentFailureReason = "";
    return row;
  });
  if (!created) {
    console.warn("Mada Commander OAuth consent validation failed", { reason: consentFailureReason });
    return html(res, 400, "<!doctype html><title>Invalid request</title><p>The authorization request expired or failed validation. Start again from ChatGPT.</p>");
  }
  const target = new URL(created.redirect_uri);
  target.searchParams.set("code", code);
  target.searchParams.set("state", created.client_state);
  target.searchParams.set("iss", getPublicBase());
  return redirect(res, target.toString());
}

async function handleOAuthToken(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (!await rateLimit(requesterKey(req, "oauth-token"), 30, 300)) return jsonError(res, 429, "rate_limited");
  const body = await readBody(req);
  const base = getPublicBase();
  const clientId = String(body.client_id || "");
  const isRefreshGrant = body.grant_type === "refresh_token";
  // Authorization-code grants validate the current client metadata. An existing,
  // unexpired refresh token proves the previously validated client binding, so renewal
  // must not depend on a fresh remote metadata fetch succeeding.
  if (!clientId || clientId.length > 2048 || (!isRefreshGrant && !await clientIdAllowed(clientId))) {
    return jsonError(res, 400, "invalid_client", "Client ID is neither on the configured allowlist nor a valid OpenAI client metadata document.");
  }
  if (body.grant_type === "authorization_code") {
    const code = String(body.code || "");
    const redirectUri = String(body.redirect_uri || "");
    const verifier = String(body.code_verifier || "");
    if (!code || !redirectUri || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) return jsonError(res, 400, "invalid_grant");
    const result = await withTransaction(async db => {
      const found = await db.query(
        "SELECT * FROM mada_oauth_codes WHERE code_hash = $1 AND expires_at > now() FOR UPDATE",
        [hashToken(code)]
      );
      if (!found.rowCount) return { ok: false };
      const row = found.rows[0];
      if (row.client_id !== clientId || row.redirect_uri !== redirectUri || row.resource !== mcpResourceUrl()
        || body.resource !== row.resource || !safeEqual(row.code_challenge, pkceChallenge(verifier))) return { ok: false };
      const used = await db.query("DELETE FROM mada_oauth_codes WHERE code_hash = $1 RETURNING code_hash", [hashToken(code)]);
      if (!used.rowCount) return { ok: false };
      const accessToken = randomToken(32);
      const refreshToken = randomToken(48);
      await db.query(
        "INSERT INTO mada_oauth_access_tokens (token_hash, user_id, client_id, scope, resource, expires_at) VALUES ($1,$2,$3,$4,$5,now() + ($6 * interval '1 second'))",
        [hashToken(accessToken), row.user_id, row.client_id, row.scope, row.resource, ACCESS_TOKEN_TTL_SECONDS]
      );
      await db.query(
        "INSERT INTO mada_oauth_refresh_tokens (token_hash, user_id, client_id, scope, resource, expires_at) VALUES ($1,$2,$3,$4,$5,NULL)",
        [hashToken(refreshToken), row.user_id, row.client_id, row.scope, row.resource]
      );
      return { ok: true, accessToken, refreshToken, scope: row.scope.join(" "), resource: row.resource };
    });
    if (!result.ok) return jsonError(res, 400, "invalid_grant", "The authorization code is invalid, expired, or already used.");
    return send(res, 200, { access_token: result.accessToken, token_type: "Bearer", expires_in: ACCESS_TOKEN_TTL_SECONDS, refresh_token: result.refreshToken, scope: result.scope, resource: result.resource });
  }
  if (body.grant_type === "refresh_token") {
    const refreshToken = String(body.refresh_token || "");
    if (!refreshToken) return jsonError(res, 400, "invalid_grant");
    const result = await withTransaction(async db => {
      const found = await db.query(
        "SELECT * FROM mada_oauth_refresh_tokens WHERE token_hash = $1 AND (expires_at IS NULL OR expires_at > now()) FOR UPDATE",
        [hashToken(refreshToken)]
      );
      if (!found.rowCount) return { ok: false };
      const row = found.rows[0];
      if (row.client_id !== clientId || row.resource !== mcpResourceUrl() || body.resource !== row.resource) return { ok: false };
      const used = await db.query("DELETE FROM mada_oauth_refresh_tokens WHERE token_hash = $1 RETURNING token_hash", [hashToken(refreshToken)]);
      if (!used.rowCount) return { ok: false };
      const accessToken = randomToken(32);
      const nextRefreshToken = randomToken(48);
      await db.query(
        "INSERT INTO mada_oauth_access_tokens (token_hash, user_id, client_id, scope, resource, expires_at) VALUES ($1,$2,$3,$4,$5,now() + ($6 * interval '1 second'))",
        [hashToken(accessToken), row.user_id, row.client_id, row.scope, row.resource, ACCESS_TOKEN_TTL_SECONDS]
      );
      await db.query(
        "INSERT INTO mada_oauth_refresh_tokens (token_hash, user_id, client_id, scope, resource, expires_at) VALUES ($1,$2,$3,$4,$5,NULL)",
        [hashToken(nextRefreshToken), row.user_id, row.client_id, row.scope, row.resource]
      );
      return { ok: true, accessToken, refreshToken: nextRefreshToken, scope: row.scope.join(" "), resource: row.resource };
    });
    if (!result.ok) return jsonError(res, 400, "invalid_grant", "The refresh token is invalid, expired, or already used.");
    return send(res, 200, { access_token: result.accessToken, token_type: "Bearer", expires_in: ACCESS_TOKEN_TTL_SECONDS, refresh_token: result.refreshToken, scope: result.scope, resource: result.resource });
  }
  return jsonError(res, 400, "unsupported_grant_type");
}

async function handleOAuthRevoke(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (!await rateLimit(requesterKey(req, "oauth-revoke"), 30, 300)) return jsonError(res, 429, "rate_limited");
  const body = await readBody(req);
  const clientId = String(body.client_id || "");
  if (!await clientIdAllowed(clientId)) return jsonError(res, 400, "invalid_client");
  const token = String(body.token || "");
  if (token) {
    await query("DELETE FROM mada_oauth_access_tokens WHERE token_hash = $1 AND client_id = $2", [hashToken(token), clientId]);
    await query("DELETE FROM mada_oauth_refresh_tokens WHERE token_hash = $1 AND client_id = $2", [hashToken(token), clientId]);
  }
  // RFC 7009 intentionally returns success even when the submitted token is unknown.
  return send(res, 200, {});
}

async function handleResourceMetadata(res) {
  const base = getPublicBase();
  return send(res, 200, {
    resource: mcpResourceUrl(),
    authorization_servers: [base],
    scopes_supported: [REQUIRED_SCOPE],
    bearer_methods_supported: ["header"],
    resource_documentation: base + "/",
    resource_policy_uri: base + "/privacy.html",
    resource_tos_uri: base + "/terms.html"
  });
}

async function handleAuthServerMetadata(res) {
  const base = getPublicBase();
  return send(res, 200, {
    issuer: base,
    authorization_endpoint: base + "/oauth/authorize",
    token_endpoint: base + "/oauth/token",
    revocation_endpoint: base + "/oauth/revoke",
    response_types_supported: ["code"],
    grant_types_supported: ["authorization_code", "refresh_token"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none"],
    scopes_supported: [REQUIRED_SCOPE],
    client_id_metadata_document_supported: CIMD_ENABLED,
    authorization_response_iss_parameter_supported: true
  });
}

async function handleAuthSession(req, res) {
  if (req.method !== "GET") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const user = await sessionUser(req);
  if (!user) return send(res, 200, { authenticated: false });
  return send(res, 200, { authenticated: true, email: user.email, displayName: user.displayName });
}

async function handleOAuthConnections(req, res) {
  if (req.method !== "GET") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required");
  const r = await query(
    "SELECT client_id, max(created_at) AS last_renewed_at, count(*)::int AS token_count " +
    "FROM mada_oauth_refresh_tokens WHERE user_id = $1 AND (expires_at IS NULL OR expires_at > now()) " +
    "GROUP BY client_id ORDER BY max(created_at) DESC LIMIT 50",
    [user.userId]
  );
  return send(res, 200, { connections: r.rows.map(row => {
    let displayName = "Authorized application";
    try {
      const hostname = new URL(row.client_id).hostname;
      displayName = hostname === "chatgpt.com" ? "ChatGPT" : hostname;
    } catch {}
    return {
      clientId: row.client_id,
      displayName,
      persistent: true,
      lastRenewedAt: row.last_renewed_at ? new Date(row.last_renewed_at).toISOString() : null,
      activeTokenCount: Number(row.token_count) || 0
    };
  }) });
}

async function handleOAuthConnectionRevoke(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (req.headers.origin && req.headers.origin !== getPublicBase()) {
    return jsonError(res, 403, "access_denied", "Cross-origin connection revocation is not allowed.");
  }
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required");
  const body = await readBody(req);
  const clientId = String(body.clientId || "");
  if (!clientId || clientId.length > 2048) return jsonError(res, 400, "invalid_request", "An authorized client ID is required.");
  const deleted = await withTransaction(async db => {
    const refresh = await db.query(
      "DELETE FROM mada_oauth_refresh_tokens WHERE user_id = $1 AND client_id = $2 RETURNING token_hash",
      [user.userId, clientId]
    );
    const access = await db.query(
      "DELETE FROM mada_oauth_access_tokens WHERE user_id = $1 AND client_id = $2 RETURNING token_hash",
      [user.userId, clientId]
    );
    return refresh.rowCount + access.rowCount;
  });
  return send(res, 200, { ok: true, revoked: deleted > 0, deletedTokenCount: deleted });
}

async function handleAccountDelete(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const base = getPublicBase();
  if (req.headers.origin && req.headers.origin !== base) return jsonError(res, 403, "access_denied", "Cross-origin account deletion is not allowed.");
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required");
  if (!await rateLimit("account-delete:" + user.userId, 3, 3600)) return jsonError(res, 429, "rate_limited");
  const body = await readBody(req);
  if (body.confirm !== "DELETE") return jsonError(res, 400, "invalid_request", "Explicit DELETE confirmation is required.");
  await withTransaction(async db => {
    const tx = await db.query("SELECT transaction_id FROM mada_oauth_authorization_requests WHERE user_id = $1", [user.userId]);
    const transactions = tx.rows.map(row => row.transaction_id).filter(Boolean);
    if (transactions.length) {
      await db.query("DELETE FROM mada_google_states WHERE transaction_id = ANY($1::text[])", [transactions]);
    }
    await db.query("DELETE FROM mada_public_users WHERE id = $1", [user.userId]);
    await db.query("DELETE FROM mada_public_rate_limits WHERE key_hash = $1 OR key_hash = $2 OR key_hash = $3", [
      hashToken("mcp-tool:" + user.userId),
      hashToken("pair-code:" + user.userId),
      hashToken("account-delete:" + user.userId)
    ]);
  });
  clearSessionCookie(res, base);
  return send(res, 200, { ok: true, deleted: true, scope: "Mada Commander public-service account data only" });
}

async function handleOAuthLogout(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const base = getPublicBase();
  if (req.headers.origin && req.headers.origin !== base) return jsonError(res, 403, "access_denied", "Cross-origin logout is not allowed.");
  const cookies = parseCookies(req.headers.cookie);
  const token = cookies[COOKIE_NAME];
  if (token) await query("DELETE FROM mada_web_sessions WHERE token_hash = $1", [hashToken(token)]);
  const secure = new URL(base).protocol === "https:" ? "; Secure" : "";
  return send(res, 200, { ok: true }, "application/json; charset=utf-8", {
    "Set-Cookie": COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + secure
  });
}

async function handleAgentList(req, res) {
  if (req.method !== "GET") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required");
  const r = await query(
    "SELECT id, name, last_seen_at, created_at FROM mada_public_agents WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 30",
    [user.userId]
  );
  return send(res, 200, { agents: r.rows.map(a => ({
    agentId: a.id,
    name: a.name,
    connected: Boolean(a.last_seen_at && Date.now() - new Date(a.last_seen_at).getTime() <= 90_000),
    lastSeenAt: a.last_seen_at ? new Date(a.last_seen_at).toISOString() : null,
    createdAt: new Date(a.created_at).toISOString()
  })) });
}

async function handleAgentRevoke(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (req.headers.origin && req.headers.origin !== getPublicBase()) return jsonError(res, 403, "access_denied", "Cross-origin agent revocation is not allowed.");
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required");
  const body = await readBody(req);
  const agentId = String(body.agentId || "");
  if (!/^[0-9a-f-]{36}$/i.test(agentId)) return jsonError(res, 400, "invalid_request");
  const revoked = await withTransaction(async db => {
    const r = await db.query(
      "UPDATE mada_public_agents SET revoked_at = now() WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL RETURNING id",
      [agentId, user.userId]
    );
    if (!r.rowCount) return false;
    await db.query(
      "UPDATE mada_public_jobs SET status = 'cancelled', error_summary = 'Agent revoked by account owner.', finished_at = now() WHERE agent_id = $1 AND user_id = $2 AND status IN ('queued','running')",
      [agentId, user.userId]
    );
    return true;
  });
  if (!revoked) return jsonError(res, 404, "not_found");
  return send(res, 200, { ok: true, revoked: true, queuedJobsCancelled: true });
}

async function handleAgentPairCode(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (req.headers.origin && req.headers.origin !== getPublicBase()) return jsonError(res, 403, "access_denied", "Cross-origin pairing is not allowed.");
  const user = await sessionUser(req);
  if (!user) return jsonError(res, 401, "login_required", "Sign in with Google before pairing a device.");
  if (!await rateLimit("pair-code:" + user.userId, 5, 600)) return jsonError(res, 429, "rate_limited");
  const pairCode = randomToken(12).toUpperCase();
  const r = await query(
    "INSERT INTO mada_agent_pair_codes (code_hash, user_id, expires_at) VALUES ($1,$2,now() + ($3 * interval '1 second')) RETURNING expires_at",
    [hashToken(pairCode), user.userId, PAIR_CODE_TTL_SECONDS]
  );
  return send(res, 201, { pairCode, expiresAt: new Date(r.rows[0].expires_at).toISOString(), useOnce: true });
}

async function handleAgentEnroll(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  if (!await rateLimit(requesterKey(req, "agent-enroll"), 10, 600)) return jsonError(res, 429, "rate_limited");
  const body = await readBody(req);
  const code = String(body.pairCode || "").trim().toUpperCase();
  const name = String(body.name || "Mada Commander agent").trim().slice(0, 80);
  if (!/^[A-Z0-9_-]{12,64}$/.test(code) || !name || /[\u0000-\u001f\u007f]/.test(name)) return jsonError(res, 400, "invalid_request");
  const agentId = crypto.randomUUID();
  const agentToken = randomToken(32);
  const inserted = await withTransaction(async db => {
    const pair = await db.query(
      "DELETE FROM mada_agent_pair_codes WHERE code_hash = $1 AND expires_at > now() RETURNING user_id",
      [hashToken(code)]
    );
    if (!pair.rowCount) return false;
    await db.query(
      "INSERT INTO mada_public_agents (id, user_id, name, token_hash) VALUES ($1,$2,$3,$4)",
      [agentId, pair.rows[0].user_id, name, hashToken(agentToken)]
    );
    return true;
  });
  if (!inserted) return jsonError(res, 400, "invalid_grant", "Pairing code is invalid, expired, or already used.");
  return send(res, 201, { agentId, agentToken, pollIntervalSeconds: 15, tokenShownOnce: true });
}

async function handleAgentHeartbeat(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const agent = await agentUser(req);
  if (!agent) return jsonError(res, 401, "invalid_token");
  if (!await rateLimit("agent-heartbeat:" + agent.agentId, 90, 60)) return jsonError(res, 429, "rate_limited");
  const body = await readBody(req);
  if (!Array.isArray(body.projects) || body.projects.length > 40) return jsonError(res, 400, "invalid_request", "The agent must send a project array with at most 40 entries.");
  const projects = body.projects;
  const safe = [];
  for (const p of projects) {
    if (!p || !isSafeProjectAlias(p.projectId) || !isSafeProjectLabel(p.label)) continue;
    safe.push({
      id: p.projectId,
      label: p.label.trim(),
      branch: typeof p.branch === "string" ? redactSensitive(p.branch).slice(0, 100).replace(/[\u0000-\u001f\u007f]/g, "") : "",
      head: typeof p.head === "string" ? p.head.slice(0, 40).replace(/[^a-fA-F0-9]/g, "") : ""
    });
  }
  await withTransaction(async db => {
    await db.query("UPDATE mada_public_agents SET last_seen_at = now() WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL", [agent.agentId, agent.userId]);
    const currentProjectIds = safe.map(p => p.id);
    await db.query("DELETE FROM mada_public_projects WHERE agent_id = $1 AND NOT (project_id = ANY($2::text[]))", [agent.agentId, currentProjectIds]);
    for (const p of safe) {
      await db.query(
        "INSERT INTO mada_public_projects (agent_id, project_id, label, branch, head, updated_at) VALUES ($1,$2,$3,$4,$5,now()) " +
        "ON CONFLICT (agent_id, project_id) DO UPDATE SET label = EXCLUDED.label, branch = EXCLUDED.branch, head = EXCLUDED.head, updated_at = now()",
        [agent.agentId, p.id, p.label, p.branch, p.head]
      );
    }
  });
  return send(res, 200, { ok: true, registeredProjects: safe.length, pathsUploaded: false });
}

async function handleAgentPoll(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const agent = await agentUser(req);
  if (!agent) return jsonError(res, 401, "invalid_token");
  if (!await rateLimit("agent-poll:" + agent.agentId, 90, 60)) return jsonError(res, 429, "rate_limited");
  const job = await withTransaction(async db => {
    await db.query("UPDATE mada_public_agents SET last_seen_at = now() WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL", [agent.agentId, agent.userId]);
    const next = await db.query(
      "SELECT id, project_id, profile, request FROM mada_public_jobs WHERE agent_id = $1 AND user_id = $2 AND status = 'queued' ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
      [agent.agentId, agent.userId]
    );
    if (!next.rowCount) return null;
    const row = next.rows[0];
    const updated = await db.query(
      "UPDATE mada_public_jobs SET status = 'running', started_at = now() WHERE id = $1 AND agent_id = $2 AND user_id = $3 AND status = 'queued' RETURNING id",
      [row.id, agent.agentId, agent.userId]
    );
    if (!updated.rowCount) return null;
    return { jobId: row.id, projectId: row.project_id, profile: row.profile, request: row.request || {} };
  });
  return send(res, 200, { job });
}

function sanitizeResult(profile, raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  if (profile === "git_status") {
    return {
      branch: typeof raw.branch === "string" ? redactSensitive(raw.branch).slice(0, 100) : "",
      head: typeof raw.head === "string" ? String(raw.head).replace(/[^a-fA-F0-9]/g, "").slice(0, 40) : "",
      dirtyCount: Number.isInteger(raw.dirtyCount) ? Math.max(0, Math.min(10_000, raw.dirtyCount)) : 0
    };
  }
  if (profile === "toolchain") {
    return {
      node: redactSensitive(String(raw.node || "")).slice(0, 40),
      npm: redactSensitive(String(raw.npm || "")).slice(0, 40),
      git: redactSensitive(String(raw.git || "")).slice(0, 60),
      javaAvailable: Boolean(raw.javaAvailable),
      gradleWrapperPresent: Boolean(raw.gradleWrapperPresent)
    };
  }
  if (profile === "inventory") {
    return {
      hasGit: Boolean(raw.hasGit),
      hasPackageJson: Boolean(raw.hasPackageJson),
      hasGradleWrapper: Boolean(raw.hasGradleWrapper),
      hasReadme: Boolean(raw.hasReadme),
      topLevelFileCount: Number.isInteger(raw.topLevelFileCount) ? Math.max(0, Math.min(1000, raw.topLevelFileCount)) : 0
    };
  }
  if (profile === "workspace_list") {
    const entries = Array.isArray(raw.entries) ? raw.entries.slice(0, 100).map(entry => ({
      name: redactSensitive(String(entry?.name || "")).slice(0, 160),
      type: ["file","directory","other"].includes(entry?.type) ? entry.type : "other"
    })) : [];
    return {
      relativeDirectory: redactSensitive(String(raw.relativeDirectory || "")).slice(0, 240),
      entries,
      totalCount: Number.isInteger(raw.totalCount) ? Math.max(0, Math.min(100000, raw.totalCount)) : entries.length,
      truncated: Boolean(raw.truncated)
    };
  }
  if (profile === "workspace_read") {
    if (typeof raw.content !== "string" || typeof raw.relativeFile !== "string") return null;
    return {
      relativeFile: redactSensitive(raw.relativeFile).slice(0, 240),
      content: redactSensitive(raw.content).slice(0, 40000),
      bytes: Number.isInteger(raw.bytes) ? Math.max(0, Math.min(1_000_000, raw.bytes)) : 0,
      truncated: Boolean(raw.truncated)
    };
  }
  if (profile === "workspace_write") {
    if (typeof raw.relativeFile !== "string" || !/^[a-f0-9]{64}$/i.test(String(raw.sha256 || ""))) return null;
    return {
      relativeFile: redactSensitive(raw.relativeFile).slice(0, 240),
      bytes: Number.isInteger(raw.bytes) ? Math.max(0, Math.min(120000, raw.bytes)) : 0,
      sha256: String(raw.sha256),
      created: Boolean(raw.created)
    };
  }
  if (profile === "run_command") {
    if (typeof raw.program !== "string" || !Array.isArray(raw.args) || typeof raw.cwd !== "string" || !Number.isInteger(raw.exitCode)) return null;
    return {
      program: redactSensitive(raw.program).slice(0, 80),
      args: raw.args.slice(0, 40).filter(arg => typeof arg === "string").map(arg => redactSensitive(arg).slice(0, 2000)),
      cwd: redactSensitive(raw.cwd).slice(0, 240),
      exitCode: Math.max(-1, Math.min(255, raw.exitCode)),
      timedOut: Boolean(raw.timedOut),
      durationMs: Number.isInteger(raw.durationMs) ? Math.max(0, Math.min(900000, raw.durationMs)) : 0,
      stdout: redactSensitive(String(raw.stdout || "")).slice(-6000),
      stderr: redactSensitive(String(raw.stderr || "")).slice(-2500)
    };
  }
  if (profile === "run_task") {
    const task = ["git_status","git_diff_stat","npm_test","npm_lint","npm_build","android_assemble_debug","android_test"].includes(raw.task) ? raw.task : "";
    if (!task || !Number.isInteger(raw.exitCode)) return null;
    return {
      task,
      exitCode: Math.max(-1, Math.min(255, raw.exitCode)),
      timedOut: Boolean(raw.timedOut),
      durationMs: Number.isInteger(raw.durationMs) ? Math.max(0, Math.min(600000, raw.durationMs)) : 0,
      stdout: redactSensitive(String(raw.stdout || "")).slice(-6000),
      stderr: redactSensitive(String(raw.stderr || "")).slice(-2500)
    };
  }
  if (profile === "opera_status") {
    return {
      connected: Boolean(raw.connected),
      browser: redactSensitive(String(raw.browser || "")).slice(0, 120),
      protocolVersion: String(raw.protocolVersion || "").slice(0, 30),
      tabsCount: Number.isInteger(raw.tabsCount) ? Math.max(0, Math.min(10000, raw.tabsCount)) : 0
    };
  }
  if (profile === "opera_tabs") {
    const tabs = Array.isArray(raw.tabs) ? raw.tabs.slice(0,40).map(tab => ({
      tabId: /^[A-Za-z0-9_-]{1,100}$/.test(String(tab?.tabId || "")) ? String(tab.tabId) : "",
      title: redactSensitive(String(tab?.title || "")).slice(0,160),
      address: redactSensitive(String(tab?.address || "")).replace(/[?#].*$/, "").replace(/\/[^/]{49,}/g, "/[redacted]").slice(0,400)
    })).filter(tab => tab.tabId) : [];
    return {
      tabs,
      totalCount: Number.isInteger(raw.totalCount) ? Math.max(tabs.length, Math.min(10000, raw.totalCount)) : tabs.length,
      truncated: Boolean(raw.truncated)
    };
  }
  if (profile === "opera_navigate") {
    if (!/^[A-Za-z0-9_-]{1,100}$/.test(String(raw.tabId || "")) || typeof raw.address !== "string") return null;
    return {
      tabId: String(raw.tabId),
      address: redactSensitive(String(raw.address)).replace(/[?#].*$/, "").slice(0,400),
      navigated: Boolean(raw.navigated)
    };
  }
  if (profile === "opera_action") {
    const action = ["inspect","click_text","click_selector","fill_selector","select_value","toggle_checked"].includes(raw.action) ? raw.action : "";
    if (!action || !/^[A-Za-z0-9_-]{1,100}$/.test(String(raw.tabId || ""))) return null;
    const value = raw.result && typeof raw.result === "object" ? raw.result : {};
    const result = {};
    if (typeof value.title === "string") result.title = redactSensitive(value.title).slice(0,160);
    if (typeof value.readyState === "string") result.readyState = value.readyState.slice(0,30);
    if (Array.isArray(value.controls)) result.controls = value.controls.slice(0,60).map(control => ({
      tag: String(control?.tag || "").slice(0,20),
      kind: String(control?.kind || "").slice(0,40),
      label: redactSensitive(String(control?.label || "")).slice(0,180),
      disabled: Boolean(control?.disabled),
      ...(typeof control?.checked === "boolean" ? { checked: control.checked } : {})
    }));
    if (typeof value.ok === "boolean") result.ok = value.ok;
    if (typeof value.scheduled === "boolean") result.scheduled = value.scheduled;
    if (typeof value.reason === "string") result.reason = redactSensitive(value.reason).slice(0,100);
    if (typeof value.clicked === "string") result.clicked = redactSensitive(value.clicked).slice(0,180);
    if (typeof value.updated === "boolean") result.updated = value.updated;
    if (typeof value.selected === "string") result.selected = redactSensitive(value.selected).slice(0,100);
    if (typeof value.checked === "boolean") result.checked = value.checked;
    return {
      tabId: String(raw.tabId),
      action,
      address: redactSensitive(String(raw.address || "")).replace(/[?#].*$/, "").slice(0,400),
      result
    };
  }
  return null;
}

async function handleAgentComplete(req, res) {
  if (req.method !== "POST") return jsonError(res, 405, "method_not_allowed");
  requireDatabase();
  const agent = await agentUser(req);
  if (!agent) return jsonError(res, 401, "invalid_token");
  if (!await rateLimit("agent-complete:" + agent.agentId, 90, 60)) return jsonError(res, 429, "rate_limited");
  const body = await readBody(req);
  const jobId = String(body.jobId || "");
  const status = body.status === "succeeded" ? "succeeded" : body.status === "failed" ? "failed" : "";
  if (!/^[0-9a-f-]{36}$/i.test(jobId) || !status) return jsonError(res, 400, "invalid_request");
  const r = await query("SELECT profile FROM mada_public_jobs WHERE id = $1 AND agent_id = $2 AND user_id = $3 AND status = 'running' LIMIT 1", [jobId, agent.agentId, agent.userId]);
  if (!r.rowCount) return jsonError(res, 404, "not_found");
  const result = status === "succeeded" ? sanitizeResult(r.rows[0].profile, body.result) : null;
  if (status === "succeeded" && !result) return jsonError(res, 400, "invalid_result");
  const errorSummary = status === "failed" ? "The local diagnostic failed. Check the local agent log for details." : "";
  const updated = await query(
    "UPDATE mada_public_jobs SET status = $3, result = $4::jsonb, error_summary = $5, finished_at = now() WHERE id = $1 AND agent_id = $2 AND user_id = $6 AND status = 'running' RETURNING id",
    [jobId, agent.agentId, status, result ? JSON.stringify(result) : null, errorSummary, agent.userId]
  );
  return send(res, updated.rowCount ? 200 : 409, { ok: Boolean(updated.rowCount) });
}

async function handleDomainChallenge(res) {
  const token = String(process.env.OPENAI_APPS_CHALLENGE_TOKEN || "").trim();
  if (!token || /[\r\n]/.test(token)) return send(res, 503, { error: "domain_challenge_not_configured" });
  return send(res, 200, token, "text/plain; charset=utf-8", { "Cache-Control": "no-store" });
}

async function handleHealth(res) {
  const base = publicBaseOrNull();
  const checks = {
    publicBaseConfigured: Boolean(base),
    databaseConfigured,
    googleOAuthConfigured: Boolean(process.env.GOOGLE_OAUTH_CLIENT_ID && process.env.GOOGLE_OAUTH_CLIENT_SECRET),
    oauthClientModeConfigured: allowedList("MADA_OAUTH_CLIENT_IDS").length > 0 || CIMD_ENABLED,
    oauthRedirectAllowlistConfigured: allowedList("MADA_OAUTH_REDIRECT_URIS").length > 0 || CIMD_ENABLED,
    oauthCimdEnabled: CIMD_ENABLED,
    domainChallengeConfigured: Boolean(process.env.OPENAI_APPS_CHALLENGE_TOKEN)
  };
  if (databaseConfigured) {
    try {
      const r = await query(
        "SELECT to_regclass('public.mada_public_users') AS users_table, " +
        "to_regclass('public.mada_web_sessions') AS sessions_table, " +
        "to_regclass('public.mada_oauth_authorization_requests') AS oauth_requests_table, " +
        "to_regclass('public.mada_oauth_access_tokens') AS access_tokens_table, " +
        "to_regclass('public.mada_public_agents') AS agents_table, " +
        "to_regclass('public.mada_public_projects') AS projects_table, " +
        "to_regclass('public.mada_public_jobs') AS jobs_table, " +
        "to_regclass('public.mada_public_rate_limits') AS rate_limits_table"
      );
      checks.schemaReady = Object.values(r.rows[0] || {}).every(Boolean);
    } catch { checks.schemaReady = false; }
  } else checks.schemaReady = false;
  const ready = Object.entries(checks).filter(([key]) => key !== "domainChallengeConfigured").every(([, value]) => value);
  return send(res, ready ? 200 : 503, { service: "mada-commander-public-mcp", displayName: "Mada Commander", ready, checks });
}

const CROSS_ORIGIN_PUBLIC_ROUTES = new Set([
  "mcp",
  "oauth-resource",
  "oauth-server",
  "oauth-authorize",
  "oauth-token",
  "oauth-revoke",
  "google-start",
  "google-callback",
  "oauth-approve",
  "domain-challenge"
]);

function cors(req, res, base, route) {
  const origin = req.headers.origin;
  if (!origin) return true;
  const publicCrossOriginRoute = CROSS_ORIGIN_PUBLIC_ROUTES.has(route);
  const allowed = new Set([base, "https://chatgpt.com", "https://chat.openai.com", ...allowedList("MADA_CORS_ORIGINS")]);
  // Public MCP and OAuth protocol endpoints must support embedded browser clients whose
  // Origin may vary. Session, account deletion, pairing, and agent-management APIs stay
  // on the explicit allowlist and retain their route-level authentication/CSRF checks.
  if (!publicCrossOriginRoute && !allowed.has(origin)) {
    console.warn("Mada Commander CORS rejection", {
      method: req.method,
      path: String(req.url || "/").split("?")[0].slice(0, 180),
      route,
      origin: String(origin).slice(0, 180)
    });
    return false;
  }
  res.setHeader("Access-Control-Allow-Origin", publicCrossOriginRoute ? "*" : origin);
  if (!publicCrossOriginRoute) res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, MCP-Protocol-Version, Accept");
  res.setHeader("Access-Control-Max-Age", "600");
  return true;
}

async function serveStatic(res, file) {
  const allowed = new Set(["index.html", "privacy.html", "terms.html", "support.html"]);
  if (!allowed.has(file)) return send(res, 404, { error: "not_found" });
  const text = await fs.readFile(path.join(PACKAGE_ROOT, file), "utf8");
  return html(res, 200, text);
}

export async function handleRequest(req, res) {
  const url = requestUrl(req);
  const route = routeFor(url);
  const base = publicBaseOrNull();
  if (base) {
    if (!cors(req, res, base, route)) return send(res, 403, { error: "origin_not_allowed" });
  }
  if (req.method === "OPTIONS") {
    const publicCrossOriginRoute = CROSS_ORIGIN_PUBLIC_ROUTES.has(route);
    const corsHeaders = base ? {
      "Access-Control-Allow-Origin": publicCrossOriginRoute ? "*" : (req.headers.origin || base),
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Authorization, Content-Type, MCP-Protocol-Version, Accept",
      "Access-Control-Max-Age": "600",
      ...(publicCrossOriginRoute ? {} : { "Vary": "Origin" })
    } : {};
    res.writeHead(204, corsHeaders);
    return res.end();
  }
  try {
    await renewPersistentWebSession(req, res, route, base);
    switch (route) {
      case "home": return await serveStatic(res, "index.html");
      case "privacy": return await serveStatic(res, "privacy.html");
      case "terms": return await serveStatic(res, "terms.html");
      case "support": return await serveStatic(res, "support.html");
      case "health": return await handleHealth(res);
      case "domain-challenge": return await handleDomainChallenge(res);
      case "oauth-resource": return await handleResourceMetadata(res);
      case "oauth-server": return await handleAuthServerMetadata(res);
      case "mcp": return await handleMcp(req, res, url);
      case "oauth-authorize": return await handleOAuthAuthorize(req, res, url);
      case "oauth-token": return await handleOAuthToken(req, res);
      case "oauth-revoke": return await handleOAuthRevoke(req, res);
      case "google-start": return await handleGoogleStart(req, res, url);
      case "google-callback": return await handleGoogleCallback(req, res, url);
      case "oauth-approve": return await handleOAuthApprove(req, res);
      case "auth-session": return await handleAuthSession(req, res);
      case "auth-logout": return await handleOAuthLogout(req, res);
      case "oauth-connections": return await handleOAuthConnections(req, res);
      case "oauth-connections-revoke": return await handleOAuthConnectionRevoke(req, res);
      case "account-delete": return await handleAccountDelete(req, res);
      case "agent-list": return await handleAgentList(req, res);
      case "agent-revoke": return await handleAgentRevoke(req, res);
      case "agent-pair-code": return await handleAgentPairCode(req, res);
      case "agent-enroll": return await handleAgentEnroll(req, res);
      case "agent-heartbeat": return await handleAgentHeartbeat(req, res);
      case "agent-poll": return await handleAgentPoll(req, res);
      case "agent-complete": return await handleAgentComplete(req, res);
      default: return send(res, 404, { error: "not_found" });
    }
  } catch (error) {
    const status = Number(error?.statusCode) || (error?.code === "MADA_DB_NOT_CONFIGURED" ? 503 : 500);
    if (status >= 500) console.error("Mada Commander public route failed:", route, String(error?.code || "INTERNAL_ERROR"));
    return send(res, status, {
      error: status === 503 ? "service_not_configured" : status === 413 ? "payload_too_large" : "request_failed",
      error_description: status === 503 ? "Mada Commander public service configuration is incomplete." : status === 429 ? "Request rate limit reached." : "The request could not be completed."
    });
  }
}

export default async function vercelHandler(req, res) {
  return handleRequest(req, res);
}
