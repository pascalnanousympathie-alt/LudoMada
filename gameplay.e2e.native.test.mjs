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

const USER_1 = {
  username: "testplayer1",
  password: "TestPassword123"
};

const USER_2 = {
  username: "testplayer2",
  password: "TestPassword123"
};

let token1;
let token2;
let user1Id;
let user2Id;
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

async function getUser(username) {
  const user = await prisma.user.findUnique({
    where: { username }
  });

  assert.ok(user, `User not found: ${username}`);

  return user;
}

before(async () => {
  token1 = await login(USER_1);
  token2 = await login(USER_2);

  const u1 = await getUser(USER_1.username);
  const u2 = await getUser(USER_2.username);

  user1Id = u1.id;
  user2Id = u2.id;

    // Test-only wallet funding.
    // Production wallet/payment logic is not changed.
    await prisma.wallet.update({
      where: { userId: user1Id },
      data: { balance: 100000n }
    });

    await prisma.wallet.update({
      where: { userId: user2Id },
      data: { balance: 100000n }
    });

  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `e2e-create-${randomUUID()}`
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

  assert.ok(gameId);

  const join = await api(`/api/games/${gameId}/join`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token2}`,
      "Idempotency-Key": `e2e-join-${randomUUID()}`
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
      "Idempotency-Key": `e2e-start-${randomUUID()}`
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

test("authoritative state starts at version 0", async () => {
  const state = await api(`/api/games/${gameId}/state`, {
    headers: {
      Authorization: `Bearer ${token1}`
    }
  });

  assert.equal(state.status, 200);
  assert.equal(state.body?.status, "PLAYING");
  assert.equal(state.body?.stateVersion, 0);
  assert.equal(state.body?.players?.length, 2);
});

test("ROLL advances authoritative version", async () => {
  const key = `e2e-roll-${randomUUID()}`;

  const roll = await api(`/api/games/${gameId}/roll`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": key
    },
    body: JSON.stringify({
      expectedVersion: 0
    })
  });

  assert.equal(
    roll.status,
    200,
    `ROLL failed: ${JSON.stringify(roll.body)}`
  );

  assert.ok(
    roll.body?.dice >= 1 &&
    roll.body?.dice <= 6
  );

  assert.ok(
    roll.body?.stateVersion >= 1
  );
});

test("same ROLL key returns exactly the same result", async () => {
  const freshGame = await createFreshGame();

  const key = `e2e-roll-replay-${randomUUID()}`;

  const first = await api(`/api/games/${freshGame}/roll`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": key
    },
    body: JSON.stringify({
      expectedVersion: 0
    })
  });

  assert.equal(first.status, 200);

  const second = await api(`/api/games/${freshGame}/roll`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": key
    },
    body: JSON.stringify({
      expectedVersion: 0
    })
  });

  assert.equal(second.status, 200);

  assert.deepEqual(second.body, first.body);
});

test("MOVE cannot be executed with stale stateVersion", async () => {
  const state = await api(`/api/games/${gameId}/state`, {
    headers: {
      Authorization: `Bearer ${token1}`
    }
  });

  assert.equal(state.status, 200);

  const staleVersion = 0;

  const move = await api(`/api/games/${gameId}/move`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `e2e-stale-move-${randomUUID()}`
    },
    body: JSON.stringify({
      tokenIndex: 0,
      expectedVersion: staleVersion
    })
  });

  assert.equal(move.status, 409);
});

test("database contains authoritative game state", async () => {
  const state = await prisma.authoritativeGameState.findUnique({
    where: { gameId }
  });

  assert.ok(state);
  assert.ok(state.state);

  assert.ok(
    Number.isInteger(state.stateVersion)
  );
});

test("database contains GAME_STARTED event", async () => {
  const event = await prisma.gameEvent.findFirst({
    where: {
      gameId,
      type: "GAME_STARTED"
    }
  });

  assert.ok(
    event,
    "GAME_STARTED event was not found"
  );
});

test("entry-fee transactions exist exactly once per player", async () => {
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
    2,
    `Expected 2 entry transactions, got ${transactions.length}`
  );

  const userIds = transactions.map(x => x.userId).sort();

  assert.deepEqual(
    userIds,
    [user1Id, user2Id].sort()
  );
});

test("ledger contains entry fees and commission", async () => {
  const ledger = await prisma.ledgerEntry.findMany({
    where: { gameId }
  });

  assert.ok(ledger.length >= 3);

  const types = ledger.map(x => x.type);

  assert.ok(types.includes("ENTRY_FEE"));
  assert.ok(types.includes("COMMISSION"));
});

async function createFreshGame() {
  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `e2e-fresh-create-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: 500
    })
  });

  assert.equal(create.status, 201);

  const id = create.body.gameId;

  const join = await api(`/api/games/${id}/join`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token2}`,
      "Idempotency-Key": `e2e-fresh-join-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: 500
    })
  });

  assert.equal(join.status, 200);

  const start = await api(`/api/games/${id}/start`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `e2e-fresh-start-${randomUUID()}`
    },
    body: "{}"
  });

  assert.equal(start.status, 200);

  return id;
}
