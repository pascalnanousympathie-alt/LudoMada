/**
 * Server-authoritative Ludo rules engine.
 * State is pure data; the API layer is responsible for persistence/authorization.
 */
import { LudoRuleset, LUDO_MADA_RULESET_V1, validateRuleset } from "./ruleset";

export type PlayerColor = "RED"|"GREEN"|"YELLOW"|"BLUE";
export type Phase = "WAITING"|"PLAYING"|"FINISHED";
export type TokenPos = number; // -1 home, 0..51 board-relative, 52..57 final path; 57 = finish

export interface Token { pos: TokenPos; }
export interface Player { id:string; color:PlayerColor; tokens:Token[]; }
export interface GameState {
  ruleset: LudoRuleset;
  phase:Phase;
  players:Player[];
  currentPlayerIndex:number;
  dice:number|null;
  stateVersion:number;
  winnerId:string|null;
}

const COLORS:PlayerColor[]=["RED","GREEN","YELLOW","BLUE"];
const START_OFFSET:Record<PlayerColor,number>={RED:0,GREEN:13,YELLOW:26,BLUE:39};
const SAFE=new Set([0,8,13,21,26,34,39,47]);

export function newGame(playerIds:string[], ruleset:LudoRuleset=LUDO_MADA_RULESET_V1):GameState {
  validateRuleset(ruleset);
  if(playerIds.length<ruleset.playerCountMin || playerIds.length>ruleset.playerCountMax)
    throw new Error("2_TO_4_PLAYERS_REQUIRED");
  return {
    ruleset,
    phase:"PLAYING",
    players:playerIds.map((id,i)=>({
      id,color:COLORS[i],tokens:Array.from({length: ruleset.tokensPerPlayer},()=>({pos:-1}))
    })),
    currentPlayerIndex:0,dice:null,stateVersion:0,winnerId:null
  };
}

export function rollDice(state:GameState, playerId:string, rng:()=>number=Math.random):number {
  const p=state.players[state.currentPlayerIndex];
  if(p.id!==playerId) throw new Error("NOT_YOUR_TURN");
  if(state.dice!==null) throw new Error("DICE_ALREADY_ROLLED");

  const n=Math.floor(rng()*6)+1;
  state.dice=n;
  state.stateVersion++;

  // If the player has no legal move, automatically skip the turn.
  // This happens for example when all tokens are at home and the dice is 1-5.
  const hasLegalMove=p.tokens.some(t =>
    canMove(t.pos,n,state.ruleset.entryRoll)
  );

  if(!hasLegalMove) {
    state.dice=null;
    state.stateVersion++;
    state.currentPlayerIndex=
      (state.currentPlayerIndex+1)%state.players.length;
  }

  return n;
}

function canMove(pos:number,dice:number,entryRoll:number):boolean {
  if(!Number.isInteger(dice) || dice<1 || dice>6) return false;
  if(pos===-1) return dice===entryRoll;
  return pos+dice<=57;
}

export function legalMoves(state:GameState,playerId:string):number[] {
  const p=state.players[state.currentPlayerIndex];
  if(p.id!==playerId || state.dice===null) return [];
  return p.tokens
  .map((t,i)=>canMove(t.pos,state.dice!,state.ruleset.entryRoll)?i:-1)
  .filter(i=>i>=0);
}

function globalSquare(color:PlayerColor,pos:number):number|null {
  if(pos<0 || pos>51) return null;
  return (START_OFFSET[color]+pos)%52;
}

export function moveToken(state:GameState,playerId:string,tokenIndex:number):{
  captured:number, finished:boolean, extraTurn:boolean
} {
  const p=state.players[state.currentPlayerIndex];
  if(p.id!==playerId) throw new Error("NOT_YOUR_TURN");
  if(state.dice===null) throw new Error("DICE_NOT_ROLLED");
  if(!Number.isInteger(tokenIndex)||tokenIndex<0||tokenIndex>3) throw new Error("INVALID_TOKEN_INDEX");

  const dice=state.dice;
  const token=p.tokens[tokenIndex];
  if(!canMove(token.pos,dice,state.ruleset.entryRoll))
  throw new Error("ILLEGAL_MOVE");

  if(token.pos===-1) token.pos=0;
  else token.pos+=dice;

  let captured=0;
  const landing=globalSquare(p.color,token.pos);
  if(landing!==null && !state.ruleset.safeSquares.includes(landing)) {
    for(const other of state.players) {
      if(other.id===p.id) continue;
      for(const t of other.tokens) {
        if(globalSquare(other.color,t.pos)===landing) {
          t.pos=-1; captured++;
        }
      }
    }
  }

  const finished=p.tokens.every(t=>t.pos===57);
  const extraTurn=(state.ruleset.extraTurnOnSix && dice===state.ruleset.entryRoll) || (state.ruleset.extraTurnOnCapture && captured>0);

  state.dice=null;
  state.stateVersion++;

  if(finished) {
    state.phase="FINISHED";
    state.winnerId=p.id;
  } else if(!extraTurn) {
    state.currentPlayerIndex=(state.currentPlayerIndex+1)%state.players.length;
  }
  return {captured,finished,extraTurn};
}
