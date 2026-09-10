import crypto from "node:crypto";
import type { Request, Response, NextFunction } from "express";
import jwt from "jsonwebtoken";
import { PrismaClient } from "@prisma/client";

export type Role = "PLAYER" | "ADMIN" | "GAME_SERVER";
export type Claims = { sub:string; role:Role; jti:string };

export function hashToken(v:string){ return crypto.createHash("sha256").update(v).digest("hex"); }
export function requestId(req:Request){
  const supplied=String(req.headers["x-request-id"]||"");
  return supplied.match(/^[A-Za-z0-9._:-]{8,100}$/)?.[0] || crypto.randomUUID();
}
export function issueAccessToken(userId:string, role:Role, secret:string){
  return jwt.sign({sub:userId,role,jti:crypto.randomUUID()},secret,{expiresIn:"15m"});
}
export function verifyAccessToken(token:string,secret:string):Claims{
  const decoded = jwt.verify(token, secret, {
    algorithms: ["HS256"]
  });

  if(typeof decoded !== "object" || decoded === null){
    throw new Error("INVALID_TOKEN_CLAIMS");
  }

  const claims = decoded as Record<string, unknown>;

  if(
    typeof claims.sub !== "string" ||
    !claims.sub ||
    typeof claims.role !== "string" ||
    !["PLAYER","ADMIN","GAME_SERVER"].includes(claims.role) ||
    typeof claims.jti !== "string" ||
    !claims.jti
  ){
    throw new Error("INVALID_TOKEN_CLAIMS");
  }

  return {
    sub: claims.sub,
    role: claims.role as Role,
    jti: claims.jti
  };
}
export function requireRole(...roles:Role[]){
  return (req:any,res:Response,next:NextFunction)=>{
    if(!req.auth || !roles.includes(req.auth.role)) return res.status(403).json({error:"Forbidden"});
    next();
  };
}
export function clientIp(req:Request){
  return String(req.headers["x-forwarded-for"]||req.socket.remoteAddress||"").split(",")[0].trim().slice(0,100);
}
export async function audit(prisma:PrismaClient, req:any, action:string, metadata?:any){
  await prisma.auditLog.create({
    data:{userId:req.auth?.sub||null,action,requestId:req.requestId,
      ip:clientIp(req),userAgent:String(req.headers["user-agent"]||"").slice(0,500),
      metadata:metadata??undefined}
  });
}
