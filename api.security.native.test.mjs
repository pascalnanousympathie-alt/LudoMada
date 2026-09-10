import "dotenv/config";
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const BASE_URL = `http://127.0.0.1:${process.env.PORT || 3000}`;

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
    body,
    headers: response.headers
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

async function createGame() {
  const out = await api("/api/games", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `native-api-create-${randomUUID()}`
    },
    body: JSON.stringify({
      entryFeeMGA: 500
    })
  });

  assert.equal(
    out.status,
    201,
    `create failed: ${JSON.stringify(out.body)}`
  );

  assert.ok(out.body?.gameId);

  return out.body.gameId;
}

before(async () => {
  token1 = await login(USER_1);
  token2 = await login(USER_2);

  gameId = await createGame();

  const join = await api(
    `/api/games/${gameId}/join`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token2}`,
        "Idempotency-Key": `native-api-join-${randomUUID()}`
      },
      body: JSON.stringify({
        entryFeeMGA: 500
      })
    }
  );

  assert.equal(
    join.status,
    200,
    `join failed: ${JSON.stringify(join.body)}`
  );
});

after(async () => {
  /*
   * The test game is intentionally left in the database.
   * It is useful for inspecting the resulting state after the suite.
   */
});

test("unauthenticated ROLL is rejected", async () => {
  const out = await api(
    `/api/games/${gameId}/roll`,
    {
      method: "POST",
      headers: {
        "Idempotency-Key": `native-api-noauth-${randomUUID()}`
      },
      body: JSON.stringify({
        expectedVersion: 0
      })
    }
  );

  assert.equal(out.status, 401);
  assert.equal(out.body?.error, "Unauthorized");
});

test("non-participant cannot access game state", async () => {
  const username = `nativeoutsider${Date.now()}`;
  const password = "NativeTestPassword123";

  const register = await api("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({
      username,
      password
    })
  });

  assert.equal(
    register.status,
    201,
    `register outsider failed: ${JSON.stringify(register.body)}`
  );

  assert.ok(register.body?.accessToken);

  const out = await api(
    `/api/games/${gameId}/state`,
    {
      headers: {
        Authorization: `Bearer ${register.body.accessToken}`
      }
    }
  );

  assert.equal(out.status, 403);
});

test("non-creator cannot START the game", async () => {
  const out = await api(
    `/api/games/${gameId}/start`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token2}`,
        "Idempotency-Key": `native-api-start-forbidden-${randomUUID()}`
      },
      body: "{}"
    }
  );

  assert.equal(out.status, 403);
});

test("creator can START the game", async () => {
  const out = await api(
    `/api/games/${gameId}/start`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token1}`,
        "Idempotency-Key": `native-api-start-${randomUUID()}`
      },
      body: "{}"
    }
  );

  assert.equal(
    out.status,
    200,
    `start failed: ${JSON.stringify(out.body)}`
  );

  assert.equal(out.body?.status, "PLAYING");
  assert.equal(out.body?.player_count, 2);
  assert.equal(out.body?.stateVersion, 0);
});

test("wrong player cannot ROLL", async () => {
  const out = await api(
    `/api/games/${gameId}/roll`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token2}`,
        "Idempotency-Key": `native-api-wrong-turn-${randomUUID()}`
      },
      body: JSON.stringify({
        expectedVersion: 0
      })
    }
  );

  assert.equal(out.status, 409);
  assert.equal(out.body?.error, "NOT_YOUR_TURN");
});


test("ROLL with wrong expectedVersion is rejected", async () => {
  const out = await api(`/api/games/${gameId}/roll`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`,
      "Idempotency-Key": `native-api-version-${randomUUID()}`
    },
    body: JSON.stringify({
      expectedVersion: 999
    })
  });

  assert.equal(out.status, 409);
  assert.equal(out.body?.error, "STATE_VERSION_CONFLICT");
});

test("ROLL without Idempotency-Key is rejected", async () => {
  const out = await api(`/api/games/${gameId}/roll`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`
    },
    body: JSON.stringify({
      expectedVersion: 0
    })
  });

  assert.equal(out.status, 400);
  assert.equal(
    out.body?.error,
    "Idempotency-Key header is required"
  );
});

test("game state endpoint returns authoritative state", async () => {
  const out = await api(`/api/games/${gameId}/state`, {
    headers: {
      Authorization: `Bearer ${token1}`
    }
  });

  assert.equal(
    out.status,
    200,
    `state failed: ${JSON.stringify(out.body)}`
  );

  assert.equal(out.body?.gameId, gameId);
  assert.equal(out.body?.status, "PLAYING");
  assert.ok(Array.isArray(out.body?.players));
  assert.equal(out.body.players.length, 2);
});

test("FINISH endpoint cannot be used by PLAYER", async () => {
  const out = await api(`/api/games/${gameId}/finish`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token1}`
    },
    body: "{}"
  });

  assert.equal(out.status, 403);
});
