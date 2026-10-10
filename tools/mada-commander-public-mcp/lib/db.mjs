import { Pool } from "pg";

const connectionString = process.env.MADA_COMMANDER_DATABASE_URL || "";
export const databaseConfigured = Boolean(connectionString);
const pool = new Pool({
  ...(connectionString ? { connectionString } : {}),
  max: Number(process.env.MADA_COMMANDER_DB_POOL_MAX || 4),
  idleTimeoutMillis: 10_000,
  connectionTimeoutMillis: 5_000,
  allowExitOnIdle: true
});
pool.on("error", () => {
  // Never log connection strings, SQL parameters, or identity/token fields.
});

export async function query(sql, params = []) {
  if (!databaseConfigured) {
    const error = new Error("Mada Commander public database is not configured.");
    error.code = "MADA_DB_NOT_CONFIGURED";
    throw error;
  }
  return pool.query(sql, params);
}

export async function withTransaction(fn) {
  if (!databaseConfigured) {
    const error = new Error("Mada Commander public database is not configured.");
    error.code = "MADA_DB_NOT_CONFIGURED";
    throw error;
  }
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const value = await fn(client);
    await client.query("COMMIT");
    return value;
  } catch (error) {
    try { await client.query("ROLLBACK"); } catch {}
    throw error;
  } finally {
    client.release();
  }
}

export async function closePool() {
  await pool.end();
}
