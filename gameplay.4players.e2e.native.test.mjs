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

const TEST_ID = randomUUID().replace(/-/g, "").slice(0, 12);

const USERS = [
  {
    username: `e2e4_p1_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `e2e4_p2_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `e2e4_p3_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `e2e4_p4_${TEST_ID}`,
    password: "TestPassword123"
  }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 100000n;

let tokens = [];
let userIds = [];
let gameId;

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

async function getUser(username) {
  const user = await prisma.user.findUnique({
    where: { username }
  });

  assert.ok(user, `User not found: ${username}`);

  return user;
}

before(async () => {
  // Create four fresh test users.
  for (const user of USERS) {
    tokens.push(await register(user));
  }

  // Resolve user IDs.
  for (const user of USERS) {
    const dbUser = await getUser(user.username);
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
      "Idempotency-Key": `e2e4-create-${randomUUID()}`
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
        "Idempotency-Key": `e2e4-join-p${i + 1}-${randomUUID()}`
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

  // Player 1 starts the four-player game.
  const start = await api(`/api/games/${gameId}/start`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `e2e4-start-${randomUUID()}`
    },
    body: "{}"
  });

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  assert.equal(start.body?.status, "PLAYING");
});

after(async () => {
  await prisma.$disconnect();
});

test("4-player game starts with exactly 4 players", async () => {
  const state = await api(`/api/games/${gameId}/state`, {
    headers: {
      Authorization: `Bearer ${tokens[0]}`
    }
  });

  assert.equal(
    state.status,
    200,
    `STATE failed: ${JSON.stringify(state.body)}`
  );

  assert.equal(state.body?.status, "PLAYING");
  assert.equal(state.body?.players?.length, 4);
});

test("all four players are present in authoritative state", async () => {
  const state = await api(`/api/games/${gameId}/state`, {
    headers: {
      Authorization: `Bearer ${tokens[0]}`
    }
  });

  assert.equal(state.status, 200);

  const players = state.body?.players || [];
  const ids = players.map(player => player.id).sort();

  assert.deepEqual(ids, [...userIds].sort());
});

test("four entry-fee transactions exist exactly once", async () => {
  const transactions = await prisma.transaction.findMany({
    where: {
      gameId,
      type: "WITHDRAWAL",
      reference: "GAME_ENTRY",
      status: "SUCCESS"
    }
  });

  assert.equal(
    transactions.length,
    4,
    `Expected 4 entry transactions, got ${transactions.length}`
  );

  const transactionUserIds = transactions
    .map(transaction => transaction.userId)
    .sort();

  assert.deepEqual(
    transactionUserIds,
    [...userIds].sort()
  );

  for (const transaction of transactions) {
    assert.equal(transaction.amount, BigInt(ENTRY_FEE));
  }
});

test("each player paid exactly 500 Ar", async () => {
  for (const userId of userIds) {
    const wallet = await prisma.wallet.findUnique({
      where: { userId }
    });

    assert.ok(wallet);

    assert.equal(
      wallet.balance,
      TEST_BALANCE - BigInt(ENTRY_FEE),
      `Unexpected balance for ${userId}`
    );
  }
});

test("gross, commission and prize pool are correct", async () => {
  const game = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(game);

  assert.equal(game.status, "PLAYING");

  assert.equal(
    game.grossAmount,
    2000n
  );

  assert.equal(
    game.commission,
    200n
  );

  assert.equal(
    game.prizePool,
    1800n
  );
});

test("ledger contains four entry fees and one commission", async () => {
  const ledger = await prisma.ledgerEntry.findMany({
    where: { gameId }
  });

  const entryFees = ledger.filter(
    entry => entry.type === "ENTRY_FEE"
  );

  const commissions = ledger.filter(
    entry => entry.type === "COMMISSION"
  );

  assert.equal(entryFees.length, 4);
  assert.equal(commissions.length, 1);
});

test("fifth player cannot join a full four-player game", async () => {
  const fifthUser = {
    username: `e2e4_p5_${TEST_ID}`,
    password: "TestPassword123"
  };

  const fifthToken = await register(fifthUser);

  const join = await api(`/api/games/${gameId}/join`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${fifthToken}`,
      "Idempotency-Key": `e2e4-join-p5-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: ENTRY_FEE
    })
  });

  assert.notEqual(
    join.status,
    200,
    `Fifth player unexpectedly joined: ${JSON.stringify(join.body)}`
  );
});

test("GAME_STARTED event exists exactly once", async () => {
  const events = await prisma.gameEvent.findMany({
    where: {
      gameId,
      type: "GAME_STARTED"
    }
  });

  assert.equal(events.length, 1);
});
