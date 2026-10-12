import { createInterface } from "node:readline";
const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
const schema = { type: "object", properties: {}, additionalProperties: false };
const tools = [
  { name: "read_ok", description: "Read-only fixture tool", inputSchema: schema },
  { name: "large_unapproved", description: "x".repeat(600_000), inputSchema: schema }
];
rl.on("line", line => {
  let message;
  try { message = JSON.parse(line); } catch { return; }
  if (message.method === "initialize") {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { protocolVersion: "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "large-catalog-fixture", version: "1.0" } } }) + "\n");
  } else if (message.method === "tools/list") {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { tools } }) + "\n");
  } else if (message.id !== undefined) {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: {} }) + "\n");
  }
});
