import fs from "node:fs";
import path from "node:path";

describe("VPS Nginx proxy contract for game SSE", () => {
  const nginx = fs.readFileSync(
    path.resolve(__dirname, "../deploy/vps/nginx.conf"),
    "utf8"
  );
  const server = fs.readFileSync(
    path.resolve(__dirname, "../src/server.ts"),
    "utf8"
  );

  test("keeps HTTP/1.1 upstream streaming without enabling protocol upgrades", () => {
    expect(nginx).toContain("proxy_http_version 1.1;");
    expect(nginx).not.toMatch(/proxy_set_header\s+Upgrade\b/i);
    expect(nginx).not.toMatch(/proxy_set_header\s+Connection\s+["']upgrade["']/i);
  });

  test("preserves the backend's SSE flush and buffering controls", () => {
    expect(server).toContain('res.setHeader("Content-Type","text/event-stream; charset=utf-8")');
    expect(server).toContain('res.setHeader("X-Accel-Buffering","no")');
    expect(server).toContain("},25_000)");
  });
});
