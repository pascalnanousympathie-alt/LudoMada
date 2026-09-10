import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const BASE_URL = `http://127.0.0.1:${process.env.PORT || 3000}`;

const adapter = new PrismaPg({
  connectionString: process.env.DATABASE_URL
});

const prisma = new PrismaClient({ adapter });

const TEST_ID = randomUUID()
  .replace(/-/g, "")
  .slice(0, 12);

const USERS = [
  { username: `cfinish_p1_${TEST_ID}`, password: "TestPassword123" },
  { username: `cfinish_p2_${TEST_ID}`, password: "TestPassword123" },
  { username: `cfinish_p3_${TEST_ID}`, password: "TestPassword123" },
  { username: `cfinish_p4_${TEST_ID}`, password: "TestPassword123" }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 100000n;

let tokens = [];
let userIds = [];
let gameId;
let expectedVersion = 1;
let prizePool = 0n;
let winnerBalanceBefore = 0n;

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
  for (const user of USERS) {
    tokens.push(await register(user));
  }

  for (const user of USERS) {
    const dbUser = await prisma.user.findUnique({
      where: {
        username: user.username
      }
    });

    assert.ok(
      dbUser,
      `User not found: ${user.username}`
    );

    userIds.push(dbUser.id);
  }

  for (const userId of userIds) {
    await prisma.wallet.update({
      where: {
        userId
      },
      data: {
        balance: TEST_BALANCE
      }
    });
  }

  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `cfinish-create-${randomUUID()}`
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

  for (let i = 1; i < 4; i++) {
    const join = await api(
      `/api/games/${gameId}/join`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${tokens[i]}`,
          "Idempotency-Key":
            `cfinish-join-p${i + 1}-${randomUUID()}`
        },
        body: JSON.stringify({
          entryFeeMGA: ENTRY_FEE
        })
      }
    );

    assert.equal(
      join.status,
      200,
      `JOIN P${i + 1} failed: ${JSON.stringify(join.body)}`
    );
  }

  const start = await api(
    `/api/games/${gameId}/start`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[0]}`,
        "Idempotency-Key":
          `cfinish-start-${randomUUID()}`
      },
      body: JSON.stringify({})
    }
  );

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  const game = await prisma.game.findUnique({
    where: {
      id: gameId
    }
  });

  assert.ok(game);

  prizePool = game.prizePool;

  const winnerWallet =
    await prisma.wallet.findUnique({
      where: {
        userId: userIds[0]
      }
    });

  assert.ok(winnerWallet);

  winnerBalanceBefore =
    winnerWallet.balance;

  const authoritative =
    await prisma.authoritativeGameState.findUnique({
      where: {
        gameId
      }
    });

  assert.ok(
    authoritative,
    "Authoritative state not found"
  );

  const state = authoritative.state;

  state.phase = "PLAYING";
  state.currentPlayerIndex = 0;
  state.dice = 6;
  state.stateVersion = expectedVersion;
  state.winnerId = null;

  state.players[0].tokens = [
    { pos: 51 },
    { pos: 57 },
    { pos: 57 },
    { pos: 57 }
  ];

  for (
    let playerIndex = 1;
    playerIndex < 4;
    playerIndex++
  ) {
    state.players[playerIndex].tokens = [
      { pos: -1 },
      { pos: -1 },
      { pos: -1 },
      { pos: -1 }
    ];
  }

  await prisma.authoritativeGameState.update({
    where: {
      gameId
    },
    data: {
      stateVersion: expectedVersion,
      state
    }
  });

  await prisma.game.update({
    where: {
      id: gameId
    },
    data: {
      status: "PLAYING",
      winnerId: null,
      winnerPayout: 0n,
      currentTurnUserId: userIds[0],
      dice: 6,
      diceRolled: true,
      stateVersion: expectedVersion,
      finishedAt: null
    }
  });
});

after(async () => {
  await prisma.$disconnect();
});

