import crypto from "node:crypto";

export const REQUIRED_SCOPE = "mada:workspace";
export const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
// Refresh tokens have no automatic expiry: users revoke connected apps from the account dashboard.
export const WEB_SESSION_TTL_SECONDS = 400 * 24 * 60 * 60;
export const AUTH_CODE_TTL_SECONDS = 90;
export const GOOGLE_STATE_TTL_SECONDS = 5 * 60;
export const PAIR_CODE_TTL_SECONDS = 10 * 60;

export function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString("base64url");
}

export function hashToken(value) {
  return crypto.createHash("sha256").update(String(value), "utf8").digest("hex");
}

export function pkceChallenge(verifier) {
  return crypto.createHash("sha256").update(String(verifier), "ascii").digest("base64url");
}

export function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

export function allowedList(name) {
  return String(process.env[name] || "").split(",").flatMap(s => s.split(String.fromCharCode(10))).map(s => s.trim()).filter(Boolean);
}

export function getPublicBase() {
  const configured = process.env.MADA_PUBLIC_BASE_URL;
  if (configured) {
    const url = new URL(configured);
    if (url.pathname !== "/" || url.search || url.hash || url.username || url.password) {
      throw new Error("MADA_PUBLIC_BASE_URL must be an origin without a path, query, or credentials.");
    }
    if (process.env.NODE_ENV === "production" && url.protocol !== "https:") {
      throw new Error("MADA_PUBLIC_BASE_URL must use HTTPS in production.");
    }
    return url.origin;
  }
  if (process.env.NODE_ENV === "production") throw new Error("MADA_PUBLIC_BASE_URL is required in production.");
  return "http://127.0.0.1:" + String(process.env.PORT || 8789);
}

export function mcpResourceUrl() {
  // OAuth resource identifiers use the canonical public origin; the MCP transport endpoint is /mcp.
  return getPublicBase();
}

export function clientAllowed(clientId) {
  return Boolean(clientId) && allowedList("MADA_OAUTH_CLIENT_IDS").includes(clientId);
}

export function redirectAllowed(redirectUri) {
  return Boolean(redirectUri) && allowedList("MADA_OAUTH_REDIRECT_URIS").includes(redirectUri);
}

export function parseCookies(header) {
  const result = {};
  for (const chunk of String(header || "").split(";")) {
    const pos = chunk.indexOf("=");
    if (pos > 0) result[chunk.slice(0, pos).trim()] = decodeURIComponent(chunk.slice(pos + 1).trim());
  }
  return result;
}

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, c => ({
    "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"
  })[c]);
}

export function redactSensitive(value) {
  let text = typeof value === "string" ? value : JSON.stringify(value ?? "");
  text = text
    .replace(/(authorization|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|password|pairing[_ -]?code|cookie)(\s*["']?\s*[:=]\s*["']?)[^\s,"'&}]+/gi, "$1$2[REDACTED]")
    .replace(/\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,})\b/g, "[REDACTED_TOKEN]")
    .replace(/Bearer\s+[A-Za-z0-9._~+/-]+=*/gi, "Bearer [REDACTED]");
  return text.slice(0, 6000);
}

export function setSecurityHeaders(res) {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  res.setHeader("Content-Security-Policy", "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self' https://accounts.google.com; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");
  if (process.env.NODE_ENV === "production") {
    res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  }
}

export function parseBearer(header) {
  const match = /^Bearer\s+([^\s]+)$/i.exec(String(header || ""));
  return match?.[1] || "";
}

export function parseBodyText(text, contentType = "") {
  if (/application\/x-www-form-urlencoded/i.test(contentType)) {
    return Object.fromEntries(new URLSearchParams(text));
  }
  try {
    const parsed = JSON.parse(text || "{}");
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

export function isSafeProjectAlias(id) {
  return typeof id === "string" && /^[a-z][a-z0-9_-]{0,39}$/.test(id);
}

export function isSafeProjectLabel(label) {
  return typeof label === "string" && label.trim().length >= 1 && label.trim().length <= 80
    && !/[\u0000-\u001f\u007f]/.test(label);
}
