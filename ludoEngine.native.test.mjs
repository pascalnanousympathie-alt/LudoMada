import test from "node:test";
import assert from "node:assert/strict";

const engine = await import("./src/game/ludoEngine.ts");

const {
  newGame,
  rollDice,
  moveToken,
  LUDO_MADA_RULESET_V1
} = engine;

test("ROLL with deterministic dice 6 creates a legal move", () => {
  const player1 = "player-1";
  const player2 = "player-2";

  const state = newGame(
    [player1, player2],
    LUDO_MADA_RULESET_V1
  );

  assert.equal(state.phase, "PLAYING");
  assert.equal(state.stateVersion, 0);
  assert.equal(state.currentPlayerIndex, 0);
  assert.equal(state.dice, null);

  const dice = rollDice(
    state,
    player1,
    () => 0.999999
  );

  assert.equal(dice, 6);
  assert.equal(state.dice, 6);
  assert.equal(state.currentPlayerIndex, 0);
  assert.equal(state.stateVersion, 1);
});

test("MOVE after dice 6 moves the selected token and increments stateVersion", () => {
  const player1 = "player-1";
  const player2 = "player-2";

  const state = newGame(
    [player1, player2],
    LUDO_MADA_RULESET_V1
  );

  rollDice(
    state,
    player1,
    () => 0.999999
  );

  const result = moveToken(
    state,
    player1,
    0
  );

  assert.equal(result.finished, false);
  assert.equal(state.players[0].tokens[0].pos, 0);
  assert.equal(state.dice, null);
  assert.equal(state.stateVersion, 2);
  assert.equal(state.currentPlayerIndex, 0);
});

test("wrong player cannot ROLL", () => {
  const player1 = "player-1";
  const player2 = "player-2";

  const state = newGame(
    [player1, player2],
    LUDO_MADA_RULESET_V1
  );

  assert.throws(
    () => rollDice(
      state,
      player2,
      () => 0.999999
    ),
    {
      message: "NOT_YOUR_TURN"
    }
  );
});

test("cannot MOVE before ROLL", () => {
  const player1 = "player-1";
  const player2 = "player-2";

  const state = newGame(
    [player1, player2],
    LUDO_MADA_RULESET_V1
  );

  assert.throws(
    () => moveToken(
      state,
      player1,
      0
    ),
    {
      message: "DICE_NOT_ROLLED"
    }
  );
});
