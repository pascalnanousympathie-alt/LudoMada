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
    username: `cidemp_p1_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidemp_p2_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidemp_p3_${TEST_ID}`,
    password: "TestPassword123"
  },
  {
    username: `cidemp_p4_${TEST_ID}`,
    password: "TestPassword123"
  }
];

const ENTRY_FEE = 500;
const TEST_BALANCE = 100000n;

let tokens = [];
let userIds = [];
let gameId;
let expectedVersion = 0;

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
          `cidemp-create-${randomUUID()}`
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
            `cidemp-join-p${i + 1}-${randomUUID()}`
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
          `cidemp-start-${randomUUID()}`
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
});

after(async () => {
  await prisma.$disconnect();
});

test(
  "CONCURRENT IDEMPOTENCY: duplicate roll commits once",
  async () => {
    const idempotencyKey =
      `cidemp-roll-${randomUUID()}`;

    const request = () =>
      api(
        `/api/games/${gameId}/roll`,
        {
          method: "POST",
          headers: {
            Authorization:
              `Bearer ${tokens[0]}`,
            "Idempotency-Key":
              idempotencyKey
          },
          body: JSON.stringify({
            expectedVersion
          })
        }
      );

    const [a, b] = await Promise.all([
      request(),
      request()
    ]);

    console.log(
      "CONCURRENT IDEMPOTENCY:",
      [a, b].map(r => ({
        status: r.status,
        error: r.body?.error,
        stateVersion:
          r.body?.stateVersion,
        dice:
          r.body?.dice
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
     * A single roll changes state version:
     *
     * Normal roll:
     *   0 -> 1
     *
     * Auto-skipped roll:
     *   0 -> 2
     *
     * because rollDice increments once for
     * the dice roll and once more when
     * automatically skipping the turn.
     */
    assert.ok(
      finalState.body.stateVersion === 1 ||
      finalState.body.stateVersion === 2,
      `Unexpected final stateVersion: ` +
        finalState.body.stateVersion
    );

    const rollCommands =
      await prisma.gameCommand.findMany({
        where: {
          gameId,
          userId: userIds[0],
          commandType: "ROLL",
          expectedVersion
        }
      });

    assert.equal(
      rollCommands.length,
      1,
      `Expected exactly one durable ROLL command, got ` +
        rollCommands.length
    );

    assert.equal(
      rollCommands[0].idempotencyKey,
      idempotencyKey,
      "Stored command has unexpected idempotency key"
    );

    const rolledEvents =
      await prisma.gameEvent.findMany({
        where: {
          gameId,
          type: "DICE_ROLLED"
        }
      });

    assert.equal(
      rolledEvents.length,
      1,
      `Expected exactly one DICE_ROLLED event, got ` +
        rolledEvents.length
    );
  }
);
