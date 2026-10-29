import { randomUUID } from "node:crypto";

export type GameUpdateReason =
  | "PLAYER_JOINED"
  | "GAME_STARTED"
  | "DICE_ROLLED"
  | "TOKEN_MOVED";

export interface PublicGameUpdate {
  id: string;
  type: "game.update";
  gameId: string;
  stateVersion: number | null;
  reason: GameUpdateReason;
  occurredAt: string;
}

type Subscriber = {
  userId: string;
  notify: (event: PublicGameUpdate) => void;
};

const MAX_STREAMS_PER_GAME = 64;
const MAX_STREAMS_PER_USER = 4;
const subscribersByGame = new Map<string, Map<string, Subscriber>>();

function countUserStreams(userId: string): number {
  let count = 0;
  for (const gameSubscribers of subscribersByGame.values()) {
    for (const subscriber of gameSubscribers.values()) {
      if (subscriber.userId === userId) count++;
    }
  }
  return count;
}

/**
 * Register a short-lived listener for a game.
 * Call the returned function when the HTTP stream closes.
 */
export function subscribeGameUpdates(
  gameId: string,
  userId: string,
  notify: (event: PublicGameUpdate) => void
): () => void {
  if (!gameId.trim() || !userId.trim()) {
    throw new Error("GAME_STREAM_ID_REQUIRED");
  }

  if (countUserStreams(userId) >= MAX_STREAMS_PER_USER) {
    throw new Error("USER_STREAM_CAPACITY_REACHED");
  }

  let gameSubscribers = subscribersByGame.get(gameId);
  if (!gameSubscribers) {
    gameSubscribers = new Map<string, Subscriber>();
    subscribersByGame.set(gameId, gameSubscribers);
  }

  if (gameSubscribers.size >= MAX_STREAMS_PER_GAME) {
    throw new Error("GAME_STREAM_CAPACITY_REACHED");
  }

  const subscriptionId = randomUUID();
  gameSubscribers.set(subscriptionId, { userId, notify });
  let active = true;

  return () => {
    if (!active) return;
    active = false;

    const current = subscribersByGame.get(gameId);
    current?.delete(subscriptionId);
    if (current?.size === 0) subscribersByGame.delete(gameId);
  };
}

/**
 * Notifications contain no wallet, identity, or full game-state data.
 * Clients should re-fetch the participant-authorized state endpoint after
 * receiving an update; the database remains the source of truth.
 */
export function publishGameUpdate(
  gameId: string,
  stateVersion: number | null,
  reason: GameUpdateReason
): PublicGameUpdate {
  if (stateVersion !== null && (!Number.isInteger(stateVersion) || stateVersion < 0)) {
    throw new Error("INVALID_GAME_STATE_VERSION");
  }

  const event: PublicGameUpdate = {
    id: randomUUID(),
    type: "game.update",
    gameId,
    stateVersion,
    reason,
    occurredAt: new Date().toISOString()
  };

  const gameSubscribers = subscribersByGame.get(gameId);
  if (!gameSubscribers) return event;

  for (const subscriber of gameSubscribers.values()) {
    try {
      subscriber.notify(event);
    } catch {
      // A disconnected listener must not fail a committed game command.
      // The HTTP request's close handler owns subscription cleanup.
    }
  }

  return event;
}