test(
  "CONCURRENT FINISH: exactly one payout is committed",
  async () => {
    const request = (idempotencyKey) =>
      api(
        `/api/games/${gameId}/move`,
        {
          method: "POST",
          headers: {
            Authorization:
              `Bearer ${tokens[0]}`,
            "Idempotency-Key":
              idempotencyKey
          },
          body: JSON.stringify({
            tokenIndex: 0,
            expectedVersion
          })
        }
      );

    const [a, b] = await Promise.all([
      request(
        `cfinish-a-${randomUUID()}`
      ),
      request(
        `cfinish-b-${randomUUID()}`
      )
    ]);

    console.log(
      "CONCURRENT FINISH:",
      [a, b].map(r => ({
        status: r.status,
        error: r.body?.error,
        stateVersion:
          r.body?.stateVersion
      }))
    );

    const successes = [a, b].filter(
      r => r.status === 200
    );

    assert.equal(
      successes.length,
      1,
      `Expected exactly one successful final move: ${JSON.stringify([a, b])}`
    );

    const failures = [a, b].filter(
      r => r.status !== 200
    );

    assert.equal(
      failures.length,
      1,
      `Expected exactly one failed request: ${JSON.stringify([a, b])}`
    );

    assert.ok(
      failures[0].status === 409 ||
      failures[0].status === 400,
      `Unexpected losing status: ${JSON.stringify(failures[0])}`
    );

    assert.ok(
      failures[0].body?.error === "STATE_VERSION_CONFLICT" ||
      failures[0].body?.error === "GAME_NOT_PLAYING" ||
      failures[0].body?.error === "GAME_FINISHED",
      `Unexpected losing error: ${JSON.stringify(failures[0])}`
    );

    const game =
      await prisma.game.findUnique({
        where: {
          id: gameId
        }
      });

    assert.ok(
      game,
      "Game not found after final move"
    );

    assert.equal(
      game.status,
      "FINISHED",
      `Expected FINISHED game, got ${game.status}`
    );

    assert.equal(
      game.winnerId,
      userIds[0],
      `Unexpected winner: ${game.winnerId}`
    );

    assert.equal(
      game.winnerPayout,
      prizePool,
      `Expected winner payout ${prizePool}, got ${game.winnerPayout}`
    );

    const winnerWalletAfter =
      await prisma.wallet.findUnique({
        where: {
          userId: userIds[0]
        }
      });

    assert.ok(
      winnerWalletAfter,
      "Winner wallet not found"
    );

    assert.equal(
      winnerWalletAfter.balance,
      winnerBalanceBefore + prizePool,
      "Winner received incorrect payout"
    );

    const payoutTransactions =
      await prisma.transaction.findMany({
        where: {
          gameId,
          userId: userIds[0],
          type: "DEPOSIT",
          status: "SUCCESS",
          reference: "GAME_PRIZE"
        }
      });

    assert.equal(
      payoutTransactions.length,
      1,
      `Expected exactly one prize transaction, got ${payoutTransactions.length}`
    );

    assert.equal(
      payoutTransactions[0].amount,
      prizePool,
      "Prize transaction amount mismatch"
    );

    const payoutLedgerEntries =
      await prisma.ledgerEntry.findMany({
        where: {
          gameId,
          userId: userIds[0],
          type: "PRIZE_PAYOUT"
        }
      });

    assert.equal(
      payoutLedgerEntries.length,
      1,
      `Expected exactly one PRIZE_PAYOUT ledger entry, got ${payoutLedgerEntries.length}`
    );

    assert.equal(
      payoutLedgerEntries[0].amount,
      prizePool,
      "Prize ledger amount mismatch"
    );

    const finishedEvents =
      await prisma.gameEvent.findMany({
        where: {
          gameId,
          type: "GAME_FINISHED"
        }
      });

    assert.equal(
      finishedEvents.length,
      1,
      `Expected exactly one GAME_FINISHED event, got ${finishedEvents.length}`
    );

    const winningMoves =
      await prisma.gameCommand.findMany({
        where: {
          gameId,
          userId: userIds[0],
          commandType: "MOVE",
          expectedVersion
        }
      });

    assert.equal(
      winningMoves.length,
      1,
      `Expected exactly one committed final MOVE, got ${winningMoves.length}`
    );
  }
);
