import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import jwt from "jsonwebtoken";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const databaseUrl = process.env.DATABASE_URL ?? "";
if (
  process.env.CI !== "true" ||
  process.env.NODE_ENV !== "staging" ||
  process.env.NATIVE_TEST_MODE !== "true" ||
  process.env.LUDOMADA_LOAD_TEST_MODE !== "true" ||
  process.env.PORT !== "3101" ||
  !databaseUrl ||
  !process.env.JWT_SECRET ||
  process.env.JWT_SECRET.length < 32
) {
  throw new Error("CAPACITY_FIXTURE_SEED_REQUIRES_GUARDED_GITHUB_CI_MODE");
}

const database = new URL(databaseUrl);
if (
  !["127.0.0.1", "localhost"].includes(database.hostname) ||
  database.port !== "5432" ||
  database.pathname !== "/ludomada_ci"
) {
  throw new Error("CAPACITY_FIXTURE_SEED_REFUSES_NON_DISPOSABLE_DATABASE");
}

const requestedCount = Number(process.env.LUDOMADA_CAPACITY_GAME_COUNT ?? "1000");
if (!Number.isInteger(requestedCount) || requestedCount < 100 || requestedCount > 5000) {
  throw new Error("LUDOMADA_CAPACITY_GAME_COUNT_MUST_BE_100_TO_5000");
}

const prisma = new PrismaClient({
  adapter: new PrismaPg({ connectionString: databaseUrl })
});

try {
  await prisma.$connect();
  const users = await prisma.user.findMany({
    where: { username: { in: ["testplayer1", "testplayer2"] } },
    select: { id: true, username: true }
  });
  if (users.length !== 2) throw new Error("CAPACITY_TEST_REQUIRES_SEEDED_NATIVE_TEST_USERS");

  const playerOne = users.find((user) => user.username === "testplayer1");
  const playerTwo = users.find((user) => user.username === "testplayer2");
  if (!playerOne || !playerTwo) throw new Error("CAPACITY_TEST_USER_LOOKUP_FAILED");

  const { newGame } = await import("../dist/game/ludoEngine.js");
  const games = Array.from({ length: requestedCount }, () => ({
    id: "capacity-" + randomUUID(),
    creatorId: playerOne.id,
    status: "PLAYING" as const,
    grossAmount: 1000n,
    commission: 100n,
    prizePool: 900n,
    winnerPayout: 0n,
    adminRevenue: 100n,
    currentTurnUserId: playerOne.id,
    stateVersion: 0
  }));

  await prisma.game.createMany({ data: games });
  await prisma.gameParticipant.createMany({
    data: games.flatMap((game) => [
      { gameId: game.id, userId: playerOne.id, entryFee: 500n },
      { gameId: game.id, userId: playerTwo.id, entryFee: 500n }
    ])
  });

  const now = new Date();
  await prisma.authoritativeGameState.createMany({
    data: games.map((game) => {
      const state = newGame([playerOne.id, playerTwo.id]);
      state.stateVersion = 0;
      return {
        gameId: game.id,
        stateVersion: 0,
        state,
        createdAt: now,
        updatedAt: now
      };
    })
  });

  const accessToken = jwt.sign(
    { sub: playerOne.id, role: "PLAYER", jti: randomUUID() },
    process.env.JWT_SECRET,
    { expiresIn: "15m" }
  );
  const csv = [
    "gameId,accessToken",
    ...games.map((game) => game.id + "," + accessToken)
  ].join("\n") + "\n";
  writeFileSync(
    resolve("loadtests/artillery/capacity-fixtures.csv"),
    csv,
    { encoding: "utf8", mode: 0o600 }
  );
  console.log("CAPACITY_FIXTURES_READY", {
    games: games.length,
    participantRows: games.length * 2,
    accessTokenWrittenToEphemeralRunnerFile: true
  });
} finally {
  await prisma.$disconnect();
}
