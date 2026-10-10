import http from "node:http";
import { handleRequest } from "./api/router.mjs";

const port = Number(process.env.PORT || 8789);
const server = http.createServer((req, res) => {
  handleRequest(req, res).catch(error => {
    console.error("Mada Commander local server error:", String(error?.code || "INTERNAL_ERROR"));
    if (!res.headersSent) {
      res.writeHead(500, { "Content-Type": "application/json", "Cache-Control": "no-store" });
      res.end(JSON.stringify({ error: "request_failed" }));
    } else {
      try { res.end(); } catch {}
    }
  });
});
server.listen(port, "127.0.0.1", () => {
  process.stdout.write("Mada Commander public MCP dev server listening on 127.0.0.1:" + port + "\n");
});
process.on("SIGTERM", () => server.close(() => process.exit(0)));
process.on("SIGINT", () => server.close(() => process.exit(0)));
