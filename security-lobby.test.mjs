import test from "node:test";
import assert from "node:assert/strict";

const configuredBase = process.env.LUDO_SECURITY_BASE_URL;
if (!configuredBase) {
  throw new Error("Set LUDO_SECURITY_BASE_URL to a disposable test API origin.");
}

const targetUrl = new URL(configuredBase);
const loopbackHosts = new Set(["127.0.0.1", "localhost", "[::1]"]);
const isLoopback = loopbackHosts.has(targetUrl.hostname);

if (targetUrl.protocol !== "https:" && !(targetUrl.protocol === "http:" && isLoopback)) {
  throw new Error("LUDO_SECURITY_BASE_URL must use HTTPS unless it targets loopback.");
}
if (!isLoopback && process.env.LUDO_SECURITY_ALLOW_NONLOCAL_TARGET !== "true") {
  throw new Error("Non-loopback targets require explicit opt-in; use disposable staging, never production.");
}
if (targetUrl.username || targetUrl.password) {
  throw new Error("LUDO_SECURITY_BASE_URL must not contain credentials.");
}

const BASE = targetUrl.origin;

async function login(username, password) {
  const r = await fetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({username, password})
  });

  const body = await r.json();
  assert.equal(r.ok, true, `Login failed for ${username}: ${JSON.stringify(body)}`);
  return body.accessToken;
}

async function api(path, token, method, body, idempotencyKey) {
  const headers = {
    "Content-Type": "application/json",
    "Authorization": `Bearer ${token}`,
    "Idempotency-Key": idempotencyKey
  };

  const r = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });

  let data = {};
  try {
    data = await r.json();
  } catch {}

  return {status: r.status, data};
}

test("LOBBY SECURITY: client cannot choose another user's userId", async () => {
  const p1 = await login("testplayer1", "TestPassword123");
  const p2 = await login("testplayer2", "TestPassword123");

  const create = await api(
    "/api/games",
    p1,
    "POST",
    {
      entryFeeMGA: 500,
      participants: [
        {userId: "cmtrh9or7001ws6fnvdmxw5kb"}
      ]
    },
    `sec-create-${Date.now()}`
  );

  console.log("CREATE with forged participants:", create);

  assert.notEqual(create.status, 500);
  assert.ok(
    create.status === 201 || create.status === 400,
    `Unexpected status: ${create.status}`
  );

  if (create.status === 201) {
    const gameId = create.data.gameId;
    assert.ok(gameId);

    const state = await api(
      `/api/games/${gameId}/state`,
      p1,
      "GET",
      undefined,
      `sec-state-${Date.now()}`
    );

    console.log("STATE after forged CREATE:", state);

    assert.equal(state.status, 200);
    assert.equal(state.data.participants.length, 1);
    assert.equal(
      state.data.participants[0].userId,
      "cmtrfzi6n001as6fnijnw4eky"
    );
  }
});

test("LOBBY SECURITY: JOIN always uses authenticated user, not client userId", async () => {
  const p1 = await login("testplayer1", "TestPassword123");
  const p2 = await login("testplayer2", "TestPassword123");

  const create = await api(
    "/api/games",
    p1,
    "POST",
    {entryFeeMGA: 500},
    `sec-join-create-${Date.now()}`
  );

  console.log("CREATE:", create);
  assert.equal(create.status, 201);

  const gameId = create.data.gameId;

  const join = await api(
    `/api/games/${gameId}/join`,
    p2,
    "POST",
    {
      entryFeeMGA: 500,
      userId: "cmtrfzi6n001as6fnijnw4eky"
    },
    `sec-join-${Date.now()}`
  );

  console.log("JOIN with forged userId:", join);

  assert.equal(join.status, 200);
  assert.equal(join.data.player_count, 2);
});

test("LOBBY SECURITY: non-creator cannot START", async () => {
  const p1 = await login("testplayer1", "TestPassword123");
  const p2 = await login("testplayer2", "TestPassword123");

  const create = await api(
    "/api/games",
    p1,
    "POST",
    {entryFeeMGA: 500},
    `sec-start-create-${Date.now()}`
  );

  assert.equal(create.status, 201);

  const gameId = create.data.gameId;

  const join = await api(
    `/api/games/${gameId}/join`,
    p2,
    "POST",
    {entryFeeMGA: 500},
    `sec-start-join-${Date.now()}`
  );

  assert.equal(join.status, 200);

  const start = await api(
    `/api/games/${gameId}/start`,
    p2,
    "POST",
    {},
    `sec-start-forbidden-${Date.now()}`
  );

  console.log("START by non-creator:", start);

  assert.equal(start.status, 403);
});

test("LOBBY SECURITY: creator cannot start with only one player", async () => {
  const p1 = await login("testplayer1", "TestPassword123");

  const create = await api(
    "/api/games",
    p1,
    "POST",
    {entryFeeMGA: 500},
    `sec-one-player-${Date.now()}`
  );

  assert.equal(create.status, 201);

  const gameId = create.data.gameId;

  const start = await api(
    `/api/games/${gameId}/start`,
    p1,
    "POST",
    {},
    `sec-one-player-start-${Date.now()}`
  );

  console.log("START with one player:", start);

  assert.equal(start.status, 409);
});
