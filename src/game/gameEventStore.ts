import { PrismaClient } from "@prisma/client";

export async function appendGameEvent(
  tx:any, gameId:string, stateVersion:number, type:string,
  actorUserId:string|null, payload:any
) {
  const last:any[]=await tx.$queryRawUnsafe(
    `SELECT COALESCE(MAX("sequence"),0) AS "seq" FROM "GameEvent" WHERE "gameId" = $1`,
    gameId
  );
  const sequence=Number(last[0]?.seq||0)+1;
  return tx.gameEvent.create({
    data:{gameId,sequence,type,actorUserId,stateVersion,payload}
  });
}
