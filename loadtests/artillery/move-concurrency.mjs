import "dotenv/config";

const baseUrl = `http://127.0.0.1:${process.env.PORT || 3000}`;

const env = await import("node:fs/promises").then(({ readFile }) =>
  readFile("loadtests/artillery/.loadtest.env", "utf8")
);

const getEnv = (name) => {
  const line = env.split("\n").find((x) => x.startsWith(`${name}=`));
  return line ? line.slice(name.length + 1) : "";
};

const gameId = getEnv("LOADTEST_GAME_ID");
const token2 = getEnv("LOADTEST_TOKEN2");

async function move(key) {
  const response = await fetch(
    `${baseUrl}/api/games/${gameId}/move`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token2}`,
        "Idempotency-Key": key
      },
      body: JSON.stringify({
        tokenIndex: 0,
        expectedVersion: 3
      })
    }
  );

  let body = null;
  try {
    body = await response.json();
  } catch {}

  return {
    status: response.status,
    body
  };
}

const [a, b] = await Promise.all([
  move(`move-concurrency-a-${Date.now()}-${Math.random()}`),
  move(`move-concurrency-b-${Date.now()}-${Math.random()}`)
]);

console.log("RESULT_A_STATUS=" + a.status);
console.log("RESULT_B_STATUS=" + b.status);

const statuses = [a.status, b.status].sort((x, y) => x - y);
console.log("SORTED_STATUSES=" + statuses.join(","));

if (
  statuses.length !== 2 ||
  statuses[0] !== 200 ||
  statuses[1] !== 409
) {
  console.error("MOVE_CONCURRENCY_TEST=FAIL");
  console.error("BODY_A=" + JSON.stringify(a.body));
  console.error("BODY_B=" + JSON.stringify(b.body));
  process.exit(1);
}

console.log("MOVE_CONCURRENCY_TEST=OK");
