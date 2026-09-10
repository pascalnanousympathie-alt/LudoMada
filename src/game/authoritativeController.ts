import crypto from "crypto";
import { newGame, rollDice, moveToken, legalMoves, GameState } from "./ludoEngine";
import { LUDO_MADA_RULESET_V1 } from "./ruleset";

type Session = {
  state: GameState;
  updatedAt: number;
};

const games = new Map<string, Session>();

export function createAuthoritativeGame(playerIds:string[]) {
  const id = crypto.randomUUID();
  games.set(id,{state:newGame(playerIds,LUDO_MADA_RULESET_V1),updatedAt:Date.now()});
  return id;
}

export function getAuthoritativeGame(id:string) {
  const s=games.get(id);
  if(!s) throw new Error("GAME_NOT_FOUND");
  return s.state;
}

export function rollAuthoritativeGame(id:string,playerId:string) {
  const s=games.get(id);
  if(!s) throw new Error("GAME_NOT_FOUND");
  const dice=rollDice(s.state,playerId,()=>crypto.randomInt(1,7)/6);
  s.updatedAt=Date.now();
  return {dice,state:s.state};
}

export function moveAuthoritativeGame(id:string,playerId:string,tokenIndex:number,expectedVersion:number) {
  const s=games.get(id);
  if(!s) throw new Error("GAME_NOT_FOUND");
  if(s.state.stateVersion!==expectedVersion) throw new Error("STATE_VERSION_CONFLICT");
  const result=moveToken(s.state,playerId,tokenIndex);
  s.updatedAt=Date.now();
  return {result,state:s.state};
}

export function legalAuthoritativeMoves(id:string,playerId:string) {
  const s=games.get(id);
  if(!s) throw new Error("GAME_NOT_FOUND");
  return {moves:legalMoves(s.state,playerId),state:s.state};
}
