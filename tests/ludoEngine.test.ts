import { newGame, rollDice, legalMoves, moveToken } from "../src/game/ludoEngine";

test("creates 2-4 player games",()=>{
  expect(()=>newGame(["a"])).toThrow("2_TO_4_PLAYERS_REQUIRED");
  expect(newGame(["a","b"]).players).toHaveLength(2);
  expect(newGame(["a","b","c","d"]).players).toHaveLength(4);
});

test("only current player can roll",()=>{
  const s=newGame(["a","b"]);
  expect(()=>rollDice(s,"b",()=>0.1)).toThrow("NOT_YOUR_TURN");
  expect(rollDice(s,"a",()=>0.0)).toBe(1);
});

test("six allows a home token to enter",()=>{
  const s=newGame(["a","b"]);
  rollDice(s,"a",()=>5/6);
  expect(legalMoves(s,"a")).toEqual([0,1,2,3]);
  const r=moveToken(s,"a",0);
  expect(s.players[0].tokens[0].pos).toBe(0);
  expect(r.extraTurn).toBe(true);
});

test("non-six cannot leave home and automatically skips turn",()=>{
  const s=newGame(["a","b"]);

  const dice=rollDice(s,"a",()=>1/6);

  expect(dice).toBe(2);
  expect(legalMoves(s,"a")).toEqual([]);
  expect(s.dice).toBeNull();
  expect(s.currentPlayerIndex).toBe(1);
  expect(s.stateVersion).toBe(2);
});

test("six enters a token and keeps the same player's turn",()=>{
  const s=newGame(["a","b"]);

  const dice=rollDice(s,"a",()=>5/6);
  const moves=legalMoves(s,"a");
  const result=moveToken(s,"a",0);

  expect(dice).toBe(6);
  expect(moves).toEqual([0,1,2,3]);
  expect(s.players[0].tokens[0].pos).toBe(0);
  expect(s.dice).toBeNull();
  expect(s.currentPlayerIndex).toBe(0);
  expect(result.extraTurn).toBe(true);
  expect(result.captured).toBe(0);
  expect(s.phase).toBe("PLAYING");
});

test("safe square prevents capture",()=>{
  const s=newGame(["a","b"]);
  s.players[0].tokens[0].pos=8;
  s.players[1].tokens[0].pos=0; // GREEN start offset 13, not same square
  // Directly put GREEN token at relative position 47 -> global 8
  s.players[1].tokens[0].pos=47;
  s.currentPlayerIndex=0; s.dice=0;
  expect(()=>moveToken(s,"a",0)).toThrow("ILLEGAL_MOVE");
  s.dice=1; s.players[0].tokens[0].pos=7;
  const r=moveToken(s,"a",0);
  expect(r.captured).toBe(0);
  expect(s.players[1].tokens[0].pos).toBe(47);
});

test("capture sends opponent token home",()=>{
  const s=newGame(["a","b"]);

  // RED relative 8 + dice 1 lands on global square 9.
  s.players[0].tokens[0].pos=8;

  // GREEN offset is 13: (13 + 48) % 52 = 9.
  s.players[1].tokens[0].pos=48;

  s.currentPlayerIndex=0;
  s.dice=1;

  const r=moveToken(s,"a",0);

  expect(r.captured).toBe(1);
  expect(s.players[1].tokens[0].pos).toBe(-1);
});

test("cannot overshoot finish",()=>{
  const s=newGame(["a","b"]);
  s.players[0].tokens[0].pos=56;
  s.currentPlayerIndex=0; s.dice=2;
  expect(legalMoves(s,"a")).toEqual([]);
});

test("winner is only declared when all four tokens finish",()=>{
  const s=newGame(["a","b"]);
  s.players[0].tokens=[{pos:56},{pos:57},{pos:57},{pos:57}];
  s.currentPlayerIndex=0; s.dice=1;
  moveToken(s,"a",0);
  expect(s.phase).toBe("FINISHED");
  expect(s.winnerId).toBe("a");
});
