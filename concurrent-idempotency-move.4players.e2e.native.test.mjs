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
    username: `cidmove_p1_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidmove_p2_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidmove_p3_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidmove_p4_${TEST_ID}`,
    password: "TestPassword123"
  }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 100000n;

let tokens = [];
let userIds = [];
let gameId;
let expectedVersion = 1;

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
    `REGISTER failed for ${user.username}: ` +
      JSON.stringify(out.body)
  );

  assert.ok(out.body?.accessToken);

  return out.body.accessToken;
}

before(async () => {
  for (const user of USERS) {
    tokens.push(
      await register(user)
    );
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
          `cidmove-create-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    }
  );

  assert.equal(
    create.status,
    201,
    `CREATE failed: ` +
      JSON.stringify(create.body)
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
            `cidmove-join-p${i + 1}-${randomUUID()}`
        },
        body: JSON.stringify({
          entryFeeMGA: ENTRY_FEE
        })
      }
    );

    assert.equal(
      join.status,
      200,
      `JOIN P${i + 1} failed: ` +
        JSON.stringify(join.body)
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
          `cidmove-start-${randomUUID()}`
      },
      body: JSON.stringify({})
    }
  );

  assert.equal(
    start.status,
    200,
    `START failed: ` +
      JSON.stringify(start.body)
  );

  /*
   * Prepare deterministic legal move:
   *
   * Player 1 turn.
   * Dice = 6.
   * Token 0 at home.
   * One move changes token 0 from -1 to 0.
   */
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

  state.players[0].tokens[0] = {
    pos: -1
  };

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
  "CONCURRENT IDEMPOTENCY MOVE: duplicate move commits once",
  async () => {
    const idempotencyKey =
      `cidmove-move-${randomUUID()}`;

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

    const [a, b] = await Promise.all([
      request(),
      request()
    ]);

    console.log(
      "CONCURRENT IDEMPOTENCY MOVE:",
      [a, b].map(r => ({
        status: r.status,
        error: r.body?.error,
        stateVersion:
          r.body?.stateVersion,
        captured:
          r.body?.captured,
        finished:
          r.body?.finished
      }))
    );

    const successes = [a, b].filter(
      r => r.status === 200
    );

    assert.equal(
      successes.length,
      2,
      `Expected both duplicate requests to succeed: ` +
        JSON.stringify([a, b])
    );

    assert.deepEqual(
      a.body,
      b.body,
      `Expected identical idempotent responses: ` +
        JSON.stringify([a, b])
    );

    const finalState = await api(
      `/api/games/${gameId}/state`,
      {
        headers: {
          Authorization:
            `Bearer ${tokens[0]}`
        }
      }
    );

    assert.equal(
      finalState.status,
      200,
      `STATE failed: ` +
        JSON.stringify(finalState.body)
    );

    /*
     * Exactly one move must occur.
     * The token starts at -1 and enters at 0.
     */
    assert.equal(
      finalState.body.players[0].tokens[0].pos,
      0,
      `Token moved incorrectly or more than once: ` +
        JSON.stringify(
          finalState.body.players[0].tokens[0]
        )
    );

    assert.equal(
      finalState.body.stateVersion,
      expectedVersion + 1,
      `Expected one state transition to version ` +
        `${expectedVersion + 1}, got ` +
        finalState.body.stateVersion
    );

    const moveCommands =
      await prisma.gameCommand.findMany({
        where: {
          gameId,
          userId: userIds[0],
          commandType: "MOVE",
          expectedVersion
        }
      });

    assert.equal(
      moveCommands.length,
      1,
      `Expected exactly one durable MOVE command, got ` +
        moveCommands.length
    );

    assert.equal(
      moveCommands[0].idempotencyKey,
      idempotencyKey,
      "Stored MOVE has unexpected idempotency key"
    );

    const movedEvents =
      await prisma.gameEvent.findMany({
        where: {
          gameId,
          type: "TOKEN_MOVED"
        }
      });

    assert.equal(
      movedEvents.length,
      1,
      `Expected exactly one TOKEN_MOVED event, got ` +
        movedEvents.length
    );
  }
);
