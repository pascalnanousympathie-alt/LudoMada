import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const adapter = new PrismaPg({
  connectionString: process.env.DATABASE_URL
});

const prisma = new PrismaClient({ adapter });

const BASE_URL = `http://127.0.0.1:${process.env.PORT || 3000}`;
const TEST_ID = randomUUID().replace(/-/g, "").slice(0, 12);

const USERS = [
  {
    username: `payout4_p1_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `payout4_p2_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `payout4_p3_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `payout4_p4_${TEST_ID}`,
    password: "TestPassword123"
  }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 10000n;

let tokens = [];
let userIds = [];
let gameId;
let prizePoolBefore;

async function api(path, options = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });

  let body = null;

  try {
    body = await response.json();
  } catch {
    body = null;
  }

  return {
    status: response.status,
    body
  };
}

async function register(user) {
  const out = await api("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(user)
  });

  assert.equal(
    out.status,
    201,
    `REGISTER failed for ${user.username}: ${JSON.stringify(out.body)}`
  );

  assert.ok(out.body?.accessToken);

  return out.body.accessToken;
}

before(async () => {
  // Create four fresh users and obtain fresh access tokens.
  for (const user of USERS) {
    tokens.push(await register(user));
  }

  // Resolve database IDs.
  for (const user of USERS) {
    const dbUser = await prisma.user.findUnique({
      where: { username: user.username }
    });

    assert.ok(dbUser, `User not found: ${user.username}`);
    userIds.push(dbUser.id);
  }

  // Test-only wallet funding.
  // Production wallet/payment logic is not changed.
  for (const userId of userIds) {
    await prisma.wallet.update({
      where: { userId },
      data: { balance: TEST_BALANCE }
    });
  }

  // Player 1 creates the game.
  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `payout4-create-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: ENTRY_FEE
    })
  });

  assert.equal(
    create.status,
    201,
    `CREATE failed: ${JSON.stringify(create.body)}`
  );

  gameId = create.body.gameId;
  assert.ok(gameId);

  // Players 2, 3 and 4 join.
  for (let i = 1; i < 4; i++) {
    const join = await api(`/api/games/${gameId}/join`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[i]}`,
        "Idempotency-Key": `payout4-join-p${i + 1}-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    });

    assert.equal(
      join.status,
      200,
      `JOIN failed for player ${i + 1}: ${JSON.stringify(join.body)}`
    );
  }

  // Start four-player game.
  const start = await api(`/api/games/${gameId}/start`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `payout4-start-${randomUUID()}`
    },
    body: "{}"
  });

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  assert.equal(start.body?.status, "PLAYING");

  const game = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(game);
  assert.equal(game.status, "PLAYING");

  prizePoolBefore = game.prizePool;

  // 4 × 500 = 2,000 Ar gross.
  // 10% commission = 200 Ar.
  // Prize pool = 1,800 Ar.
  assert.equal(prizePoolBefore, 1800n);

  /*
   * Put player 1 immediately before winning.
   *
   * All four tokens are positioned at 57 except token 0 at 56.
   * With dice = 1, the next MOVE completes the player's tokens.
   */
  const authoritative = await prisma.authoritativeGameState.findUnique({
    where: { gameId }
  });

  assert.ok(authoritative);

  const state = structuredClone(authoritative.state);

  assert.equal(state.phase, "PLAYING");
  assert.equal(state.currentPlayerIndex, 0);

  const player = state.players[0];

  assert.equal(player.id, userIds[0]);

  for (const token of player.tokens) {
    token.pos = 57;
  }

  player.tokens[0].pos = 56;
  state.dice = 1;

  await prisma.authoritativeGameState.update({
    where: { gameId },
    data: {
      stateVersion: authoritative.stateVersion,
      state
    }
  });
});

after(async () => {
  await prisma.$disconnect();
});

test("4-player game has the correct prize pool", async () => {
  const game = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(game);
  assert.equal(game.status, "PLAYING");
  assert.equal(game.grossAmount, 2000n);
  assert.equal(game.commission, 200n);
  assert.equal(game.prizePool, 1800n);
});

