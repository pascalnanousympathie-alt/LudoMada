import "dotenv/config";
import { randomUUID } from "node:crypto";

const BASE_URL = `http://127.0.0.1:${process.env.PORT || 3000}`;

const users = [
  { username: "testplayer1", password: "TestPassword123" },
  { username: "testplayer2", password: "TestPassword123" }
];

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
  } catch {}

  return { status: response.status, body };
}

async function login(user) {
  const out = await api("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(user)
  });

  if (out.status !== 200 || !out.body?.accessToken) {
    throw new Error(`LOGIN_FAILED_${user.username}: ${JSON.stringify(out.body)}`);
  }

  return out.body.accessToken;
}

const token1 = await login(users[0]);
const token2 = await login(users[1]);

const create = await api("/api/games", {
  method: "POST",
  headers: {
    Authorization: `Bearer ${token1}`,
    "Idempotency-Key": `loadtest-create-${randomUUID()}`
  },
  body: JSON.stringify({ entryFeeMGA: 500 })
});

if (create.status !== 201 || !create.body?.gameId) {
  throw new Error(`CREATE_FAILED: ${JSON.stringify(create.body)}`);
}

const gameId = create.body.gameId;

const join = await api(`/api/games/${gameId}/join`, {
  method: "POST",
  headers: {
    Authorization: `Bearer ${token2}`,
    "Idempotency-Key": `loadtest-join-${randomUUID()}`
  },
  body: JSON.stringify({ entryFeeMGA: 500 })
});

if (join.status !== 200) {
  throw new Error(`JOIN_FAILED: ${JSON.stringify(join.body)}`);
}

const start = await api(`/api/games/${gameId}/start`, {
  method: "POST",
  headers: {
    Authorization: `Bearer ${token1}`,
    "Idempotency-Key": `loadtest-start-${randomUUID()}`
  },
  body: "{}"
});

if (start.status !== 200 || start.body?.status !== "PLAYING") {
  throw new Error(`START_FAILED: ${JSON.stringify(start.body)}`);
}

await import("node:fs/promises").then(({ writeFile }) =>
  writeFile(
    "loadtests/artillery/.loadtest.env",
    `LOADTEST_GAME_ID=${gameId}\nLOADTEST_TOKEN1=${token1}\nLOADTEST_TOKEN2=${token2}\n`,
    { mode: 0o600 }
  )
);

console.log(`GAME_ID=${gameId}`);
console.log("TOKENS_SAVED=YES");
console.log("SETUP_GAME=OK");
