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
  { username: `cmove_p1_${TEST_ID}`, password: "TestPassword123" },
  { username: `cmove_p2_${TEST_ID}`, password: "TestPassword123" },
  { username: `cmove_p3_${TEST_ID}`, password: "TestPassword123" },
  { username: `cmove_p4_${TEST_ID}`, password: "TestPassword123" }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 100000n;

let tokens = [];
let userIds = [];
let gameId;
let expectedVersion = 1;

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
      where: { username: user.username }
    });

    assert.ok(dbUser, `User not found: ${user.username}`);
    userIds.push(dbUser.id);
  }

  for (const userId of userIds) {
    await prisma.wallet.update({
      where: { userId },
      data: { balance: TEST_BALANCE }
    });
  }

  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `cmove-create-${randomUUID()}`
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
    const join = await api(`/api/games/${gameId}/join`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[i]}`,
        "Idempotency-Key": `cmove-join-p${i + 1}-${randomUUID()}`
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

  const start = await api(`/api/games/${gameId}/start`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `cmove-start-${randomUUID()}`
    },
    body: JSON.stringify({})
  });

  assert.equal(
    start.status,
    200,
    `START failed: ${JSON.stringify(start.body)}`
  );

  /*
   * Make a deterministic legal move directly in the
   * authoritative test state:
   *
   * Player 1 is current player.
   * Token 0 is at home.
   * Dice = 6.
   * Version = 1.
   */
  const authoritative =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(authoritative, "Authoritative state not found");

  const state = authoritative.state;

  state.currentPlayerIndex = 0;
  state.dice = 6;
  state.stateVersion = expectedVersion;
  state.phase = "PLAYING";
  state.winnerId = null;

  await prisma.authoritativeGameState.update({
    where: { gameId },
    data: {
      stateVersion: expectedVersion,
      state
    }
  });

  await prisma.game.update({
    where: { id: gameId },
    data: {
      status: "PLAYING",
      currentTurnUserId: userIds[0],
      dice: 6,
      diceRolled: true,
      stateVersion: expectedVersion
    }
  });
});

after(async () => {
  await prisma.$disconnect();
});

test(
  "CONCURRENT MOVE: only one move commits the same state version",
  async () => {
    const request = (idempotencyKey) =>
      api(`/api/games/${gameId}/move`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${tokens[0]}`,
          "Idempotency-Key": idempotencyKey
        },
        body: JSON.stringify({
          tokenIndex: 0,
          expectedVersion
        })
      });

    const [a, b] = await Promise.all([
      request(`cmove-a-${randomUUID()}`),
      request(`cmove-b-${randomUUID()}`)
    ]);

    console.log(
      "CONCURRENT MOVE:",
      [a, b].map(r => ({
        status: r.status,
        error: r.body?.error,
        stateVersion: r.body?.stateVersion
      }))
    );

    const successes = [a, b].filter(
      r => r.status === 200
    );

    const conflicts = [a, b].filter(
      r =>
        r.status === 409 &&
        r.body?.error === "STATE_VERSION_CONFLICT"
    );

    assert.equal(
      successes.length,
      1,
      `Expected one successful move: ${JSON.stringify([a, b])}`
    );

    assert.equal(
      conflicts.length,
      1,
      `Expected one STATE_VERSION_CONFLICT: ${JSON.stringify([a, b])}`
    );

    const finalState = await api(
      `/api/games/${gameId}/state`,
      {
        method: "GET",
        headers: {
          Authorization: `Bearer ${tokens[0]}`
        }
      }
    );

    assert.equal(
      finalState.status,
      200,
      `FINAL STATE failed: ${JSON.stringify(finalState.body)}`
    );

    assert.equal(
      finalState.body.stateVersion,
      expectedVersion + 1,
      `Expected stateVersion ${expectedVersion + 1}, got ${finalState.body.stateVersion}`
    );

    assert.equal(
      finalState.body.players[0].tokens[0].pos,
      0,
      `Expected token 0 to enter board exactly once: ${JSON.stringify(finalState.body.players[0].tokens)}`
    );

    const moveCommands =
      await prisma.gameCommand.count({
        where: {
          gameId,
          commandType: "MOVE",
          expectedVersion
        }
      });

    assert.equal(
      moveCommands,
      1,
      `Expected exactly 1 committed MOVE command, got ${moveCommands}`
    );
  }
);