test("winning MOVE pays the 1,800 Ar prize exactly once", async () => {
  const beforeWallet = await prisma.wallet.findUnique({
    where: { userId: userIds[0] }
  });

  assert.ok(beforeWallet);

  const beforeTransactions = await prisma.transaction.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "DEPOSIT",
      reference: "GAME_PRIZE",
      status: "SUCCESS"
    }
  });

  const beforeLedger = await prisma.ledgerEntry.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "PRIZE_PAYOUT"
    }
  });

  const serviceModule = await import(
    "./dist/game/authoritativeCommandService.js"
  );

  const service = new serviceModule.AuthoritativeCommandService(prisma);

  const result = await service.move(
    gameId,
    userIds[0],
    0,
    0,
    `payout4-win-${randomUUID()}`
  );

  assert.equal(result.result.finished, true);

  const game = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(game);

  assert.equal(game.status, "FINISHED");
  assert.equal(game.winnerId, userIds[0]);
  assert.equal(game.winnerPayout, prizePoolBefore);

  const afterWallet = await prisma.wallet.findUnique({
    where: { userId: userIds[0] }
  });

  assert.ok(afterWallet);

  assert.equal(
    afterWallet.balance,
    beforeWallet.balance + prizePoolBefore
  );

  const transactions = await prisma.transaction.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "DEPOSIT",
      reference: "GAME_PRIZE",
      status: "SUCCESS"
    }
  });

  assert.equal(
    transactions,
    beforeTransactions + 1
  );

  const ledger = await prisma.ledgerEntry.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "PRIZE_PAYOUT"
    }
  });

  assert.equal(
    ledger,
    beforeLedger + 1
  );
});

test("second winning command cannot create another payout", async () => {
  const beforeWallet = await prisma.wallet.findUnique({
    where: { userId: userIds[0] }
  });

  assert.ok(beforeWallet);

  const beforeTransactions = await prisma.transaction.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "DEPOSIT",
      reference: "GAME_PRIZE",
      status: "SUCCESS"
    }
  });

  const beforeLedger = await prisma.ledgerEntry.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "PRIZE_PAYOUT"
    }
  });

  const serviceModule = await import(
    "./dist/game/authoritativeCommandService.js"
  );

  const service = new serviceModule.AuthoritativeCommandService(prisma);

  await assert.rejects(
    service.move(
      gameId,
      userIds[0],
      0,
      1,
      `payout4-double-${randomUUID()}`
    ),
    error => {
      assert.equal(error.message, "GAME_NOT_PLAYING");
      return true;
    }
  );

  const afterWallet = await prisma.wallet.findUnique({
    where: { userId: userIds[0] }
  });

  assert.ok(afterWallet);

  assert.equal(
    afterWallet.balance,
    beforeWallet.balance
  );

  const transactions = await prisma.transaction.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "DEPOSIT",
      reference: "GAME_PRIZE",
      status: "SUCCESS"
    }
  });

  assert.equal(
    transactions,
    beforeTransactions
  );

  const ledger = await prisma.ledgerEntry.count({
    where: {
      gameId,
      userId: userIds[0],
      type: "PRIZE_PAYOUT"
    }
  });

  assert.equal(
    ledger,
    beforeLedger
  );
});

test("GAME_FINISHED event exists exactly once", async () => {
  const events = await prisma.gameEvent.findMany({
    where: {
      gameId,
      type: "GAME_FINISHED"
    }
  });

  assert.equal(events.length, 1);
});

test("GAME_PRIZE transaction equals the complete 1,800 Ar prize pool", async () => {
  const transaction = await prisma.transaction.findFirst({
    where: {
      gameId,
      userId: userIds[0],
      type: "DEPOSIT",
      reference: "GAME_PRIZE",
      status: "SUCCESS"
    }
  });

  assert.ok(transaction);
  assert.equal(transaction.amount, prizePoolBefore);
});
