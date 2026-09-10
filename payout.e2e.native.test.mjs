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

const USER_1 = {
  username: `payout_u1_${TEST_ID}`,
  password: "TestPassword123"
};

const USER_2 = {
  username: `payout_u2_${TEST_ID}`,
  password: "TestPassword123"
};

let token1;
let token2;
let user1Id;
let user2Id;
let gameId;
let prizePoolBefore;
let walletBefore;

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
    `register failed: ${JSON.stringify(out.body)}`
  );

  assert.ok(out.body?.accessToken);

  return out.body.accessToken;
}

async function login(user) {
  const out = await api("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(user)
  });

  assert.equal(
    out.status,
    200,
    `login failed: ${JSON.stringify(out.body)}`
  );

  assert.ok(out.body?.accessToken);

  return out.body.accessToken;
}

before(async () => {
  token1 = await register(USER_1);
  token2 = await register(USER_2);

  const u1 = await prisma.user.findUnique({
    where: { username: USER_1.username }
  });

  const u2 = await prisma.user.findUnique({
    where: { username: USER_2.username }
  });

  assert.ok(u1);
  assert.ok(u2);

  user1Id = u1.id;
  user2Id = u2.id;

  // Credit isolated test wallets.
  // These users are created fresh for every test run.
  await prisma.wallet.update({
    where: { userId: user1Id },
    data: {
      balance: 10000n
    }
  });

  await prisma.wallet.update({
    where: { userId: user2Id },
    data: {
      balance: 10000n
    }
  });

  walletBefore = (
    await prisma.wallet.findUnique({
      where: { userId: user1Id }
    })
  )?.balance;

  assert.ok(walletBefore !== undefined);

  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `payout-create-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: 500
    })
  });

  assert.equal(
    create.status,
    201,
    `CREATE failed: ${JSON.stringify(create.body)}`
  );

  gameId = create.body.gameId;

  const join = await api(`/api/games/${gameId}/join`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token2}`,
      "Idempotency-Key": `payout-join-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: 500
    })
  });

  assert.equal(
    join.status,
    200,
    `JOIN failed: ${JSON.stringify(join.body)}`
  );

  const start = await api(`/api/games/${gameId}/start`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `payout-start-${randomUUID()}`
    },
    body: "{}"
  });

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  const game = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(game);
  assert.equal(game.status, "PLAYING");

  prizePoolBefore = game.prizePool;

  assert.ok(prizePoolBefore > 0n);

  /*
   * Put the authoritative state immediately before winning.
   *
   * token 0 = position 56
   * dice    = 1
   *
   * Therefore the next MOVE finishes all four tokens.
   */
  const authoritative =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(authoritative);

  const state = structuredClone(authoritative.state);

  assert.equal(state.phase, "PLAYING");
  assert.equal(state.currentPlayerIndex, 0);

  const player = state.players[0];

  assert.equal(player.id, user1Id);

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

test("winning MOVE finishes the game and pays the winner exactly once", async () => {
  const key = `payout-win-${randomUUID()}`;

  const beforeWallet =
    await prisma.wallet.findUnique({
      where: { userId: user1Id }
    });

  assert.ok(beforeWallet);

  const beforeTransactions =
    await prisma.transaction.count({
      where: {
        gameId,
        userId: user1Id,
        type: "DEPOSIT",
        reference: "GAME_PRIZE",
        status: "SUCCESS"
      }
    });

  const beforeLedger =
    await prisma.ledgerEntry.count({
      where: {
        gameId,
        userId: user1Id,
        type: "PRIZE_PAYOUT"
      }
    });

  const move = await import("./dist/game/authoritativeCommandService.js");

  const service =
    new move.AuthoritativeCommandService(prisma);

  const result = await service.move(
    gameId,
    user1Id,
    0,
    0,
    key
  );

  assert.equal(result.result.finished, true);
  assert.equal(
    result.result.stateVersion,
    1
  );

  const game =
    await prisma.game.findUnique({
      where: { id: gameId }
    });

  assert.ok(game);

  assert.equal(game.status, "FINISHED");
  assert.equal(game.winnerId, user1Id);
  assert.equal(
    game.winnerPayout,
    prizePoolBefore
  );

  const afterWallet =
    await prisma.wallet.findUnique({
      where: { userId: user1Id }
    });

  assert.ok(afterWallet);

  assert.equal(
    afterWallet.balance,
    beforeWallet.balance + prizePoolBefore
  );

  const transactions =
    await prisma.transaction.count({
      where: {
        gameId,
        userId: user1Id,
        type: "DEPOSIT",
        reference: "GAME_PRIZE",
        status: "SUCCESS"
      }
    });

  assert.equal(
    transactions,
    beforeTransactions + 1
  );

  const ledger =
    await prisma.ledgerEntry.count({
      where: {
        gameId,
        userId: user1Id,
        type: "PRIZE_PAYOUT"
      }
    });

  assert.equal(
    ledger,
    beforeLedger + 1
  );
});

test("second winning command cannot create another payout", async () => {
  const beforeWallet =
    await prisma.wallet.findUnique({
      where: { userId: user1Id }
    });

  assert.ok(beforeWallet);

  const beforeTransactions =
    await prisma.transaction.count({
      where: {
        gameId,
        userId: user1Id,
        type: "DEPOSIT",
        reference: "GAME_PRIZE",
        status: "SUCCESS"
      }
    });

  const beforeLedger =
    await prisma.ledgerEntry.count({
      where: {
        gameId,
        userId: user1Id,
        type: "PRIZE_PAYOUT"
      }
    });

  const serviceModule = await import("./dist/game/authoritativeCommandService.js");

  const service =
    new serviceModule.AuthoritativeCommandService(prisma);

  await assert.rejects(
    service.move(
      gameId,
      user1Id,
      0,
      1,
      `payout-double-${randomUUID()}`
    ),
    error => {
      assert.equal(
        error.message,
        "GAME_NOT_PLAYING"
      );
      return true;
    }
  );

  const afterWallet =
    await prisma.wallet.findUnique({
      where: { userId: user1Id }
    });

  assert.ok(afterWallet);

  assert.equal(
    afterWallet.balance,
    beforeWallet.balance
  );

  const transactions =
    await prisma.transaction.count({
      where: {
        gameId,
        userId: user1Id,
        type: "DEPOSIT",
        reference: "GAME_PRIZE",
        status: "SUCCESS"
      }
    });

  assert.equal(
    transactions,
    beforeTransactions
  );

  const ledger =
    await prisma.ledgerEntry.count({
      where: {
        gameId,
        userId: user1Id,
        type: "PRIZE_PAYOUT"
      }
    });

  assert.equal(
    ledger,
    beforeLedger
  );
});

test("GAME_FINISHED event exists", async () => {
  const event =
    await prisma.gameEvent.findFirst({
      where: {
        gameId,
        type: "GAME_FINISHED"
      }
    });

  assert.ok(
    event,
    "GAME_FINISHED event was not created"
  );
});

test("GAME_PRIZE transaction amount equals prizePool", async () => {
  const transaction =
    await prisma.transaction.findFirst({
      where: {
        gameId,
        userId: user1Id,
        type: "DEPOSIT",
        reference: "GAME_PRIZE",
        status: "SUCCESS"
      }
    });

  assert.ok(transaction);

  assert.equal(
    transaction.amount,
    prizePoolBefore
  );
});
