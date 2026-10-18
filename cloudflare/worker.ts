/**
 * Cloudflare Workers adapter for the existing Express + Prisma backend.
 *
 * The existing application is PostgreSQL-specific. Use a reachable PostgreSQL
 * database (preferably via Cloudflare Hyperdrive) rather than rewriting the
 * wallet/game ledger for D1 during this recovery.
 */
import { env } from "cloudflare:workers";
import { httpServerHandler } from "cloudflare:node";

interface HyperdriveBinding {
  connectionString: string;
}

interface CloudflareBindings {
  DATABASE_URL?: string;
  HYPERDRIVE?: HyperdriveBinding;
  JWT_SECRET?: string;
  CORS_ORIGINS?: string;
  PG_POOL_MAX?: string;
}

const bindings = env as unknown as CloudflareBindings;
const databaseUrl =
  bindings.HYPERDRIVE?.connectionString ?? bindings.DATABASE_URL;

if (!databaseUrl) {
  throw new Error(
    "CLOUDFLARE_DATABASE_REQUIRED: configure a DATABASE_URL secret or HYPERDRIVE binding",
  );
}
if (!bindings.JWT_SECRET || bindings.JWT_SECRET.length < 32) {
  throw new Error("CLOUDFLARE_JWT_SECRET_REQUIRED: use a secret of at least 32 characters");
}
if (!bindings.CORS_ORIGINS?.trim()) {
  throw new Error("CLOUDFLARE_CORS_ORIGINS_REQUIRED: list the real client origins");
}

// Populate the same runtime contract used by the tested Node/Docker backend.
// The default pool size is kept low because each Worker isolate owns its pool.
process.env.DATABASE_URL = databaseUrl;
process.env.JWT_SECRET = bindings.JWT_SECRET;
process.env.CORS_ORIGINS = bindings.CORS_ORIGINS;
process.env.NODE_ENV = "production";
process.env.PORT = "3000";
process.env.TRUST_PROXY = "true";
process.env.CLOUDFLARE_WORKERS = "true";
process.env.PG_POOL_MAX = bindings.PG_POOL_MAX ?? "1";

// The backend starts its Express listener on port 3000. Cloudflare's official
// Node HTTP server adapter translates that listener into the Worker fetch API.
await import("../src/server");

export default httpServerHandler({ port: 3000 });
