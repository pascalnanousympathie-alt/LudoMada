import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import { randomUUID } from "node:crypto";

const prisma = new PrismaClient({
  adapter: new PrismaPg({
    connectionString: process.env.DATABASE_URL
  })
});

const BASE = `http://127.0.0.1:${process.env.PORT || 3000}`;

const USER_1 = "testplayer1";
const USER_2 = "testplayer2";
const PASSWORD = "TestPassword123";

let user1Id;
let user2Id;
let token1;
let token2;
let gameId;

async function login(username) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      username,
      password: PASSWORD
    })
  });

  assert.equal(res.status, 200);

  const body = await res.json();

  assert.ok(body.accessToken);

  return body.accessToken;
}

before(async () => {
  const users = await prisma.user.findMany({
    where: { username: { in: [USER_1, USER_2] } },
    select: { id: true, username: true }
  });
  assert.equal(users.length, 2, "Expected testplayer1 and testplayer2 to exist");
  user1Id = users.find(u => u.username === USER_1).id;
  user2Id = users.find(u => u.username === USER_2).id;

  token1 = await login(USER_1);
  token2 = await login(USER_2);

  gameId = `audit-auth-${randomUUID()}`;

  await prisma.game.create({
    data: {
      id: gameId,
      creatorId: user1Id,
      status: "PLAYING",
      grossAmount: 1000n,
      commission: 100n,
      prizePool: 900n,
      winnerPayout: 0n,
      adminRevenue: 100n,
      currentTurnUserId: user1Id,
      stateVersion: 0
    }
  });

  await prisma.gameParticipant.create({
    data: {
      gameId,
      userId: user1Id,
      entryFee: 500n
    }
  });

  const { newGame } =
    await import("./src/game/ludoEngine.ts");

  const state = newGame([
    user1Id,
    user2Id
  ]);

  state.players[0].tokens[0].pos = 0;
  state.dice = 1;
  state.stateVersion = 0;

  await prisma.authoritativeGameState.create({
    data: {
      gameId,
      stateVersion: 0,
      state
    }
  });
});

after(async () => {
  if (gameId) {
    await prisma.gameEvent.deleteMany({
      where: { gameId }
    });

    await prisma.gameCommand.deleteMany({
      where: { gameId }
    });

    await prisma.authoritativeGameState.deleteMany({
      where: { gameId }
    });

    await prisma.gameParticipant.deleteMany({
      where: { gameId }
    });

    await prisma.auditLog.deleteMany({
      where: {
        action: {
          in: ["GAME_MOVE", "GAME_ROLL"]
        }
      }
    });

    await prisma.game.delete({
      where: { id: gameId }
    });
  }

  await prisma.$disconnect();
});

test("non-participant cannot MOVE or ROLL", async () => {
  const moveKey = `audit-move-${randomUUID()}`;
  const rollKey = `audit-roll-${randomUUID()}`;

  const moveRes = await fetch(
    `${BASE}/api/games/${gameId}/move`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token2}`,
        "Idempotency-Key": moveKey
      },
      body: JSON.stringify({
        tokenIndex: 0,
        expectedVersion: 0
      })
    }
  );

  const moveBody = await moveRes.json();

  assert.equal(
    moveRes.status,
    409,
    `Unexpected MOVE response: ${JSON.stringify(moveBody)}`
  );
  assert.equal(moveBody.error, "NOT_GAME_PARTICIPANT");

  const rollRes = await fetch(
    `${BASE}/api/games/${gameId}/roll`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token2}`,
        "Idempotency-Key": rollKey
      },
      body: JSON.stringify({
        expectedVersion: 0
      })
    }
  );

  const rollBody = await rollRes.json();

  assert.equal(
    rollRes.status,
    409,
    `Unexpected ROLL response: ${JSON.stringify(rollBody)}`
  );
  assert.equal(rollBody.error, "NOT_GAME_PARTICIPANT");
});

test("participant MOVE succeeds and creates audit log", async () => {
  const requestId = `audit-${randomUUID()}`;
  const key = `audit-success-${randomUUID()}`;

  const res = await fetch(
    `${BASE}/api/games/${gameId}/move`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token1}`,
        "Idempotency-Key": key,
        "X-Request-Id": requestId
      },
      body: JSON.stringify({
        tokenIndex: 0,
        expectedVersion: 0
      })
    }
  );

  const body = await res.json();

  assert.equal(res.status, 200);
  assert.equal(body.stateVersion, 1);

  const audit = await prisma.auditLog.findFirst({
    where: {
      requestId,
      action: "GAME_MOVE"
    },
    orderBy: {
      createdAt: "desc"
    }
  });

  assert.ok(audit);
  assert.equal(
    audit.userId,
    user1Id
  );
  assert.equal(audit.action, "GAME_MOVE");
  assert.equal(audit.requestId, requestId);

  console.log("AUDIT USER:", audit.userId);
  console.log("AUDIT ACTION:", audit.action);
  console.log("AUDIT REQUEST ID:", audit.requestId);
});

test("participant ROLL succeeds and creates audit log", async () => {
  await prisma.authoritativeGameState.update({
    where: { gameId },
    data: {
      stateVersion: 0,
      state: {
        ruleset: {
          entryRoll: 6,
          extraTurnOnSix: true,
          extraTurnOnCapture: true,
          safeSquares: []
        },
        phase: "PLAYING",
        players: [
          {
            id: user1Id,
            color: "RED",
            tokens: [
              { pos: 0 },
              { pos: -1 },
              { pos: -1 },
              { pos: -1 }
            ]
          },
          {
            id: user2Id,
            color: "GREEN",
            tokens: [
              { pos: -1 },
              { pos: -1 },
              { pos: -1 },
              { pos: -1 }
            ]
          }
        ],
        currentPlayerIndex: 0,
        dice: null,
        stateVersion: 0,
        winnerId: null
      }
    }
  });

  await prisma.game.update({
    where: { id: gameId },
    data: {
      currentTurnUserId: user1Id,
      stateVersion: 0,
      dice: null,
      diceRolled: false
    }
  });
  const requestId = `audit-roll-${randomUUID()}`;
  const key = `audit-roll-success-${randomUUID()}`;

  const state =
    await prisma.authoritativeGameState.findUnique({
      where: { gameId }
    });

  assert.ok(state);

  const res = await fetch(
    `${BASE}/api/games/${gameId}/roll`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token1}`,
        "Idempotency-Key": key,
        "X-Request-Id": requestId
      },
      body: JSON.stringify({
        expectedVersion: state.stateVersion
      })
    }
  );

  const body = await res.json();

  assert.equal(res.status, 200);
  assert.ok(Number.isInteger(body.dice));
  assert.ok(body.dice >= 1 && body.dice <= 6);

  const audit = await prisma.auditLog.findFirst({
    where: {
      requestId,
      action: "GAME_ROLL"
    },
    orderBy: {
      createdAt: "desc"
    }
  });

  assert.ok(audit);
  assert.equal(
    audit.userId,
    user1Id
  );
  assert.equal(audit.action, "GAME_ROLL");
  assert.equal(audit.requestId, requestId);

  console.log("AUDIT ROLL USER:", audit.userId);
  console.log("AUDIT ROLL ACTION:", audit.action);
});
