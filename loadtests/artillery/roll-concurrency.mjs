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
const token1 = getEnv("LOADTEST_TOKEN1");

async function roll(key) {
  const response = await fetch(
    `${baseUrl}/api/games/${gameId}/roll`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token1}`,
        "Idempotency-Key": key
      },
      body: JSON.stringify({
        expectedVersion: 0
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
  roll(`roll-concurrency-a-${Date.now()}`),
  roll(`roll-concurrency-b-${Date.now()}`)
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
  console.error("ROLL_CONCURRENCY_TEST=FAIL");
  process.exit(1);
}

console.log("ROLL_CONCURRENCY_TEST=OK");
