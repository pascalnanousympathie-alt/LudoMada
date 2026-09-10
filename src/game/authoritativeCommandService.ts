import { PrismaClient } from "@prisma/client";
import { GameStateRepository } from "./gameStateRepository";
import { appendGameEvent } from "./gameEventStore";
import { rollDice, moveToken } from "./ludoEngine";
import { randomInt } from "crypto";

export class AuthoritativeCommandService {
  constructor(private prisma:PrismaClient, private repo=new GameStateRepository(prisma)) {}

  async roll(gameId:string,userId:string,expectedVersion:number,idempotencyKey:string) {
    return this.repo.atomicCommand(gameId, async(tx,state)=>{
      const game=await tx.game.findUnique({
        where:{id:gameId}
      });

      if(!game){
        throw new Error("GAME_NOT_FOUND");
      }

      if(game.status!=="PLAYING"){
        throw new Error("GAME_NOT_PLAYING");
      }

      const participant=await tx.gameParticipant.findUnique({
        where:{
          gameId_userId:{
            gameId,
            userId
          }
        }
      });

      if(!participant){
        throw new Error("NOT_GAME_PARTICIPANT");
      }

      const prior=await tx.gameCommand.findUnique({
        where:{idempotencyKey}
      });

      if(prior){
        if(
          prior.gameId!==gameId ||
          prior.userId!==userId ||
          prior.commandType!=="ROLL" ||
          Number(prior.expectedVersion)!==expectedVersion
        ){
          throw new Error("IDEMPOTENCY_KEY_CONFLICT");
        }

        if(prior.result==null){
          throw new Error("IDEMPOTENCY_RESULT_MISSING");
        }

        return {
          state,
          result:prior.result
        };
      }

      if(state.stateVersion!==expectedVersion){
        throw new Error("STATE_VERSION_CONFLICT");
      }

      if(state.phase==="FINISHED"){
        throw new Error("GAME_FINISHED");
      }

      const dice=rollDice(
        state,
        userId,
        ()=>randomInt(0,6)/6
      );

      const autoSkipped=state.dice===null;

      const result={
        dice,
        stateVersion:state.stateVersion,
        autoSkipped,
        currentPlayerIndex:state.currentPlayerIndex
      };

      await tx.gameCommand.create({
        data:{
          idempotencyKey,
          gameId,
          userId,
          commandType:"ROLL",
          expectedVersion,
          payload:{},
          result
        }
      });

      await appendGameEvent(
        tx,
        gameId,
        state.stateVersion,
        "DICE_ROLLED",
        userId,
        {dice}
      );

      if(autoSkipped) {
        await appendGameEvent(
          tx,
          gameId,
          state.stateVersion,
          "TURN_SKIPPED",
          userId,
          {
            reason:"NO_LEGAL_MOVE",
            nextPlayerIndex:state.currentPlayerIndex
          }
        );
      }

      // Keep the read-model Game row synchronized with the
      // authoritative gameplay state after every server-side roll.
      await tx.game.update({
        where:{id:gameId},
        data:{
          currentTurnUserId:
            state.players[state.currentPlayerIndex]?.id ?? null,
          dice:state.dice,
          diceRolled:state.dice !== null,
          stateVersion:state.stateVersion
        }
      });

      return {
        state,
        result
      };
    });
  }

