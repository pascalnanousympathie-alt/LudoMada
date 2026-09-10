import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const prisma = new PrismaClient({
  adapter: new PrismaPg({
    connectionString: process.env.DATABASE_URL
  })
});

const USER_1 = "cmtrfzi6n001as6fnijnw4eky";
const USER_2 = "cmtrh9or7001ws6fnvdmxw5kb";

let service;
let gameId;

async function makeGame(id) {
  const { newGame } = await import("./src/game/ludoEngine.ts");

  await prisma.game.create({
    data: {
      id,
      creatorId: USER_1,
      status: "PLAYING",
      grossAmount: 1000n,
      commission: 100n,
      prizePool: 900n,
      winnerPayout: 0n,
      adminRevenue: 100n,
      currentTurnUserId: USER_1,
      stateVersion: 0
    }
  });

  await prisma.gameParticipant.createMany({
    data: [
      { gameId: id, userId: USER_1, entryFee: 500n },
      { gameId: id, userId: USER_2, entryFee: 500n }
    ]
  });

  const state = newGame([USER_1, USER_2]);

  state.players[0].tokens[0].pos = 0;
  state.dice = 1;
  state.stateVersion = 0;

  await prisma.authoritativeGameState.create({
    data: {
      gameId: id,
      stateVersion: 0,
      state
    }
  });
}

async function cleanup(id) {
  await prisma.gameEvent.deleteMany({ where: { gameId: id } });
  await prisma.gameCommand.deleteMany({ where: { gameId: id } });
  await prisma.authoritativeGameState.deleteMany({ where: { gameId: id } });
  await prisma.gameParticipant.deleteMany({ where: { gameId: id } });
  await prisma.game.delete({ where: { id } });
}

before(async () => {
  const { AuthoritativeCommandService } =
    await import("./src/game/authoritativeCommandService.ts");

  service = new AuthoritativeCommandService(prisma);

  gameId = `native-concurrent-${randomUUID()}`;

  await makeGame(gameId);
});

after(async () => {
  if (gameId) {
    await cleanup(gameId);
  }

  await prisma.$disconnect();
});

test("two concurrent MOVE requests cannot both mutate the same version", async () => {
  const key1 = `concurrent-a-${randomUUID()}`;
  const key2 = `concurrent-b-${randomUUID()}`;

  const results = await Promise.allSettled([
    service.move(gameId, USER_1, 0, 0, key1),
    service.move(gameId, USER_1, 0, 0, key2)
  ]);

  const success = results.filter(
    r => r.status === "fulfilled"
  );

  const failed = results.filter(
    r => r.status === "rejected"
  );

  assert.equal(success.length, 1);
  assert.equal(failed.length, 1);

  assert.equal(
    failed[0].reason.message,
    "STATE_VERSION_CONFLICT"
  );

  const state =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(state);

  assert.equal(state.stateVersion, 1);
  assert.equal(
    state.state.players[0].tokens[0].pos,
    1
  );

  const commands =
    await prisma.gameCommand.findMany({
      where: { gameId }
    });

  assert.equal(commands.length, 1);
});

test("same idempotency key returns the same result concurrently", async () => {
  const id = `native-idem-${randomUUID()}`;

  await makeGame(id);

  const key = `same-key-${randomUUID()}`;

  const results = await Promise.allSettled([
    service.move(id, USER_1, 0, 0, key),
    service.move(id, USER_1, 0, 0, key)
  ]);

  const success = results.filter(
    r => r.status === "fulfilled"
  );

  const failed = results.filter(
    r => r.status === "rejected"
  );

  assert.equal(
    success.length,
    2,
    `Expected 2 successful/replayed results, got ${success.length}`
  );

  assert.equal(failed.length, 0);

  assert.deepEqual(
    success[0].value.result,
    success[1].value.result
  );

  const commands =
    await prisma.gameCommand.findMany({
      where: {
        gameId: id,
        idempotencyKey: key
      }
    });

  assert.equal(commands.length, 1);

  const state =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId: id }
    });

  assert.ok(state);

  assert.equal(state.stateVersion, 1);
  assert.equal(
    state.state.players[0].tokens[0].pos,
    1
  );

  await cleanup(id);
});
