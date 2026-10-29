import { chooseAiMove } from "../src/game/aiOpponent";
import { newGame } from "../src/game/ludoEngine";

test("returns null unless the current player has rolled", () => {
  const state = newGame(["alice", "bob"]);
  expect(chooseAiMove(state, "alice")).toBeNull();
  expect(chooseAiMove(state, "bob")).toBeNull();
});

test("prioritizes a winning move", () => {
  const state = newGame(["alice", "bob"]);
  state.players[0].tokens = [{ pos: 56 }, { pos: 57 }, { pos: 57 }, { pos: 57 }];
  state.dice = 1;

  expect(chooseAiMove(state, "alice")).toBe(0);
  expect(state.players[0].tokens[0].pos).toBe(56);
});

test("prioritizes capturing an opponent over a plain one-step move", () => {
  const state = newGame(["alice", "bob"]);
  state.players[0].tokens[0].pos = 8;
  state.players[1].tokens[0].pos = 48;
  state.dice = 1;

  expect(chooseAiMove(state, "alice")).toBe(0);
  expect(state.players[1].tokens[0].pos).toBe(48);
});

test("chooses the first equivalent legal token deterministically", () => {
  const state = newGame(["alice", "bob"]);
  state.dice = 6;

  expect(chooseAiMove(state, "alice")).toBe(0);
  expect(chooseAiMove(state, "alice")).toBe(0);
});
