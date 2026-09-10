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
  { username: `croll_p1_${TEST_ID}`, password: "TestPassword123" },
  { username: `croll_p2_${TEST_ID}`, password: "TestPassword123" },
  { username: `croll_p3_${TEST_ID}`, password: "TestPassword123" },
  { username: `croll_p4_${TEST_ID}`, password: "TestPassword123" }
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
  // 1. Create 4 fresh users.
  for (const user of USERS) {
    tokens.push(await register(user));
  }

  // 2. Resolve user IDs.
  for (const user of USERS) {
    const dbUser = await prisma.user.findUnique({
      where: { username: user.username }
    });

    assert.ok(dbUser, `User not found: ${user.username}`);
    userIds.push(dbUser.id);
  }

  // 3. Test-only wallet funding.
  for (const userId of userIds) {
    await prisma.wallet.update({
      where: { userId },
      data: { balance: TEST_BALANCE }
    });
  }

  // 4. Player 1 creates the game.
  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `croll-create-${randomUUID()}`
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

  // 5. Players 2, 3 and 4 join.
  for (let i = 1; i < 4; i++) {
    const join = await api(`/api/games/${gameId}/join`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[i]}`,
        "Idempotency-Key": `croll-join-p${i + 1}-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    });

    assert.equal(
      join.status,
      200,
      `JOIN P${i + 1} failed: ${JSON.stringify(join.body)}`
    );
  }

  // 6. Start the game.
  const start = await api(`/api/games/${gameId}/start`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `croll-start-${randomUUID()}`
    },
    body: JSON.stringify({})
  });

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  // 7. Verify initial authoritative state.
  const state = await api(`/api/games/${gameId}/state`, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${tokens[0]}`
    }
  });

  assert.equal(
    state.status,
    200,
    `STATE failed: ${JSON.stringify(state.body)}`
  );

  assert.equal(state.body.phase, "PLAYING");
  assert.equal(state.body.stateVersion, 0);
  assert.equal(state.body.currentPlayerIndex, 0);
});

after(async () => {
  await prisma.$disconnect();
});

test("CONCURRENT ROLL: only one roll commits the same state version", async () => {
  const expectedVersion = 0;

  // Player 1 is the first/current player.
  const results = await Promise.all([
    api(`/api/games/${gameId}/roll`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[0]}`,
        "Idempotency-Key": `croll-roll-a-${randomUUID()}`
      },
      body: JSON.stringify({
        expectedVersion
      })
    }),

    api(`/api/games/${gameId}/roll`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[0]}`,
        "Idempotency-Key": `croll-roll-b-${randomUUID()}`
      },
      body: JSON.stringify({
        expectedVersion
      })
    })
  ]);

  console.log(
    "CONCURRENT ROLL:",
    results.map((r) => ({
      status: r.status,
      error: r.body?.error,
      stateVersion: r.body?.stateVersion,
      dice: r.body?.dice
    }))
  );

  const statuses = results.map((r) => r.status).sort((a, b) => a - b);

  // Exactly one command should succeed and the competing command
  // must be rejected because the authoritative state version changed.
  assert.deepEqual(
    statuses,
    [200, 409],
    `Unexpected concurrent roll statuses: ${JSON.stringify(results)}`
  );

  const state = await api(`/api/games/${gameId}/state`, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${tokens[0]}`
    }
  });

  assert.equal(
    state.status,
    200,
    `FINAL STATE failed: ${JSON.stringify(state.body)}`
  );

  assert.ok(
    state.body.stateVersion === 1 || state.body.stateVersion === 2,
    `Expected one committed roll with optional auto-skip, got stateVersion=${state.body.stateVersion}`
  );

  // Verify only one ROLL command exists for this game/version.
  const rollCommands = await prisma.gameCommand.count({
    where: {
      gameId,
      commandType: "ROLL",
      expectedVersion
    }
  });

  assert.equal(
    rollCommands,
    1,
    `Expected exactly 1 committed ROLL command, got ${rollCommands}`
  );
});
