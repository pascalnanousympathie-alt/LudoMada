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

const SENSITIVE_PROPERTY = /(?:^|[_\-.])(?:authorization|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|api[_-]?key|private[_-]?key|service[_-]?role[_-]?key|token|password|pairing[_-]?code|cookie|database[_-]?(?:url|uri)|connection[_-]?string|credentials?|key)(?:$|[_\-.])/i;
const REDACTION_LIMIT_MARKER = Object.freeze({
  truncated: true,
  note: "External MCP result exceeded the redaction safety limits."
});

export function redactSensitive(value) {
  let text;
  try {
    text = typeof value === "string" ? value : JSON.stringify(value ?? "");
  } catch {
    return "[REDACTED_UNSERIALIZABLE_VALUE]";
  }
  return text
    .replace(/-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----/gi, "[REDACTED_PRIVATE_KEY]")
    .replace(/\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/-]+=*/gi, match => match.split(/\s+/, 1)[0] + " [REDACTED]")
    .replace(
      /(["']?[A-Za-z0-9_.-]*(?:authorization|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|secret|api[_ -]?key|private[_ -]?key|service[_ -]?role[_ -]?key|database[_ -]?(?:url|uri)|connection[_ -]?string|credential(?:s)?|pairing[_ -]?code|cookie|password|token|(?:^|[_-])key)[A-Za-z0-9_.-]*["']?\s*[:=]\s*)(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s,"'&}]+))/gi,
      (_match, prefix, doubleQuoted, singleQuoted) => {
        if (doubleQuoted !== undefined) return prefix + '"[REDACTED]"';
        if (singleQuoted !== undefined) return prefix + "'[REDACTED]'";
        return prefix + "[REDACTED]";
      }
    )
    .replace(/^([A-Z][A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|(?:API|PRIVATE|SERVICE_ROLE)?_?KEY|DATABASE_URL|DATABASE_URI|CONNECTION_STRING|CREDENTIALS?|AUTHORIZATION|COOKIE)[A-Z0-9_]*\s*=\s*).+$/gim, "$1[REDACTED]")
    .replace(/\b(?:postgres(?:ql)?|mongodb(?:\+srv)?|redis):\/\/[^\s"'<>]+/gi, "[REDACTED_CONNECTION_STRING]")
    .replace(/\b([a-z][a-z0-9+.-]*:\/\/)[^\s/@:]+:[^\s/@]+@/gi, "$1[REDACTED]@")
    .replace(/\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sb_secret_[A-Za-z0-9_-]{16,}|npm_[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})\b/gi, "[REDACTED_TOKEN]")
    .replace(/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{8,}\b/g, "[REDACTED_JWT]")
    .slice(0, 6000);
}

export function redactSensitiveStructured(value, options = {}) {
  const maxDepth = Number.isSafeInteger(options.maxDepth) ? Math.max(1, Math.min(options.maxDepth, 24)) : 16;
  const maxNodes = Number.isSafeInteger(options.maxNodes) ? Math.max(1, Math.min(options.maxNodes, 20_000)) : 10_000;
  const maxBytes = Number.isSafeInteger(options.maxBytes) ? Math.max(256, Math.min(options.maxBytes, 1_000_000)) : 500_000;
  let nodes = 0;
  let exceeded = false;

  const visit = (input, depth) => {
    nodes += 1;
    if (nodes > maxNodes || depth > maxDepth) {
      exceeded = true;
      return null;
    }
    if (input === null || typeof input === "boolean") return input;
    if (typeof input === "number") return Number.isFinite(input) ? input : "[REDACTED_NONFINITE_NUMBER]";
    if (typeof input === "string") return redactSensitive(input);
    if (Array.isArray(input)) {
      if (input.length > maxNodes) {
        exceeded = true;
        return null;
      }
      return input.map(item => visit(item, depth + 1));
    }
    if (typeof input === "object") {
      const output = Object.create(null);
      for (const [key, child] of Object.entries(input)) {
        if (key.length > 256) {
          exceeded = true;
          break;
        }
        output[key] = SENSITIVE_PROPERTY.test(key) ? "[REDACTED]" : visit(child, depth + 1);
      }
      return output;
    }
    return "[REDACTED_UNSUPPORTED_VALUE]";
  };

  try {
    const safe = visit(value, 0);
    if (exceeded) return { ...REDACTION_LIMIT_MARKER };
    if (Buffer.byteLength(JSON.stringify(safe), "utf8") > maxBytes) return { ...REDACTION_LIMIT_MARKER };
    return safe;
  } catch {
    return { ...REDACTION_LIMIT_MARKER };
  }
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
