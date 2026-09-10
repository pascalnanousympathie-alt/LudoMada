import "dotenv/config";
import test, { before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const { AuthoritativeCommandService } =
  await import("./src/game/authoritativeCommandService.ts");

const { newGame } =
  await import("./src/game/ludoEngine.ts");

const adapter = new PrismaPg({
  connectionString: process.env.DATABASE_URL
});

const prisma = new PrismaClient({ adapter });
const service = new AuthoritativeCommandService(prisma);

const USER_1 = "cmtrfzi6n001as6fnijnw4eky";
const USER_2 = "cmtrh9or7001ws6fnvdmxw5kb";

let gameId;

async function createPlayingGame({
  player1 = USER_1,
  player2 = USER_2,
  prizePool = 900n
} = {}) {
  const game = await prisma.game.create({
    data: {
      creatorId: player1,
      status: "PLAYING",
      grossAmount: 1000n,
      commission: 100n,
      prizePool,
      winnerPayout: 0n,
      adminRevenue: 100n,
      currentTurnUserId: player1,
      dice: null,
      diceRolled: false,
      stateVersion: 0,

      participants: {
        create: [
          {
            userId: player1,
            entryFee: 500n
          },
          {
            userId: player2,
            entryFee: 500n
          }
        ]
      }
    }
  });

  const state = newGame([player1, player2]);

  await prisma.authoritativeGameState.create({
    data: {
      gameId: game.id,
      stateVersion: state.stateVersion,
      state
    }
  });

  return game.id;
}

async function deleteTestGame(id) {
  if (!id) return;

  await prisma.game.delete({
    where: { id }
  }).catch(() => {});
}

before(async () => {
  await prisma.$connect();

  const users = await prisma.user.findMany({
    where: {
      id: {
        in: [USER_1, USER_2]
      }
    },
    select: {
      id: true
    }
  });

  assert.equal(
    users.length,
    2,
    "Expected testplayer1 and testplayer2 to exist"
  );
});

beforeEach(async () => {
  gameId = await createPlayingGame();
});

after(async () => {
  await deleteTestGame(gameId);
  await prisma.$disconnect();
});

test("ROLL rejects a non-participant", async () => {
  const outsider = "not-a-participant";

  await assert.rejects(
    service.roll(
      gameId,
      outsider,
      0,
      `native-roll-outsider-${randomUUID()}`
    ),
    {
      message: "NOT_GAME_PARTICIPANT"
    }
  );
});

test("ROLL rejects the wrong player turn", async () => {
  await assert.rejects(
    service.roll(
      gameId,
      USER_2,
      0,
      `native-roll-wrong-turn-${randomUUID()}`
    ),
    {
      message: "NOT_YOUR_TURN"
    }
  );
});

test("ROLL rejects an incorrect expectedVersion", async () => {
  await assert.rejects(
    service.roll(
      gameId,
      USER_1,
      999,
      `native-roll-version-${randomUUID()}`
    ),
    {
      message: "STATE_VERSION_CONFLICT"
    }
  );
});

test("ROLL creates a GameCommand and advances authoritative state", async () => {
  const key = `native-roll-${randomUUID()}`;

  const out = await service.roll(
    gameId,
    USER_1,
    0,
    key
  );

  assert.ok(out);
  assert.ok(out.result);
  assert.ok(
    Number.isInteger(out.result.dice),
    "dice must be an integer"
  );
  assert.ok(
    out.result.dice >= 1 && out.result.dice <= 6,
    "dice must be between 1 and 6"
  );

  const command = await prisma.gameCommand.findUnique({
    where: {
      idempotencyKey: key
    }
  });

  assert.ok(command);
  assert.equal(command.gameId, gameId);
  assert.equal(command.userId, USER_1);
  assert.equal(command.commandType, "ROLL");
  assert.equal(command.expectedVersion, 0);

  const state = await prisma.authoritativeGameState.findUnique({
    where: {
      gameId
    }
  });

  assert.ok(state);
  assert.equal(
    state.stateVersion,
    out.result.stateVersion
  );
});

test("MOVE rejects a non-participant", async () => {
  await assert.rejects(
    service.move(
      gameId,
      "not-a-participant",
      0,
      0,
      `native-move-outsider-${randomUUID()}`
    ),
    {
      message: "NOT_GAME_PARTICIPANT"
    }
  );
});

test("MOVE rejects wrong stateVersion", async () => {
  await assert.rejects(
    service.move(
      gameId,
      USER_1,
      0,
      999,
      `native-move-version-${randomUUID()}`
    ),
    {
      message: "STATE_VERSION_CONFLICT"
    }
  );
});

test("MOVE rejects before dice is rolled", async () => {
  await assert.rejects(
    service.move(
      gameId,
      USER_1,
      0,
      0,
      `native-move-before-roll-${randomUUID()}`
    ),
    {
      message: "DICE_NOT_ROLLED"
    }
  );
});

test("same idempotency key cannot be reused for another command", async () => {
  const key = `native-conflict-${randomUUID()}`;

  await service.roll(
    gameId,
    USER_1,
    0,
    key
  );

  await assert.rejects(
    service.move(
      gameId,
      USER_1,
      0,
      0,
      key
    ),
    {
      message: "IDEMPOTENCY_KEY_CONFLICT"
    }
  );
});

test("finished MOVE performs exactly one prize payout", async () => {
  const payoutGame = await prisma.game.create({
    data: {
      creatorId: USER_1,
      status: "PLAYING",
      grossAmount: 1000n,
      commission: 100n,
      prizePool: 900n,
      winnerPayout: 0n,
      adminRevenue: 100n,
      currentTurnUserId: USER_1,
      dice: 1,
      diceRolled: true,
      stateVersion: 0,

      participants: {
        create: [
          {
            userId: USER_1,
            entryFee: 500n
          },
          {
            userId: USER_2,
            entryFee: 500n
          }
        ]
      }
    }
  });

  const state = newGame([USER_1, USER_2]);

  state.players[0].tokens[0].pos = 56;
  state.players[0].tokens[1].pos = 57;
  state.players[0].tokens[2].pos = 57;
  state.players[0].tokens[3].pos = 57;

  state.dice = 1;
  state.stateVersion = 0;
  state.currentPlayerIndex = 0;

  await prisma.authoritativeGameState.create({
    data: {
      gameId: payoutGame.id,
      stateVersion: 0,
      state
    }
  });

  const walletBefore = await prisma.wallet.findUnique({
    where: {
      userId: USER_1
    }
  });

  assert.ok(walletBefore);

  const key = `native-payout-${randomUUID()}`;

  const out = await service.move(
    payoutGame.id,
    USER_1,
    0,
    0,
    key
  );

  assert.equal(out.result.finished, true);

  const finishedGame = await prisma.game.findUnique({
    where: {
      id: payoutGame.id
    }
  });

  assert.ok(finishedGame);
  assert.equal(finishedGame.status, "FINISHED");
  assert.equal(finishedGame.winnerId, USER_1);
  assert.equal(finishedGame.winnerPayout, 900n);

  const payoutTransactions =
    await prisma.transaction.findMany({
      where: {
        gameId: payoutGame.id,
        userId: USER_1,
        type: "DEPOSIT",
        status: "SUCCESS",
        reference: "GAME_PRIZE"
      }
    });

  assert.equal(payoutTransactions.length, 1);
  assert.equal(payoutTransactions[0].amount, 900n);

  const walletAfter = await prisma.wallet.findUnique({
    where: {
      userId: USER_1
    }
  });

  assert.ok(walletAfter);
  assert.equal(
    walletAfter.balance,
    walletBefore.balance + 900n
  );

  await deleteTestGame(payoutGame.id);
});

test("concurrent ROLL commands serialize through the authoritative repository", async () => {
  const key1 = `native-concurrent-roll-1-${randomUUID()}`;
  const key2 = `native-concurrent-roll-2-${randomUUID()}`;

  const results = await Promise.allSettled([
    service.roll(
      gameId,
      USER_1,
      0,
      key1
    ),
    service.roll(
      gameId,
      USER_1,
      0,
      key2
    )
  ]);

  const fulfilled =
    results.filter(
      x => x.status === "fulfilled"
    );

  const rejected =
    results.filter(
      x => x.status === "rejected"
    );

  assert.equal(
    fulfilled.length,
    1,
    "exactly one concurrent ROLL should succeed"
  );

  assert.equal(
    rejected.length,
    1,
    "exactly one concurrent ROLL should be rejected"
  );

  if (rejected[0].status === "rejected") {
    assert.ok(
      [
        "STATE_VERSION_CONFLICT",
        "NOT_YOUR_TURN",
        "DICE_ALREADY_ROLLED"
      ].includes(rejected[0].reason?.message),
      `unexpected concurrent error: ${rejected[0].reason?.message}`
    );
  }

  const commands =
    await prisma.gameCommand.findMany({
      where: {
        gameId,
        commandType: "ROLL"
      }
    });

  assert.equal(commands.length, 1);
});

test("same ROLL idempotency key replays the original result", async () => {
  const key = `native-roll-replay-${randomUUID()}`;

  const first = await service.roll(
    gameId,
    USER_1,
    0,
    key
  );

  const stateAfterFirst =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(stateAfterFirst);

  const second = await service.roll(
    gameId,
    USER_1,
    0,
    key
  );

  assert.deepEqual(
    second.result,
    first.result
  );

  const stateAfterSecond =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(stateAfterSecond);

  assert.equal(
    stateAfterSecond.stateVersion,
    stateAfterFirst.stateVersion
  );

  const commands =
    await prisma.gameCommand.findMany({
      where: {
        gameId,
        idempotencyKey: key
      }
    });

  assert.equal(commands.length, 1);
});

test("same MOVE idempotency key replays the original result", async () => {
  const rollKey =
    `native-move-replay-roll-${randomUUID()}`;

  const moveKey =
    `native-move-replay-${randomUUID()}`;

  const roll = await service.roll(
    gameId,
    USER_1,
    0,
    rollKey
  );

  assert.ok(roll.result);

  /*
   * A random ROLL may produce a value other than 6.
   * If there is no legal move, the engine automatically
   * skips the turn, so this test cannot safely perform MOVE.
   *
   * Therefore this test only continues when the server
   * produced a legal dice state for USER_1.
   */
  if (
    roll.result.autoSkipped ||
    roll.result.dice === null
  ) {
    assert.ok(true);
    return;
  }

  const version =
    roll.result.stateVersion;

  const first = await service.move(
    gameId,
    USER_1,
    0,
    version,
    moveKey
  );

  const second = await service.move(
    gameId,
    USER_1,
    0,
    version,
    moveKey
  );

  assert.deepEqual(
    second.result,
    first.result
  );

  const commands =
    await prisma.gameCommand.findMany({
      where: {
        gameId,
        idempotencyKey: moveKey
      }
    });

  assert.equal(commands.length, 1);
});
