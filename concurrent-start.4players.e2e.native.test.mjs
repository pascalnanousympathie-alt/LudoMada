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
  { username: `cstart_p1_${TEST_ID}`, password: "TestPassword123" },
  { username: `cstart_p2_${TEST_ID}`, password: "TestPassword123" },
  { username: `cstart_p3_${TEST_ID}`, password: "TestPassword123" },
  { username: `cstart_p4_${TEST_ID}`, password: "TestPassword123" }
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

before(async () => {
  // Create 4 fresh test users.
  for (const user of USERS) {
    tokens.push(await register(user));
  }

  // Resolve user IDs.
  for (const user of USERS) {
    const dbUser = await prisma.user.findUnique({
      where: { username: user.username }
    });

    assert.ok(dbUser, `User not found: ${user.username}`);
    userIds.push(dbUser.id);
  }

  // Test-only wallet funding.
  for (const userId of userIds) {
    await prisma.wallet.update({
      where: { userId },
      data: { balance: TEST_BALANCE }
    });
  }

  // Player 1 creates the lobby.
  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `cstart-create-${randomUUID()}`
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
        "Idempotency-Key": `cstart-join-p${i + 1}-${randomUUID()}`
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

  // Confirm the lobby is still waiting before the concurrent start.
  const waitingGame = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(waitingGame);
  assert.equal(waitingGame.status, "WAITING");
});

after(async () => {
  await prisma.$disconnect();
});

test("concurrent start requests produce exactly one game start", async () => {
  const headersA = {
    Authorization: `Bearer ${tokens[0]}`,
    "Idempotency-Key": `cstart-A-${randomUUID()}`
  };

  const headersB = {
    Authorization: `Bearer ${tokens[0]}`,
    "Idempotency-Key": `cstart-B-${randomUUID()}`
  };

  // Launch both requests without awaiting either one first.
  const [a, b] = await Promise.all([
    api(`/api/games/${gameId}/start`, {
      method: "POST",
      headers: headersA,
      body: "{}"
    }),
    api(`/api/games/${gameId}/start`, {
      method: "POST",
      headers: headersB,
      body: "{}"
    })
  ]);

  const responses = [a, b];
  const statuses = responses.map(r => r.status).sort((x, y) => x - y);

  console.log("CONCURRENT START:", statuses);

  // Exactly one request must succeed.
  assert.equal(
    responses.filter(r => r.status === 200).length,
    1,
    `Expected exactly one 200: ${JSON.stringify(responses)}`
  );

  // The other request must conflict because the game is no longer WAITING.
  assert.equal(
    responses.filter(r => r.status === 409).length,
    1,
    `Expected exactly one 409: ${JSON.stringify(responses)}`
  );

  const success = responses.find(r => r.status === 200);
  const conflict = responses.find(r => r.status === 409);

  assert.equal(success?.body?.status, "PLAYING");
  assert.ok(
    conflict?.body?.error,
    `Missing conflict error: ${JSON.stringify(conflict?.body)}`
  );

  // The game itself must have transitioned exactly once.
  const game = await prisma.game.findUnique({
    where: { id: gameId }
  });

  assert.ok(game);
  assert.equal(game.status, "PLAYING");
  assert.equal(game.grossAmount, 2000n);
  assert.equal(game.commission, 200n);
  assert.equal(game.prizePool, 1800n);

  // Exactly one authoritative state must exist.
  const states = await prisma.authoritativeGameState.findMany({
    where: { gameId }
  });

  assert.equal(states.length, 1);

  // Exactly four entry-fee transactions.
  const entryTransactions = await prisma.transaction.findMany({
    where: {
      gameId,
      type: "WITHDRAWAL",
      reference: "GAME_ENTRY",
      status: "SUCCESS"
    }
  });

  assert.equal(
    entryTransactions.length,
    4,
    `Expected 4 entry transactions, got ${entryTransactions.length}`
  );

  assert.deepEqual(
    entryTransactions.map(t => t.userId).sort(),
    [...userIds].sort()
  );

  for (const tx of entryTransactions) {
    assert.equal(tx.amount, BigInt(ENTRY_FEE));
  }

  // Exactly one commission ledger entry.
  const ledger = await prisma.ledgerEntry.findMany({
    where: { gameId }
  });

  assert.equal(
    ledger.filter(e => e.type === "ENTRY_FEE").length,
    4
  );

  assert.equal(
    ledger.filter(e => e.type === "COMMISSION").length,
    1
  );

  // Each player must have been debited exactly once.
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

  // Exactly one GAME_STARTED event.
  const startedEvents = await prisma.gameEvent.findMany({
    where: {
      gameId,
      type: "GAME_STARTED"
    }
  });

  assert.equal(
    startedEvents.length,
    1,
    `Expected exactly 1 GAME_STARTED event, got ${startedEvents.length}`
  );

  // The successful response must report the same game.
  assert.equal(success?.body?.gameId, gameId);
  assert.equal(success?.body?.player_count, 4);
  assert.equal(success?.body?.gross_amount, "2000");
  assert.equal(success?.body?.commission_10_percent, "200");
  assert.equal(success?.body?.prize_pool, "1800");
});
