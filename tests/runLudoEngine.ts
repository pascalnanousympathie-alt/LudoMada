import {
  newGame,
  rollDice,
  legalMoves,
  moveToken
} from "../src/game/ludoEngine";

let passed = 0;
let failed = 0;

function test(name: string, fn: () => void) {
  try {
    fn();
    passed++;
    console.log(`PASS: ${name}`);
  } catch (error: any) {
    failed++;
    console.error(`FAIL: ${name}`);
    console.error(error?.message || error);
  }
}

function assert(condition: boolean, message: string) {
  if (!condition) {
    throw new Error(message);
  }
}

function equal(actual: any, expected: any, message: string) {
  if (actual !== expected) {
    throw new Error(
      `${message}\nExpected: ${expected}\nActual: ${actual}`
    );
  }
}

function deepEqual(actual: any, expected: any, message: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);

  if (a !== e) {
    throw new Error(
      `${message}\nExpected: ${e}\nActual: ${a}`
    );
  }
}

/* --------------------------------------------------
   GAME CREATION
-------------------------------------------------- */

test("creates 2 player game", () => {
  const s = newGame(["a", "b"]);
  equal(s.players.length, 2, "Game must have 2 players");
});

test("creates 4 player game", () => {
  const s = newGame(["a", "b", "c", "d"]);
  equal(s.players.length, 4, "Game must have 4 players");
});

test("rejects less than 2 players", () => {
  let threw = false;

  try {
    newGame(["a"]);
  } catch (e: any) {
    threw = e.message === "2_TO_4_PLAYERS_REQUIRED";
  }

  assert(threw, "Expected 2_TO_4_PLAYERS_REQUIRED");
});

/* --------------------------------------------------
   TURN RULES
-------------------------------------------------- */

test("only current player can roll", () => {
  const s = newGame(["a", "b"]);

  let threw = false;

  try {
    rollDice(s, "b", () => 0.1);
  } catch (e: any) {
    threw = e.message === "NOT_YOUR_TURN";
  }

  assert(threw, "Player B should not roll during Player A turn");
});

/* --------------------------------------------------
   AUTO SKIP
-------------------------------------------------- */

test("non-six automatically skips turn", () => {
  const s = newGame(["a", "b"]);

  const dice = rollDice(s, "a", () => 1 / 6);

  equal(dice, 2, "Dice should be 2");
  deepEqual(legalMoves(s, "a"), [], "There should be no legal move");

  equal(s.dice, null, "Dice should be cleared");
  equal(s.currentPlayerIndex, 1, "Turn should move to Player B");
  equal(s.stateVersion, 2, "State version should increase twice");
});

/* --------------------------------------------------
   SIX / EXTRA TURN
-------------------------------------------------- */

test("six enters token and gives extra turn", () => {
  const s = newGame(["a", "b"]);

  const dice = rollDice(s, "a", () => 5 / 6);

  deepEqual(
    legalMoves(s, "a"),
    [0, 1, 2, 3],
    "All home tokens should be legal"
  );

  const result = moveToken(s, "a", 0);

  equal(dice, 6, "Dice should be 6");
  equal(s.players[0].tokens[0].pos, 0, "Token should enter board");

  equal(s.dice, null, "Dice should clear after move");
  equal(s.currentPlayerIndex, 0, "Player A should keep turn");

  equal(result.extraTurn, true, "Six should give extra turn");
  equal(result.captured, 0, "There should be no capture");
});

/* --------------------------------------------------
   CAPTURE
-------------------------------------------------- */

test("capture sends opponent home", () => {
  const s = newGame(["a", "b"]);

  // RED: relative 8 + dice 1 = global square 9
  s.players[0].tokens[0].pos = 8;

  // GREEN offset is 13.
  // (13 + 48) % 52 = 9, same landing square.
  s.players[1].tokens[0].pos = 48;

  s.currentPlayerIndex = 0;
  s.dice = 1;

  const result = moveToken(s, "a", 0);

  equal(result.captured, 1, "One opponent token should be captured");
  equal(
    s.players[1].tokens[0].pos,
    -1,
    "Captured token should return home"
  );
});

/* --------------------------------------------------
   SAFE SQUARE
-------------------------------------------------- */

test("safe square prevents capture", () => {
  const s = newGame(["a", "b"]);

  s.players[0].tokens[0].pos = 7;
  s.players[1].tokens[0].pos = 47;

  s.currentPlayerIndex = 0;
  s.dice = 1;

  const result = moveToken(s, "a", 0);

  equal(result.captured, 0, "Safe square should prevent capture");
  equal(
    s.players[1].tokens[0].pos,
    47,
    "Opponent should remain on safe square"
  );
});

/* --------------------------------------------------
   FINISH RULES
-------------------------------------------------- */

test("cannot overshoot finish", () => {
  const s = newGame(["a", "b"]);

  s.players[0].tokens[0].pos = 56;

  s.currentPlayerIndex = 0;
  s.dice = 2;

  deepEqual(
    legalMoves(s, "a"),
    [],
    "Token should not overshoot finish"
  );
});

test("all four tokens required to win", () => {
  const s = newGame(["a", "b"]);

  s.players[0].tokens = [
    { pos: 56 },
    { pos: 57 },
    { pos: 57 },
    { pos: 57 }
  ];

  s.currentPlayerIndex = 0;
  s.dice = 1;

  moveToken(s, "a", 0);

  equal(s.phase, "FINISHED", "Game should finish");
  equal(s.winnerId, "a", "Player A should win");
});

/* --------------------------------------------------
   SUMMARY
-------------------------------------------------- */

console.log("\n----------------------------");
console.log(`PASSED: ${passed}`);
console.log(`FAILED: ${failed}`);
console.log("----------------------------");

process.exit(failed > 0 ? 1 : 0);
