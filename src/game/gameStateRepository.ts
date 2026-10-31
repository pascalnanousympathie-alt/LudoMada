import { PrismaClient } from "@prisma/client";
import { GameState } from "./ludoEngine";

export class GameStateRepository {
  constructor(private prisma: PrismaClient) {}

  async create(gameId:string,state:GameState) {
    return this.prisma.authoritativeGameState.create({
      data:{gameId,stateVersion:state.stateVersion,state:state as any}
    });
  }

  async load(gameId:string) {
    return this.prisma.authoritativeGameState.findUnique({where:{gameId}});
  }

  /**
   * Executes one command in a READ COMMITTED transaction while holding the
   * authoritative game-state row lock (SELECT ... FOR UPDATE). This serializes
   * commands for the same game across API instances.
   */
  async atomicCommand<T>(
    gameId:string,
    fn:(tx:any, current:GameState)=>Promise<{state:GameState,result:T}>
  ):Promise<{state:GameState,result:T}> {
    const maxRetries = 5;

    for(let attempt=1; attempt<=maxRetries; attempt++){
      try{
        return await this.prisma.$transaction(async(tx)=>{
          const rows:any[]=await tx.$queryRawUnsafe(
            `SELECT "gameId","stateVersion","state"::text AS "state" FROM "AuthoritativeGameState" WHERE "gameId" = $1 FOR UPDATE`,
            gameId
          );

          if(!rows.length) throw new Error("GAME_STATE_NOT_FOUND");

          const current=JSON.parse(rows[0].state) as GameState;
          const out=await fn(tx,current);

          await tx.authoritativeGameState.update({
            where:{gameId},
            data:{
              stateVersion:out.state.stateVersion,
              state:out.state as any
            }
          });

          return out;
        },{isolationLevel:"ReadCommitted"});
      }catch(e:any){
        const errorText = String(e?.message ?? "");

        const serializationConflict =
          e?.code === "P2034" ||
          e?.meta?.code === "40001" ||
          errorText.includes("40001") ||
          errorText.includes("serialization failure") ||
          errorText.includes("could not serialize access");

        if(!serializationConflict || attempt===maxRetries){
          throw e;
        }

        const delayMs = 25 * attempt;
        console.warn(
          "SERIALIZATION_RETRY",
          {gameId,attempt,delayMs}
        );

        await new Promise(resolve=>setTimeout(resolve,delayMs));
      }
    }

    throw new Error("SERIALIZATION_RETRY_EXHAUSTED");
  }
}
