import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";

const PORT = Number(process.env.PORT || 8787);
const TARGET = "https://openapiuat.airtel.mg";
const TOKEN_FILE = process.env.AIRTEL_RELAY_TOKEN_FILE ||
  "/data/data/com.termux/files/home/.airtel-relay-token";

const allowed = new Set([
  "POST /auth/oauth2/token",
  "GET /v1/rsa/encryption-keys",
]);

function loadToken() {
  return fs.readFileSync(TOKEN_FILE, "utf8").trim();
}

function authorized(req) {
  const expected = loadToken();
  const actual = String(req.headers["x-relay-token"] || "");
  if (!expected || !actual) return false;
  const a = Buffer.from(actual);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function key(method, pathname) {
  return `${method.toUpperCase()} ${pathname}`;
}

const server = http.createServer(async (req, res) => {
  try {
    const pathname = new URL(req.url || "/", "http://relay.local").pathname;
    if (req.method === "GET" && pathname === "/health") {
      res.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
      res.end("ok");
      return;
    }

    const route = key(req.method || "GET", pathname);
    const paymentRoute = req.method === "POST" && pathname === "/merchant/v2/payments/";
    const statusRoute = req.method === "GET" && /^\/standard\/v1\/payments\/[A-Za-z0-9._~-]+$/.test(pathname);
    if (!allowed.has(route) && !paymentRoute && !statusRoute) {
      res.writeHead(404);
      res.end("not found");
      return;
    }

    if (!authorized(req)) {
      res.writeHead(401);
      res.end("unauthorized");
      return;
    }

    const chunks = [];
    for await (const chunk of req) {
      if (chunks.reduce((n, c) => n + c.length, 0) + chunk.length > 1024 * 1024) {
        res.writeHead(413);
        res.end("payload too large");
        return;
      }
      chunks.push(chunk);
    }

    const headers = {};
    for (const name of [
      "accept", "content-type", "authorization",
      "x-country", "x-currency", "x-signature", "x-key"
    ]) {
      const value = req.headers[name];
      if (typeof value === "string") headers[name] = value;
    }

    const upstream = await fetch(TARGET + (req.url || "/"), {
      method: req.method,
      headers,
      body: req.method === "GET" || req.method === "HEAD" ? undefined : Buffer.concat(chunks),
    });

    const responseHeaders = {};
    for (const name of ["content-type", "cache-control"]) {
      const value = upstream.headers.get(name);
      if (value) responseHeaders[name] = value;
    }

    res.writeHead(upstream.status, responseHeaders);
    res.end(Buffer.from(await upstream.arrayBuffer()));
  } catch {
    res.writeHead(502);
    res.end("bad gateway");
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`AIRTEL_RELAY_LISTENING port=${PORT}`);
});
