import { publishGameUpdate, subscribeGameUpdates } from "../src/realtime/gameEventHub";

test("only notifies subscriptions for the matching game", () => {
  const first: unknown[] = [];
  const other: unknown[] = [];
  const unsubscribe = subscribeGameUpdates("game-a", "player-a", event => first.push(event));
  const unsubscribeOther = subscribeGameUpdates("game-b", "player-b", event => other.push(event));

  try {
    const event = publishGameUpdate("game-a", 3, "TOKEN_MOVED");
    expect(first).toHaveLength(1);
    expect(first[0]).toEqual(event);
    expect(other).toHaveLength(0);
    expect(event).toMatchObject({
      type: "game.update",
      gameId: "game-a",
      stateVersion: 3,
      reason: "TOKEN_MOVED"
    });
  } finally {
    unsubscribe();
    unsubscribeOther();
  }
});

test("unsubscribe is idempotent and stops future notifications", () => {
  const received: unknown[] = [];
  const unsubscribe = subscribeGameUpdates("game-cleanup", "player-cleanup", event => received.push(event));
  unsubscribe();
  unsubscribe();

  publishGameUpdate("game-cleanup", 4, "DICE_ROLLED");
  expect(received).toHaveLength(0);
});

test("rejects negative state versions", () => {
  expect(() => publishGameUpdate("game-version", -1, "DICE_ROLLED")).toThrow("INVALID_GAME_STATE_VERSION");
});

test("supports a terminal game-finished notification", () => {
  const event = publishGameUpdate("game-finished", 57, "GAME_FINISHED");
  expect(event).toMatchObject({
    type: "game.update",
    gameId: "game-finished",
    stateVersion: 57,
    reason: "GAME_FINISHED"
  });
});
