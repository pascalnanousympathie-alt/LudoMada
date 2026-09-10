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
  { username: `cjoin_p1_${TEST_ID}`, password: "TestPassword123" },
  { username: `cjoin_p2_${TEST_ID}`, password: "TestPassword123" },
  { username: `cjoin_p3_${TEST_ID}`, password: "TestPassword123" },
  { username: `cjoin_p4_${TEST_ID}`, password: "TestPassword123" },
  { username: `cjoin_p5_${TEST_ID}`, password: "TestPassword123" }
];

const ENTRY_FEE = 500;

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
  // Create 5 fresh users.
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

  // Player 1 creates the lobby.
  const create = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${tokens[0]}`,
      "Idempotency-Key": `cjoin-create-${randomUUID()}`
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

  // Players 2 and 3 join normally.
  for (let i = 1; i <= 2; i++) {
    const join = await api(`/api/games/${gameId}/join`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[i]}`,
        "Idempotency-Key": `cjoin-initial-p${i + 1}-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    });

    assert.equal(
      join.status,
      200,
      `Initial JOIN failed for player ${i + 1}: ${JSON.stringify(join.body)}`
    );
  }

  // Confirm exactly 3 players before the race.
  const game = await prisma.game.findUnique({
    where: { id: gameId },
    include: { participants: true }
  });

  assert.ok(game);
  assert.equal(game.status, "WAITING");
  assert.equal(game.participants.length, 3);
});

after(async () => {
  await prisma.$disconnect();
});

test("concurrent joins cannot exceed the four-player limit", async () => {
  // Player 4 and Player 5 race for the final available slot.
  const [p4, p5] = await Promise.all([
    api(`/api/games/${gameId}/join`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[3]}`,
        "Idempotency-Key": `cjoin-race-p4-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    }),

    api(`/api/games/${gameId}/join`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens[4]}`,
        "Idempotency-Key": `cjoin-race-p5-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: ENTRY_FEE
      })
    })
  ]);

  const responses = [p4, p5];
  const statuses = responses.map(r => r.status).sort((a, b) => a - b);

  console.log("CONCURRENT JOIN:", statuses);

  // Exactly one must obtain the fourth slot.
  assert.equal(
    responses.filter(r => r.status === 200).length,
    1,
    `Expected exactly one successful join: ${JSON.stringify(responses)}`
  );

  // The other must be rejected.
  assert.equal(
    responses.filter(r => r.status === 409).length,
    1,
    `Expected exactly one rejected join: ${JSON.stringify(responses)}`
  );

  // Verify the final DB state.
  const game = await prisma.game.findUnique({
    where: { id: gameId },
    include: { participants: true }
  });

  assert.ok(game);
  assert.equal(game.status, "WAITING");

  // Never 5 participants.
  assert.equal(
    game.participants.length,
    4,
    `Expected exactly 4 participants, got ${game.participants.length}`
  );

  const participantIds = game.participants
    .map(p => p.userId)
    .sort();

  // Player 1, 2 and 3 are definitely present.
  assert.ok(participantIds.includes(userIds[0]));
  assert.ok(participantIds.includes(userIds[1]));
  assert.ok(participantIds.includes(userIds[2]));

  // Exactly one of Player 4 / Player 5 joined.
  const p4Joined = participantIds.includes(userIds[3]);
  const p5Joined = participantIds.includes(userIds[4]);

  assert.notEqual(
    p4Joined,
    p5Joined,
    `Expected exactly one of P4/P5 to join: ${JSON.stringify({
      p4Joined,
      p5Joined
    })}`
  );

  // Verify the participant count directly in DB.
  const participantCount = await prisma.gameParticipant.count({
    where: { gameId }
  });

  assert.equal(participantCount, 4);
});
