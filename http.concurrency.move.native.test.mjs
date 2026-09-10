import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import { randomUUID } from "node:crypto";

const prisma = new PrismaClient({
  adapter: new PrismaPg({
    connectionString: process.env.DATABASE_URL
  })
});

const BASE = "http://localhost:3000";
const USER = "testplayer1";
const PASSWORD = "TestPassword123";

let gameId;
let token;

async function login() {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      username: USER,
      password: PASSWORD
    })
  });

  assert.equal(res.status, 200);

  const body = await res.json();
  assert.ok(body.accessToken);

  return body.accessToken;
}

before(async () => {
  token = await login();
});

after(async () => {
  if (gameId) {
    await prisma.gameEvent.deleteMany({
      where: { gameId }
    });

    await prisma.gameCommand.deleteMany({
      where: { gameId }
    });

    await prisma.authoritativeGameState.deleteMany({
      where: { gameId }
    });

    await prisma.gameParticipant.deleteMany({
      where: { gameId }
    });

    await prisma.game.delete({
      where: { id: gameId }
    });
  }

  await prisma.$disconnect();
});

test("HTTP concurrent MOVE: only one request can mutate the same version", async () => {
  const { newGame } =
    await import("./dist/game/ludoEngine.js");

  gameId = `http-concurrent-${randomUUID()}`;

  await prisma.game.create({
    data: {
      id: gameId,
      creatorId: "cmtrfzi6n001as6fnijnw4eky",
      status: "PLAYING",
      grossAmount: 1000n,
      commission: 100n,
      prizePool: 900n,
      winnerPayout: 0n,
      adminRevenue: 100n,
      currentTurnUserId: "cmtrfzi6n001as6fnijnw4eky",
      stateVersion: 0
    }
  });

  await prisma.gameParticipant.create({
    data: {
      gameId,
      userId: "cmtrfzi6n001as6fnijnw4eky",
      entryFee: 500n
    }
  });

  const state = newGame([
    "cmtrfzi6n001as6fnijnw4eky",
    "cmtrh9or7001ws6fnvdmxw5kb"
  ]);

  state.players[0].tokens[0].pos = 0;
  state.dice = 1;
  state.stateVersion = 0;

  await prisma.authoritativeGameState.create({
    data: {
      gameId,
      stateVersion: 0,
      state
    }
  });

  const key1 = `http-a-${randomUUID()}`;
  const key2 = `http-b-${randomUUID()}`;

  const request = (key) =>
    fetch(`${BASE}/api/games/${gameId}/move`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`,
        "Idempotency-Key": key
      },
      body: JSON.stringify({
        tokenIndex: 0,
        expectedVersion: 0
      })
    });

  const responses = await Promise.all([
    request(key1),
    request(key2)
  ]);

  const bodies = await Promise.all(
    responses.map(r => r.json())
  );

  const statuses = responses.map(r => r.status).sort();

  assert.deepEqual(statuses, [200, 409]);

  const errors = bodies.filter(
    b => b.error === "STATE_VERSION_CONFLICT"
  );

  assert.equal(errors.length, 1);

  const dbState =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(dbState);
  assert.equal(dbState.stateVersion, 1);
  assert.equal(
    dbState.state.players[0].tokens[0].pos,
    1
  );

  const commands =
    await prisma.gameCommand.findMany({
      where: { gameId }
    });

  assert.equal(commands.length, 1);

  console.log("HTTP CONCURRENCY:", statuses);
  console.log(
    "FINAL STATE VERSION:",
    dbState.stateVersion
  );
  console.log(
    "FINAL TOKEN POSITION:",
    dbState.state.players[0].tokens[0].pos
  );
  console.log(
    "GAME COMMANDS:",
    commands.length
  );
});
