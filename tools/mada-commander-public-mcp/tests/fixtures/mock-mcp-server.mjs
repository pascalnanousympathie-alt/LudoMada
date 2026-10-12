import { createInterface } from "node:readline";

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
function reply(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
}
rl.on("line", line => {
  let m;
  try { m = JSON.parse(line); } catch { return; }
  if (m.method === "initialize") {
    reply(m.id, { protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "test-fixture", version: "1.0" } });
  } else if (m.method === "tools/list") {
    reply(m.id, { tools: [
      { name: "read_ok", description: "Read-only fixture tool", inputSchema: { type: "object", properties: { query: { type: "string" } }, additionalProperties: false } },
      { name: "write_ok", description: "Mutating fixture tool", inputSchema: { type: "object", properties: {}, additionalProperties: false } }
    ] });
  } else if (m.method === "tools/call") {
    reply(m.id, { content: [{ type: "text", text: "fixture result: " + String(m.params?.arguments?.query || "ok") }], structuredContent: { ok: true } });
  } else if (m.id !== undefined) {
    reply(m.id, {});
  }
});
