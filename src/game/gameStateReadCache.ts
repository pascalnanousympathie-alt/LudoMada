import type { GameState } from "./ludoEngine";

export interface GameStateReadCacheEntry {
  gameId: string;
  status: string;
  entryFee: string | null;
  creatorId: string;
  winnerId: string | null;
  participants: Array<{ userId: string }>;
  state: GameState;
}

interface StoredEntry {
  value: GameStateReadCacheEntry;
  expiresAt: number;
}

/**
 * Process-local read-through cache for the authorized game-state endpoint.
 *
 * The PostgreSQL row remains authoritative. Every successful state mutation
 * invalidates its gameId after commit. This cache is deliberately process-local;
 * it must not be used with multiple API replicas until shared invalidation is
 * implemented.
 */
export class GameStateReadCache {
  private readonly entries = new Map<string, StoredEntry>();
  private mutationGeneration = 0;
  private hits = 0;
  private misses = 0;

  constructor(
    private readonly ttlMs = 10_000,
    private readonly maxEntries = 5_000,
    private readonly now: () => number = Date.now
  ) {
    if (!Number.isInteger(ttlMs) || ttlMs < 1) {
      throw new Error("GAME_STATE_CACHE_TTL_MUST_BE_POSITIVE");
    }
    if (!Number.isInteger(maxEntries) || maxEntries < 1) {
      throw new Error("GAME_STATE_CACHE_MAX_ENTRIES_MUST_BE_POSITIVE");
    }
  }

  generation(): number {
    return this.mutationGeneration;
  }

  get(gameId: string): GameStateReadCacheEntry | null {
    const stored = this.entries.get(gameId);
    if (!stored) {
      this.misses++;
      return null;
    }

    if (stored.expiresAt <= this.now()) {
      this.entries.delete(gameId);
      this.misses++;
      return null;
    }

    this.hits++;
    // Refresh insertion order for bounded LRU eviction.
    this.entries.delete(gameId);
    this.entries.set(gameId, stored);
    return stored.value;
  }

  set(
    value: GameStateReadCacheEntry,
    expectedGeneration = this.mutationGeneration
  ): boolean {
    if (
      !value.gameId ||
      !Array.isArray(value.participants) ||
      !value.state ||
      expectedGeneration !== this.mutationGeneration
    ) {
      return false;
    }

    this.entries.delete(value.gameId);
    this.entries.set(value.gameId, {
      value,
      expiresAt: this.now() + this.ttlMs
    });

    while (this.entries.size > this.maxEntries) {
      const oldestKey = this.entries.keys().next().value;
      if (oldestKey === undefined) break;
      this.entries.delete(oldestKey);
    }

    return true;
  }

  invalidate(gameId: string): void {
    this.mutationGeneration++;
    this.entries.delete(gameId);
  }

  clear(): void {
    this.mutationGeneration++;
    this.entries.clear();
  }

  hasParticipant(entry: GameStateReadCacheEntry, userId: string): boolean {
    return entry.participants.some((participant) => participant.userId === userId);
  }

  metrics() {
    return {
      entries: this.entries.size,
      generation: this.mutationGeneration,
      hits: this.hits,
      misses: this.misses
    };
  }
}

export const gameStateReadCache = new GameStateReadCache();

export function invalidateGameStateReadCache(gameId: string): void {
  gameStateReadCache.invalidate(gameId);
}
