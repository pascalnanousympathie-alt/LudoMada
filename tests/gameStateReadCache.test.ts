import { GameStateReadCache, type GameStateReadCacheEntry } from "../src/game/gameStateReadCache";
import type { GameState } from "../src/game/ludoEngine";

function entry(gameId: string, users = ["player-1", "player-2"]): GameStateReadCacheEntry {
  return {
    gameId,
    status: "PLAYING",
    entryFee: "100",
    creatorId: users[0],
    winnerId: null,
    participants: users.map((userId) => ({ userId })),
    state: { stateVersion: 1, players: [] } as unknown as GameState
  };
}

describe("GameStateReadCache", () => {
  test("serves only participant-authorized cached game snapshots", () => {
    const cache = new GameStateReadCache();
    cache.set(entry("game-1"));

    const cached = cache.get("game-1");
    expect(cached).not.toBeNull();
    expect(cache.hasParticipant(cached!, "player-1")).toBe(true);
    expect(cache.hasParticipant(cached!, "player-2")).toBe(true);
    expect(cache.hasParticipant(cached!, "intruder")).toBe(false);
  });

  test("expires snapshots using the configured TTL", () => {
    let now = 100;
    const cache = new GameStateReadCache(50, 10, () => now);
    cache.set(entry("game-1"));
    expect(cache.get("game-1")).not.toBeNull();

    now = 151;
    expect(cache.get("game-1")).toBeNull();
    expect(cache.metrics().entries).toBe(0);
  });

  test("invalidates the committed game snapshot and advances mutation generation", () => {
    const cache = new GameStateReadCache();
    cache.set(entry("game-1"));
    const generation = cache.generation();

    cache.invalidate("game-1");

    expect(cache.get("game-1")).toBeNull();
    expect(cache.generation()).toBe(generation + 1);
  });

  test("does not repopulate stale data when a write commits during a cache miss", () => {
    const cache = new GameStateReadCache();
    const beforeQuery = cache.generation();

    cache.invalidate("game-1");
    expect(cache.set(entry("game-1"), beforeQuery)).toBe(false);
    expect(cache.get("game-1")).toBeNull();
  });

  test("bounds memory with least-recently-used eviction", () => {
    const cache = new GameStateReadCache(10_000, 2);
    cache.set(entry("game-1"));
    cache.set(entry("game-2"));
    expect(cache.get("game-1")).not.toBeNull();
    cache.set(entry("game-3"));

    expect(cache.get("game-1")).not.toBeNull();
    expect(cache.get("game-2")).toBeNull();
    expect(cache.get("game-3")).not.toBeNull();
    expect(cache.metrics().entries).toBe(2);
  });

  test("clear invalidates all entries and in-flight cache fills", () => {
    const cache = new GameStateReadCache();
    cache.set(entry("game-1"));
    const generation = cache.generation();

    cache.clear();

    expect(cache.get("game-1")).toBeNull();
    expect(cache.generation()).toBe(generation + 1);
  });
});
