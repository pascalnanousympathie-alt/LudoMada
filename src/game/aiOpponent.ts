import { GameState, legalMoves, moveToken, PlayerColor } from "./ludoEngine";

const START_OFFSET: Record<PlayerColor, number> = {
  RED: 0,
  GREEN: 13,
  YELLOW: 26,
  BLUE: 39
};

function globalSquare(color: PlayerColor, position: number): number | null {
  if (!Number.isInteger(position) || position < 0 || position > 51) return null;
  return (START_OFFSET[color] + position) % 52;
}

function cloneState(state: GameState): GameState {
  return JSON.parse(JSON.stringify(state)) as GameState;
}

function exposedTokenCount(state: GameState, playerId: string): number {
  const ownPlayer = state.players.find(player => player.id === playerId);
  const nextPlayer = state.players[state.currentPlayerIndex];
  if (!ownPlayer || !nextPlayer || nextPlayer.id === playerId) return 0;

  let exposed = 0;
  for (const ownToken of ownPlayer.tokens) {
    const square = globalSquare(ownPlayer.color, ownToken.pos);
    if (square === null || state.ruleset.safeSquares.includes(square)) continue;

    const canBeCaptured = nextPlayer.tokens.some(enemyToken => {
      for (let roll = 1; roll <= 6; roll++) {
        if (enemyToken.pos === -1 && roll !== state.ruleset.entryRoll) continue;
        const targetPosition = enemyToken.pos === -1 ? 0 : enemyToken.pos + roll;
        const targetSquare = globalSquare(nextPlayer.color, targetPosition);
        if (targetSquare === square) return true;
      }
      return false;
    });

    if (canBeCaptured) exposed++;
  }

  return exposed;
}

/**
 * Pick a legal token move with a small, deterministic heuristic.
 * This function never mutates the supplied state and never rolls dice.
 * It is intentionally an explainable starter strategy, not a learned model.
 */
export function chooseAiMove(state: GameState, playerId: string): number | null {
  const currentPlayer = state.players[state.currentPlayerIndex];
  if (
    state.phase !== "PLAYING" ||
    state.dice === null ||
    !currentPlayer ||
    currentPlayer.id !== playerId
  ) {
    return null;
  }

  const candidates = legalMoves(state, playerId);
  if (candidates.length === 0) return null;

  const ranked = candidates.map(tokenIndex => {
    const candidate = cloneState(state);
    const player = candidate.players.find(item => item.id === playerId)!;
    const previousPosition = player.tokens[tokenIndex].pos;
    const beforeOpponentPieces = candidate.players
      .filter(item => item.id !== playerId)
      .reduce((total, item) => total + item.tokens.filter(token => token.pos >= 0 && token.pos < 57).length, 0);

    const result = moveToken(candidate, playerId, tokenIndex);
    const movedPlayer = candidate.players.find(item => item.id === playerId)!;
    const newPosition = movedPlayer.tokens[tokenIndex].pos;
    const afterOpponentPieces = candidate.players
      .filter(item => item.id !== playerId)
      .reduce((total, item) => total + item.tokens.filter(token => token.pos >= 0 && token.pos < 57).length, 0);

    let score = Math.max(0, newPosition - Math.max(0, previousPosition)) * 10;

    if (previousPosition === -1 && newPosition === 0) score += 180;
    if (newPosition === 57) score += 500;
    if (result.captured > 0) score += result.captured * 300;
    if (result.extraTurn) score += 25;
    if (candidate.winnerId === playerId) score += 100_000;

    const landingSquare = globalSquare(movedPlayer.color, newPosition);
    if (landingSquare !== null && candidate.ruleset.safeSquares.includes(landingSquare)) {
      score += 45;
    }

    // A capture decreases the number of opponent pieces that remain on-board.
    score += Math.max(0, beforeOpponentPieces - afterOpponentPieces) * 20;
    score -= exposedTokenCount(candidate, playerId) * 22;

    return { tokenIndex, score };
  });

  ranked.sort((a, b) => b.score - a.score || a.tokenIndex - b.tokenIndex);
  return ranked[0]?.tokenIndex ?? null;
}
