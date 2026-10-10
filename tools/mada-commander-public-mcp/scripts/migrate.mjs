import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { query, closePool, databaseConfigured } from "../lib/db.mjs";

if (!databaseConfigured) {
  console.error("Refusing migration: set MADA_COMMANDER_DATABASE_URL to a dedicated Mada Commander database first.");
  process.exit(2);
}
if (process.env.MADA_COMMANDER_CONFIRM_SCHEMA !== "YES") {
  console.error("Refusing to migrate without explicit confirmation. Set MADA_COMMANDER_CONFIRM_SCHEMA=YES after reviewing schema.sql.");
  process.exit(2);
}
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
try {
  const schema = await fs.readFile(path.join(root, "schema.sql"), "utf8");
  await query(schema);
  console.log("Mada Commander public schema applied successfully. No connection string or credential was printed.");
} catch (error) {
  console.error("Mada Commander schema migration failed:", String(error?.code || "MIGRATION_ERROR"));
  process.exitCode = 1;
} finally {
  await closePool();
}