  async move(gameId:string,userId:string,tokenIndex:number,expectedVersion:number,idempotencyKey:string) {
    return this.repo.atomicCommand(gameId,async(tx,state)=>{
      const game=await tx.game.findUnique({where:{id:gameId}});
      if(!game) throw new Error("GAME_NOT_FOUND");

      const participant=await tx.gameParticipant.findUnique({
        where:{gameId_userId:{gameId,userId}}
      });
      if(!participant) throw new Error("NOT_GAME_PARTICIPANT");

      /*
       * Idempotency must be checked BEFORE GAME_NOT_PLAYING.
       *
       * This is important for retries of a final winning MOVE:
       * the first request may already have finished the game,
       * but a duplicate request with the same idempotency key
       * must replay the original result instead of returning
       * GAME_NOT_PLAYING.
       */
      const prior=await tx.gameCommand.findUnique({
        where:{idempotencyKey}
      });

      if(prior){
        const priorPayload:any=prior.payload ?? {};

        if(
          prior.gameId!==gameId ||
          prior.userId!==userId ||
          prior.commandType!=="MOVE" ||
          Number(priorPayload.tokenIndex)!==tokenIndex ||
          Number(prior.expectedVersion)!==expectedVersion
        ){
          throw new Error("IDEMPOTENCY_KEY_CONFLICT");
        }

        if(prior.result==null){
          throw new Error("IDEMPOTENCY_RESULT_MISSING");
        }

        return {state,result:prior.result};
      }

      if(game.status!=="PLAYING") {
        throw new Error("GAME_NOT_PLAYING");
      }

      if(state.phase==="FINISHED") {
        throw new Error("GAME_FINISHED");
      }

      if(state.stateVersion!==expectedVersion) {
        throw new Error("STATE_VERSION_CONFLICT");
      }

      const result=moveToken(state,userId,tokenIndex);
      const response={...result,stateVersion:state.stateVersion};

      await tx.gameCommand.create({
        data:{
          idempotencyKey,
          gameId,
          userId,
          commandType:"MOVE",
          expectedVersion,
          payload:{tokenIndex},
          result:response
        }
      });

      await appendGameEvent(
        tx,
        gameId,
        state.stateVersion,
        "TOKEN_MOVED",
        userId,
        {
          tokenIndex,
          ...result
        }
      );

      if(result.finished) {
        const winnerId=state.winnerId;
        if(!winnerId) throw new Error("WINNER_NOT_SET");

        const game=await tx.game.findUnique({
          where:{id:gameId}
        });

        if(!game) throw new Error("GAME_NOT_FOUND");

        if(game.status==="FINISHED"){
          throw new Error("GAME_ALREADY_FINISHED");
        }

        const winner=await tx.gameParticipant.findUnique({
          where:{
            gameId_userId:{
              gameId,
              userId:winnerId
            }
          }
        });

        if(!winner) throw new Error("WINNER_NOT_PARTICIPANT");

        const payoutKey=`game-win:${gameId}`;

        const existingPayout=await tx.transaction.findFirst({
          where:{
            userId:winnerId,
            idempotencyKey:payoutKey,
            type:"DEPOSIT",
            status:"SUCCESS"
          }
        });

        if(existingPayout) {
          throw new Error("GAME_PAYOUT_ALREADY_PROCESSED");
        }

        await tx.wallet.update({
          where:{userId:winnerId},
          data:{balance:{increment:game.prizePool}}
        });

        await tx.transaction.create({
          data:{
            userId:winnerId,
            type:"DEPOSIT",
            method:"MVOLA",
            amount:game.prizePool,
            idempotencyKey:payoutKey,
            status:"SUCCESS",
            gameId,
            reference:"GAME_PRIZE"
          }
        });

        await tx.ledgerEntry.create({
          data:{
            gameId,
            userId:winnerId,
            type:"PRIZE_PAYOUT",
            amount:game.prizePool
          }
        });

        await tx.game.update({
          where:{id:gameId},
          data:{
            status:"FINISHED",
            winnerId,
            winnerPayout:game.prizePool,
            currentTurnUserId:null,
            dice:null,
            diceRolled:false,
            stateVersion:state.stateVersion,
            finishedAt:new Date()
          }
        });

        await appendGameEvent(
          tx,
          gameId,
          state.stateVersion,
          "GAME_FINISHED",
          userId,
          {
            winnerId,
            payout:game.prizePool.toString()
          }
        );
      } else {
        await tx.game.update({
          where:{id:gameId},
          data:{
            currentTurnUserId:
              state.players[state.currentPlayerIndex]?.id ?? null,
            dice:state.dice,
            diceRolled:state.dice !== null,
            stateVersion:state.stateVersion
          }
        });
      }

      return {state,result:response};
    });
  }
}
