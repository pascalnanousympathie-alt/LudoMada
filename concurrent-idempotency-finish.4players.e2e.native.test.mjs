import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const BASE_URL =
  `http://127.0.0.1:${process.env.PORT || 3000}`;

const adapter = new PrismaPg({
  connectionString: process.env.DATABASE_URL
});

const prisma = new PrismaClient({ adapter });

const TEST_ID = randomUUID()
  .replace(/-/g, "")
  .slice(0, 12);

const USERS = [
  {
    username: `cidfinish_p1_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidfinish_p2_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidfinish_p3_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidfinish_p4_${TEST_ID}`,
    password: "TestPassword123"
  }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 100000n;

let tokens = [];
let userIds = [];
let gameId;

const expectedVersion = 1;
let prizePool = 0n;
let winnerBalanceBefore = 0n;

async function api(path, options = {}) {
  const response = await fetch(
    `${BASE_URL}${path}`,
    {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {})
      }
    }
  );

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
  const out = await api(
    "/api/auth/register",
    {
      method: "POST",
      body: JSON.stringify(user)
    }
  );

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
    const dbUser =
      await prisma.user.findUnique({
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

  const create = await api(
    "/api/games",
    {
      method: "POST",
      headers: {
        Authorization:
          `Bearer ${tokens[0]}`,
        "Idempotency-Key":
          `cidfinish-create-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    }
  );

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
          Authorization:
            `Bearer ${tokens[i]}`,
          "Idempotency-Key":
            `cidfinish-join-p${i + 1}-${randomUUID()}`
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
        Authorization:
          `Bearer ${tokens[0]}`,
        "Idempotency-Key":
          `cidfinish-start-${randomUUID()}`
      },
      body: JSON.stringify({})
    }
  );

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  const game =
    await prisma.game.findUnique({
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

  const state =
    authoritative.state;

  state.phase = "PLAYING";
  state.currentPlayerIndex = 0;
  state.dice = 6;
  state.stateVersion =
    expectedVersion;
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
      stateVersion:
        expectedVersion,
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
      currentTurnUserId:
        userIds[0],
      dice: 6,
      diceRolled: true,
      stateVersion:
        expectedVersion,
      finishedAt: null
    }
  });
});

after(async () => {
  await prisma.$disconnect();
});

test(
  "CONCURRENT IDEMPOTENCY FINISH: duplicate final move pays once",
  async () => {
    const idempotencyKey =
      `cidfinish-move-${randomUUID()}`;

    const request = () =>
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

    const [a, b] =
      await Promise.all([
        request(),
        request()
      ]);

    console.log(
      "CONCURRENT IDEMPOTENCY FINISH:",
      [a, b].map(r => ({
        status: r.status,
        error: r.body?.error,
        stateVersion:
          r.body?.stateVersion,
        finished:
          r.body?.finished
      }))
    );

    assert.equal(
      a.status,
      200,
      `First request failed: ${JSON.stringify(a.body)}`
    );

    assert.equal(
      b.status,
      200,
      `Second request failed: ${JSON.stringify(b.body)}`
    );

    assert.deepEqual(
      a.body,
      b.body,
      "Duplicate final move must return the same result"
    );

    assert.equal(
      a.body?.finished,
      true,
      "Final move must finish the game"
    );

    const game =
      await prisma.game.findUnique({
        where: {
          id: gameId
        }
      });

    assert.ok(game);

    assert.equal(
      game.status,
      "FINISHED"
    );

    assert.equal(
      game.winnerId,
      userIds[0]
    );

    assert.equal(
      game.winnerPayout,
      prizePool
    );

    const winnerWallet =
      await prisma.wallet.findUnique({
        where: {
          userId: userIds[0]
        }
      });

    assert.ok(winnerWallet);

    assert.equal(
      winnerWallet.balance,
      winnerBalanceBefore + prizePool,
      "Prize must be credited exactly once"
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
      prizePool
    );

    const payoutLedger =
      await prisma.ledgerEntry.findMany({
        where: {
          gameId,
          userId: userIds[0],
          type: "PRIZE_PAYOUT"
        }
      });

    assert.equal(
      payoutLedger.length,
      1,
      `Expected exactly one PRIZE_PAYOUT ledger entry, got ${payoutLedger.length}`
    );

    assert.equal(
      payoutLedger[0].amount,
      prizePool
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
