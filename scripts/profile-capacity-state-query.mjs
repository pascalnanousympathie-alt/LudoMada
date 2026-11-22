import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import pg from "pg";

const databaseUrl = process.env.DATABASE_URL ?? "";
const fixturePath = resolve(process.argv[2] ?? "loadtests/artillery/capacity-fixtures.csv");
const outputPath = resolve(process.argv[3] ?? process.env.RUNNER_TEMP ?? ".", "ludomada-query-plan.json");

if (
  process.env.CI !== "true" ||
  process.env.NODE_ENV !== "staging" ||
  process.env.LUDOMADA_LOAD_TEST_MODE !== "true" ||
  process.env.PORT !== "3101" ||
  !databaseUrl
) {
  throw new Error("CAPACITY_QUERY_PROFILE_REQUIRES_GUARDED_GITHUB_CI_MODE");
}

const database = new URL(databaseUrl);
if (
  !["127.0.0.1", "localhost"].includes(database.hostname) ||
  database.port !== "5432" ||
  database.pathname !== "/ludomada_ci"
) {
  throw new Error("CAPACITY_QUERY_PROFILE_REFUSES_NON_DISPOSABLE_DATABASE");
}

const firstFixture = readFileSync(fixturePath, "utf8").split(/\r?\n/).find((line, index) => index > 0 && line);
const gameId = firstFixture?.split(",", 1)[0];
if (!gameId || !gameId.startsWith("capacity-")) {
  throw new Error("CAPACITY_QUERY_PROFILE_FIXTURE_NOT_FOUND");
}

function summarizePlan(node) {
  return {
    nodeType: node["Node Type"],
    relation: node["Relation Name"],
    index: node["Index Name"],
    actualRows: node["Actual Rows"],
    actualLoops: node["Actual Loops"],
    actualStartupMs: node["Actual Startup Time"],
    actualTotalMs: node["Actual Total Time"],
    sharedHitBlocks: node["Shared Hit Blocks"],
    sharedReadBlocks: node["Shared Read Blocks"],
    tempReadBlocks: node["Temp Read Blocks"],
    tempWrittenBlocks: node["Temp Written Blocks"],
    children: (node.Plans ?? []).map(summarizePlan)
  };
}

const pool = new pg.Pool({ connectionString: databaseUrl, max: 1, connectionTimeoutMillis: 5000 });
try {
  const participant = await pool.query(
    `SELECT "userId" FROM "GameParticipant" WHERE "gameId" = $1 ORDER BY "createdAt" ASC LIMIT 1`,
    [gameId]
  );
  const userId = participant.rows[0]?.userId;
  if (!userId) throw new Error("CAPACITY_QUERY_PROFILE_PARTICIPANT_NOT_FOUND");

  const sql = `
    SELECT
      g.id AS "gameId",
      g.status::text AS "status",
      g."creatorId" AS "creatorId",
      g."winnerId" AS "winnerId",
      (
        SELECT gp."entryFee"::text
        FROM "GameParticipant" gp
        WHERE gp."gameId" = g.id
        ORDER BY gp."createdAt" ASC
        LIMIT 1
      ) AS "entryFee",
      COALESCE(
        (
          SELECT json_agg(json_build_object('userId', gp."userId") ORDER BY gp."createdAt" ASC)
          FROM "GameParticipant" gp
          WHERE gp."gameId" = g.id
        ),
        '[]'::json
      ) AS "participants",
      ags.state AS "state"
    FROM "Game" g
    LEFT JOIN "AuthoritativeGameState" ags ON ags."gameId" = g.id
    WHERE g.id = $1
      AND EXISTS (
        SELECT 1 FROM "GameParticipant" gp_auth
        WHERE gp_auth."gameId" = g.id AND gp_auth."userId" = $2
      )
    LIMIT 1
  `;
  const explain = await pool.query(`EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) ${sql}`, [gameId, userId]);
  const planDocument = explain.rows[0]["QUERY PLAN"][0];
  const activity = await pool.query(
    `SELECT state, count(*)::integer AS connections
     FROM pg_stat_activity
     WHERE datname = current_database() AND pid <> pg_backend_pid()
     GROUP BY state ORDER BY state`
  );
  const maxConnections = await pool.query("SHOW max_connections");

  const report = {
    profile: "authenticated-game-state-read",
    database: "disposable-local-ci-only",
    planningMs: planDocument["Planning Time"],
    executionMs: planDocument["Execution Time"],
    plan: summarizePlan(planDocument.Plan),
    databaseSnapshot: {
      maxConnections: Number(maxConnections.rows[0].max_connections),
      otherConnectionsByState: activity.rows
    }
  };
  writeFileSync(outputPath, JSON.stringify(report, null, 2) + "\n", { mode: 0o600 });
  console.log("CAPACITY_QUERY_PROFILE", JSON.stringify(report));
} finally {
  await pool.end();
}
