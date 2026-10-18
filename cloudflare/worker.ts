/**
 * Cloudflare Workers adapter for the existing Express + Prisma backend.
 *
 * Create a fresh Prisma client for each invocation and schedule teardown with
 * waitUntil. Hyperdrive owns the long-lived PostgreSQL connection pool; never
 * retain a pg.Pool or Prisma client across Worker request contexts.
 */
import { env } from "cloudflare:workers";
import { handleAsNodeRequest } from "cloudflare:node";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "@prisma/client";

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

interface InvocationContext {
  waitUntil(promise: Promise<unknown>): void;
}

const topLevelBindings = env as unknown as CloudflareBindings;
const configuredDatabaseUrl =
  topLevelBindings.HYPERDRIVE?.connectionString ?? topLevelBindings.DATABASE_URL;

if (!configuredDatabaseUrl) {
  throw new Error(
    "CLOUDFLARE_DATABASE_REQUIRED: configure a DATABASE_URL secret or HYPERDRIVE binding",
  );
}
if (!topLevelBindings.JWT_SECRET || topLevelBindings.JWT_SECRET.length < 32) {
  throw new Error("CLOUDFLARE_JWT_SECRET_REQUIRED: use a secret of at least 32 characters");
}
if (!topLevelBindings.CORS_ORIGINS?.trim()) {
  throw new Error("CLOUDFLARE_CORS_ORIGINS_REQUIRED: list the real client origins");
}

// The Express backend validates these settings during module initialization.
process.env.DATABASE_URL = configuredDatabaseUrl;
process.env.JWT_SECRET = topLevelBindings.JWT_SECRET;
process.env.CORS_ORIGINS = topLevelBindings.CORS_ORIGINS;
process.env.NODE_ENV = "production";
process.env.PORT = "3000";
process.env.TRUST_PROXY = "true";
process.env.CLOUDFLARE_WORKERS = "true";
process.env.PG_POOL_MAX = "1";

const { withRequestPrisma } = await import("../src/server");

export default {
  async fetch(
    request: Request,
    requestBindings: CloudflareBindings,
    ctx: InvocationContext,
  ): Promise<Response> {
    const databaseUrl =
      requestBindings.HYPERDRIVE?.connectionString ??
      requestBindings.DATABASE_URL ??
      configuredDatabaseUrl;

    // Keep the Prisma/pg pool request-scoped in Workers. Always close it after
    // the response has been handled; waitUntil lets cleanup finish afterward.
    const requestPrisma = new PrismaClient({
      adapter: new PrismaPg({ connectionString: databaseUrl }),
    });

    try {
      return await withRequestPrisma(
        requestPrisma,
        () => handleAsNodeRequest(3000, request),
      );
    } finally {
      ctx.waitUntil(requestPrisma.$disconnect());
    }
  },
};
