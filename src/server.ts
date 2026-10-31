import express from "express";
import helmet from "helmet";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { randomInt, randomUUID, createHash } from "node:crypto";
import bcrypt from "bcryptjs";
import { PrismaClient, PaymentMethod, TransactionType } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import pg from "pg";
import { z } from "zod";
import { issueAccessToken, verifyAccessToken, hashToken, requestId, audit, clientIp, requireRole } from "./security";
import { isCapacityLoadTestEnabled, isPerformanceDiagnosticsEnabled, isPoolDiagnosticsEnabled } from "./operationalModes";

import { newGame , legalMoves, GameState} from "./game/ludoEngine";
import { LUDO_MADA_RULESET_V1 } from "./game/ruleset";
import { GameStateRepository } from "./game/gameStateRepository";
import { AuthoritativeCommandService } from "./game/authoritativeCommandService";
import { appendGameEvent } from "./game/gameEventStore";
import { isValidEntryFee } from "./game/entryFees";
import { chooseAiMove } from "./game/aiOpponent";
import { publishGameUpdate, subscribeGameUpdates } from "./realtime/gameEventHub";
import crypto from "node:crypto";
import { paymentConfig } from "./payments/config";
import { providerFor } from "./payments/providers";
import { AirtelMoneyProvider } from "./payments/airtel";
const DATABASE_URL = process.env.DATABASE_URL ?? "";

if (!DATABASE_URL) {
  throw new Error("DATABASE_URL_IS_REQUIRED");
}

function poolSize(value: string | undefined, fallback: number) {
  const parsed = Number(value ?? fallback);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 20) throw new Error("PG_POOL_MAX_MUST_BE_AN_INTEGER_1_TO_20");
  return parsed;
}

const pgPoolMax = poolSize(process.env.PG_POOL_MAX, 5);

const pool = new pg.Pool({
  connectionString: DATABASE_URL,
  max: pgPoolMax,
  min: 0,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 5000,
});

const adapter = new PrismaPg(pool);

const prisma = new PrismaClient({ adapter });

const PERF_DIAGNOSTICS_ENABLED = isPerformanceDiagnosticsEnabled();
const PG_POOL_DIAGNOSTICS_ENABLED = isPoolDiagnosticsEnabled();

if (PG_POOL_DIAGNOSTICS_ENABLED) {
  setInterval(() => {
    console.log("PG_POOL_STATUS", {
      total: pool.totalCount,
      idle: pool.idleCount,
      waiting: pool.waitingCount
    });
  }, 1000).unref();
}
const authoritativeCommands = new AuthoritativeCommandService(prisma);
const gameStateRepository = new GameStateRepository(prisma);

const app=express();


if (PERF_DIAGNOSTICS_ENABLED) {
// REQUEST_E2E_TIMING_STATE_V3
app.use((req:any,res:any,next:any)=>{
  const isState =
    req.path.startsWith("/api/games/") &&
    req.path.endsWith("/state");

  if(isState){
    const start=process.hrtime.bigint();

    console.log("REQUEST_E2E_START",{
      method:req.method,
      path:req.path
    });

    res.on("finish",()=>{
      const totalMs=
        Number(process.hrtime.bigint()-start)/1e6;

      console.log("REQUEST_E2E_TIMING",{
        status:res.statusCode,
        totalMs:Number(totalMs.toFixed(2))
      });
    });
  }

  next();
});
}

const JWT_SECRET: string = process.env.JWT_SECRET ?? "";
if(JWT_SECRET.length < 32){
  throw new Error("JWT_SECRET_MUST_BE_AT_LEAST_32_CHARACTERS");
}

const NODE_ENV = process.env.NODE_ENV ?? "development";
const capacityLoadTestRequested = process.env.LUDOMADA_LOAD_TEST_MODE === "true";
const capacityLoadTestEnabled = isCapacityLoadTestEnabled();

if (capacityLoadTestRequested && !capacityLoadTestEnabled) {
  throw new Error("LUDOMADA_LOAD_TEST_MODE_REQUIRES_DISPOSABLE_CI_DATABASE");
}

const rawPort = process.env.PORT ?? "3000";
const port = Number(rawPort);

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("PORT_MUST_BE_A_VALID_INTEGER_1_TO_65535");
}

const COMMISSION_BPS = 1000;
const REFRESH_DAYS = 30;

const allowedOrigins = (process.env.CORS_ORIGINS ?? "")
  .split(",")
  .map((origin) => origin.trim())
  .filter(Boolean);

if (NODE_ENV === "production" && allowedOrigins.length === 0) {
  throw new Error("CORS_ORIGINS_IS_REQUIRED_IN_PRODUCTION");
}

if (NODE_ENV === "production") {
  if (process.env.TRUST_PROXY !== "true") {
    throw new Error("TRUST_PROXY_MUST_BE_TRUE_IN_PRODUCTION");
  }

  app.set("trust proxy", 1);
}

app.disable("x-powered-by");

app.use(helmet());

app.use(cors({
  origin(origin, callback) {
    if (!origin) {
      return callback(null, true);
    }

    if (allowedOrigins.includes(origin)) {
      return callback(null, true);
    }

    return callback(new Error("CORS_NOT_ALLOWED"));
  },
  methods: ["GET", "POST"],
  allowedHeaders: [
    "Content-Type",
    "Authorization",
    "Idempotency-Key",
    "X-Request-ID"
  ],
  maxAge: 86400
}));

app.use(express.json({ limit: "32kb", verify: (req:any,_res,buf)=>{ req.rawBody=buf.toString("utf8"); } }));

app.use((req:any,res,next)=>{
  req.requestId=requestId(req);
  res.setHeader("X-Request-ID",req.requestId);
  next();
});



const authRateLimit = rateLimit({
  windowMs: 15 * 60_000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req:any) => req.path + ":" + String(req.headers["x-forwarded-for"] || req.socket?.remoteAddress || "unknown"),
  message: { error: "Too many authentication attempts" }
});

// AUTH_TIMING_V1 — request timing is opt-in, not a synchronous hot-path log.
function auth(req:any,res:any,next:any){
  const shouldMeasureAuth =
    PERF_DIAGNOSTICS_ENABLED &&
    req.path.startsWith("/api/games/") &&
    req.path.endsWith("/state");
  const authStart = shouldMeasureAuth ? process.hrtime.bigint() : 0n;

  try {
    const h=String(req.headers.authorization||"");
    const t=h.startsWith("Bearer ")?h.slice(7):"";
    req.auth=verifyAccessToken(t,JWT_SECRET);
    req.userId=req.auth.sub;

    if (shouldMeasureAuth) {
      const authMs=Number(process.hrtime.bigint()-authStart)/1e6;
      console.log("AUTH_TIMING",{
        totalMs:Number(authMs.toFixed(3)),
        result:"OK"
      });
    }

    next();
  } catch {
    if (shouldMeasureAuth) {
      const authMs=Number(process.hrtime.bigint()-authStart)/1e6;
      console.log("AUTH_TIMING",{
        totalMs:Number(authMs.toFixed(3)),
        result:"FAIL"
      });
    }
    res.status(401).json({error:"Unauthorized"});
  }
}
function split(gross:bigint){ const commission=(gross*1000n)/10000n; return {commission,prizePool:gross-commission}; }
function refreshToken(){ return randomUUID()+"."+randomUUID()+"."+randomUUID(); }



// HTTP_CONCURRENCY_DIAG_V1

app.get("/health",(_,res)=>res.json({
  ok:true,
  service:"ludo-mada-backend-v4"
}));

app.get("/ready",async(_req,res)=>{
  try {
    await prisma.$queryRaw`SELECT 1`;
    res.json({
      ok:true,
      service:"ludo-mada-backend-v4",
      database:"ready"
    });
  } catch {
    res.status(503).json({
      ok:false,
      service:"ludo-mada-backend-v4",
      database:"unavailable"
    });
  }
});

if (!capacityLoadTestEnabled) {
  app.use(rateLimit({
    windowMs: 60_000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false
  }));
}

app.post("/api/auth/register",async(req,res)=>{
  try {
    const b=z.object({username:z.string().min(3).max(32).regex(/^[A-Za-z0-9_]+$/),password:z.string().min(10).max(128),email:z.string().email().optional(),phoneNumber:z.string().min(7).max(20).regex(/^\+?[0-9() .-]+$/).optional()}).parse(req.body);
    const hash=await bcrypt.hash(b.password,12);
    const user=await prisma.user.create({data:{username:b.username,email:b.email ?? undefined,phoneNumber:b.phoneNumber ?? undefined,passwordHash:hash,wallet:{create:{}}}});
    const refresh=refreshToken();
    await prisma.refreshSession.create({data:{userId:user.id,tokenHash:hashToken(refresh),expiresAt:new Date(Date.now()+REFRESH_DAYS*86400000)}});
    const access=issueAccessToken(user.id,"PLAYER",JWT_SECRET);
    await audit(prisma,{auth:{sub:user.id},requestId:req.get("X-Request-ID") ?? randomUUID(),headers:req.headers,socket:req.socket},"AUTH_REGISTER");
    res.status(201).json({accessToken:access,refreshToken:refresh,user:{id:user.id,username:user.username}});
  } catch(e:any){ res.status(400).json({error:e?.code==="P2002"?"Username already exists":"Registration failed"}); }
});

app.post("/api/auth/login",authRateLimit,async(req,res)=>{
  try{
    const b=z.object({
      identifier:z.string().min(1).max(128).optional(),
      username:z.string().min(1).max(128).optional(),
      password:z.string().min(1).max(128)
    }).parse(req.body);

    const user=await prisma.user.findFirst({
      where:{
        OR:[
          ...(b.identifier || b.username ? [{username:(b.identifier ?? b.username)!}] : []),
          ...(b.identifier ? [{email:b.identifier},{phoneNumber:b.identifier}] : [])
        ]
      }
    });

    if(!user || !(await bcrypt.compare(b.password,user.passwordHash))){
      return res.status(401).json({error:"Invalid credentials"});
    }

    const refresh=refreshToken();

    await prisma.refreshSession.create({
      data:{
        userId:user.id,
        tokenHash:hashToken(refresh),
        expiresAt:new Date(Date.now()+REFRESH_DAYS*86400000)
      }
    });

    const access=issueAccessToken(user.id,"PLAYER",JWT_SECRET);

    await audit(
      prisma,
      {
        auth:{sub:user.id},
        requestId:req.get("X-Request-ID") ?? randomUUID(),
        headers:req.headers,
        socket:req.socket
      },
      "AUTH_LOGIN"
    );

    res.json({
      accessToken:access,
      refreshToken:refresh,
      user:{id:user.id,username:user.username}
    });
  }catch(e:any){
    if(e instanceof z.ZodError){
      return res.status(400).json({error:"Invalid request"});
    }

    console.error("AUTH LOGIN ERROR:",{
      name:e?.name,
      message:e?.message,
      requestId:req.get("X-Request-ID")
    });

    return res.status(500).json({error:"Internal server error"});
  }
});

app.post("/api/auth/refresh",authRateLimit,async(req,res)=>{
  try{
    const b=z.object({
      refreshToken:z.string().min(20).max(512)
    }).parse(req.body);

    const tokenHash=hashToken(b.refreshToken);

    const old=await prisma.refreshSession.findUnique({
      where:{tokenHash}
    });

    if(!old){
      return res.status(401).json({error:"Invalid refresh token"});
    }

    const now=new Date();
    const fresh=refreshToken();

    const result=await prisma.$transaction(async(tx)=>{
      // Atomic one-time-use protection.
      // Only the first concurrent request can revoke this session.
      const revoked=await tx.refreshSession.updateMany({
        where:{
          id:old.id,
          revokedAt:null,
          expiresAt:{gt:now}
        },
        data:{
          revokedAt:now
        }
      });

      if(revoked.count!==1){
        throw new Error("REFRESH_TOKEN_REUSED");
      }

      await tx.refreshSession.create({
        data:{
          userId:old.userId,
          tokenHash:hashToken(fresh),
          expiresAt:new Date(Date.now()+REFRESH_DAYS*86400000)
        }
      });

      return {
        userId:old.userId
      };
    });

    res.json({
      accessToken:issueAccessToken(result.userId,"PLAYER",JWT_SECRET),
      refreshToken:fresh
    });

  }catch(e:any){
    if(e instanceof z.ZodError){
      return res.status(400).json({error:"Invalid request"});
    }

    if(e?.message==="REFRESH_TOKEN_REUSED"){
      return res.status(401).json({error:"Invalid refresh token"});
    }

    console.error("AUTH REFRESH ERROR:",{
      name:e?.name,
      message:e?.message,
      requestId:req.get("X-Request-ID")
    });

    return res.status(500).json({error:"Internal server error"});
  }
});

app.get("/api/wallet",auth,async(req:any,res)=>{
  const w=await prisma.wallet.findUnique({where:{userId:req.userId}});
  res.json({balanceMGA:(w?.balance??0n).toString()});
});

const payment=z.object({amountMGA:z.number().int().positive().max(100_000_000),method:z.nativeEnum(PaymentMethod),customerPhone:z.string().trim().min(8).max(32).optional()});

app.get("/api/payments/config",auth,async(_req,res)=>{
  res.json(paymentConfig());
});

app.get("/internal/airtel-smoke", async (req, res) => {
  const expected = process.env.AIRTEL_SMOKE_TOKEN?.trim();
  if (!expected || req.get("X-Airtel-Smoke-Token") !== expected) {
    return res.status(404).json({error:"Not found"});
  }
  try {
    const result = await new AirtelMoneyProvider().uatDiagnostics();
    return res.json(result);
  } catch (error: any) {
    return res.status(502).json({error:"AIRTEL_SMOKE_FAILED", code:String(error?.message || "UNKNOWN").slice(0,80)});
  }
});

async function reconcilePayment(txId:string){
  const tx=await prisma.transaction.findUnique({where:{id:txId}});
  if(!tx || tx.status!=="PENDING" || !tx.providerReference) return tx;
  const provider=providerFor(tx.method);
  if(!provider.enabled()) return tx;
  const pr=await provider.query(tx.providerReference,{expectedAmountMGA:tx.amount,customerPhone:tx.customerPhone||undefined,type:tx.type});
  return prisma.$transaction(async(t)=>{
    const current=await t.transaction.findUnique({where:{id:txId}});
    if(!current || current.status!=="PENDING") return current;
    const status=String(pr.providerStatus||"").toUpperCase();
    const failed=["FAILED","CANCELLED","CANCELED","REJECTED","EXPIRED"].includes(status);
    if(!pr.settled && !failed) return current;
    const nextStatus=pr.settled?"SUCCESS":"FAILED";
    const updated=await t.transaction.update({where:{id:txId},data:{status:nextStatus,providerStatus:pr.providerStatus,providerPayload:pr.raw as any}});
    // A successful status-query call only confirms that Binance accepted the
    // query. Credit a deposit solely after the provider reports settlement.
    if(pr.settled && current.type===TransactionType.DEPOSIT){
      await t.wallet.upsert({where:{userId:current.userId},create:{userId:current.userId,balance:current.amount},update:{balance:{increment:current.amount}}});
    }
    if(failed && current.type===TransactionType.WITHDRAWAL){
      await t.wallet.update({where:{userId:current.userId},data:{balance:{increment:current.amount}}});
    }
    return updated;
  });
}

app.get("/api/payments/:transactionId",auth,async(req:any,res)=>{
  const tx=await prisma.transaction.findFirst({where:{id:req.params.transactionId,userId:req.userId}});
  if(!tx) return res.status(404).json({error:"Transaction not found"});
  const reconciled=tx.status==="PENDING" ? await reconcilePayment(tx.id) : tx;
  res.json({transactionId:reconciled?.id,status:reconciled?.status,method:reconciled?.method,amountMGA:reconciled?.amount.toString(),reference:reconciled?.reference,providerReference:reconciled?.providerReference,providerStatus:reconciled?.providerStatus});
});

app.post("/api/payments/:method/webhook",async(req:any,res)=>{
  const method=String(req.params.method).toUpperCase() as PaymentMethod;
  if(!Object.values(PaymentMethod).includes(method)) return res.status(400).json({error:"Unsupported payment method"});
  const provider=providerFor(method);
  if(!provider.enabled() || !provider.verifyWebhook(req.headers as any, req.rawBody || JSON.stringify(req.body))) return res.status(401).json({error:"Invalid provider webhook"});
  const providerReference=String(req.body?.prepayId || req.body?.transactionId || req.body?.merchantTradeNo || "").trim();
  if(!providerReference) return res.status(400).json({error:"Missing provider reference"});
  const tx=await prisma.transaction.findFirst({where:{providerReference}});
  if(!tx) return res.status(404).json({error:"Transaction not found"});
  const paid=String(req.body?.status || req.body?.bizStatus || "").toUpperCase()==="PAID" || String(req.body?.status || "").toUpperCase()==="SUCCESS";
  await prisma.$transaction(async(t)=>{
    const current=await t.transaction.findUnique({where:{id:tx.id}});
    if(!current || current.status!=="PENDING") return;
    await t.transaction.update({where:{id:tx.id},data:{status:paid?"SUCCESS":"FAILED",providerStatus:String(req.body?.status || "UNKNOWN"),providerPayload:req.body}});
    if(paid && current.type===TransactionType.DEPOSIT){ await t.wallet.upsert({where:{userId:current.userId},create:{userId:current.userId,balance:current.amount},update:{balance:{increment:current.amount}}}); }
    if(!paid && current.type===TransactionType.WITHDRAWAL){ await t.wallet.update({where:{userId:current.userId},data:{balance:{increment:current.amount}}}); }
  });
  res.json({ok:true});
});

app.post("/api/wallet/deposit",auth,async(req:any,res)=>{
  const p=payment.safeParse(req.body);
  const key=String(req.headers["idempotency-key"]||"").trim();

  if(!p.success || !key || key.length>128){
    return res.status(400).json({
      error:"Invalid request or Idempotency-Key"
    });
  }

  if (!providerFor(p.data.method).enabled()) {
    return res.status(503).json({error:"PAYMENT_PROVIDER_NOT_AVAILABLE"});
  }

  try{
    const amount=BigInt(p.data.amountMGA);

    // Fast path: return the already-created transaction.
    const existing=await prisma.transaction.findUnique({
      where:{
        userId_idempotencyKey:{
          userId:req.userId,
          idempotencyKey:key
        }
      }
    });

    if(existing){
      if(
        existing.type!==TransactionType.DEPOSIT ||
        existing.amount!==amount ||
        existing.method!==p.data.method
      ){
        return res.status(409).json({
          error:"IDEMPOTENCY_KEY_CONFLICT"
        });
      }

      return res.json({
        transactionId:existing.id,
        status:existing.status
      });
    }

    // The unique constraint is the final concurrency gate.
    const tx=await prisma.transaction.create({
      data:{
        userId:req.userId,
        type:TransactionType.DEPOSIT,
        method:p.data.method,
        amount,
        customerPhone:p.data.customerPhone,
        idempotencyKey:key,
        status:"PENDING"
      }
    });

    const provider=providerFor(p.data.method);
    if(provider.enabled()){
      const pr=await provider.create({transactionId:tx.id,amountMGA:amount,method:p.data.method,type:TransactionType.DEPOSIT,customerPhone:p.data.customerPhone});
      if(!pr.accepted){
        await prisma.transaction.update({where:{id:tx.id},data:{status:"FAILED",providerStatus:pr.providerStatus,providerPayload:pr.raw as any}});
        await audit(prisma,req,"WALLET_DEPOSIT_REJECTED",{transactionId:tx.id,amount:p.data.amountMGA,method:p.data.method,errorCode:pr.errorCode});
        return res.status(502).json({transactionId:tx.id,status:"FAILED",error:pr.errorCode||"PAYMENT_PROVIDER_REJECTED"});
      }
      await prisma.transaction.update({where:{id:tx.id},data:{providerReference:pr.providerReference,providerStatus:pr.providerStatus,providerPayload:pr.raw as any}});
    }

    await audit(
      prisma,
      req,
      "WALLET_DEPOSIT_REQUEST",
      {
        transactionId:tx.id,
        amount:p.data.amountMGA,
        method:p.data.method
      }
    );

    return res.status(202).json({
      transactionId:tx.id,
      status:"PENDING"
    });

  }catch(e:any){
    // Concurrent request with the same idempotency key:
    // the other request may have won the unique constraint.
    if(e?.code==="P2002"){
      const existing=await prisma.transaction.findUnique({
        where:{
          userId_idempotencyKey:{
            userId:req.userId,
            idempotencyKey:key
          }
        }
      });

      if(existing){
        const amount=BigInt(p.data.amountMGA);

        if(
          existing.type!==TransactionType.DEPOSIT ||
          existing.amount!==amount ||
          existing.method!==p.data.method
        ){
          return res.status(409).json({
            error:"IDEMPOTENCY_KEY_CONFLICT"
          });
        }

        return res.json({
          transactionId:existing.id,
          status:existing.status
        });
      }
    }

    console.error("WALLET DEPOSIT ERROR:",{
      name:e?.name,
      message:e?.message,
      requestId:req.requestId
    });

    return res.status(500).json({
      error:"Internal server error"
    });
  }
});

app.post("/api/wallet/withdraw",auth,async(req:any,res)=>{
  const p=payment.safeParse(req.body);
  const key=String(req.headers["idempotency-key"]||"").trim();
  const amount=p.success ? BigInt(p.data.amountMGA) : 0n;

  if(!p.success || !key || key.length>128){
    return res.status(400).json({
      error:"Invalid request or Idempotency-Key"
    });
  }

  if (!providerFor(p.data.method).enabled()) {
    return res.status(503).json({error:"PAYMENT_PROVIDER_NOT_AVAILABLE"});
  }

  if (p.data.method === PaymentMethod.MVOLA) {
    return res.status(400).json({error:"MVOLA_WITHDRAWAL_API_NOT_SUPPORTED"});
  }

  try{
    /*
     * First check: if this idempotency key already exists,
     * return the original transaction without debiting again.
     */
    const existing=await prisma.transaction.findUnique({
      where:{
        userId_idempotencyKey:{
          userId:req.userId,
          idempotencyKey:key
        }
      }
    });

    if(existing){
      if(
        existing.type!==TransactionType.WITHDRAWAL ||
        existing.amount!==amount ||
        existing.method!==p.data.method
      ){
        return res.status(409).json({
          error:"IDEMPOTENCY_KEY_CONFLICT"
        });
      }

      return res.json({
        transactionId:existing.id,
        status:existing.status
      });
    }

    /*
     * Create the transaction and debit the wallet atomically.
     *
     * If another request wins the same idempotency key race,
     * the unique constraint will reject this transaction.
     * We then fetch the already-created transaction OUTSIDE
     * the failed Prisma transaction.
     */
    const r=await prisma.$transaction(async(tx)=>{
      const tr=await tx.transaction.create({
        data:{
          userId:req.userId,
          type:TransactionType.WITHDRAWAL,
          method:p.data.method,
          amount,
          customerPhone:p.data.customerPhone,
          idempotencyKey:key,
          status:"PENDING"
        }
      });

      const w=await tx.wallet.findUnique({
        where:{userId:req.userId}
      });

      if(!w || w.balance<amount){
        throw new Error("INSUFFICIENT_FUNDS");
      }

      const updated=await tx.wallet.update({
        where:{userId:req.userId},
        data:{
          balance:{decrement:amount}
        }
      });

      return {
        duplicate:false,
        id:tr.id,
        balance:updated.balance
      };
    },{
      isolationLevel:"Serializable"
    });

    const provider=providerFor(p.data.method);
    if(provider.enabled()){
      const pr=await provider.create({transactionId:r.id,amountMGA:amount,method:p.data.method,type:TransactionType.WITHDRAWAL,customerPhone:p.data.customerPhone});
      if(!pr.accepted){
        await prisma.$transaction(async(tx)=>{
          const current=await tx.transaction.findUnique({where:{id:r.id}});
          if(current?.status==="PENDING"){
            await tx.transaction.update({where:{id:r.id},data:{status:"FAILED",providerStatus:pr.providerStatus,providerPayload:pr.raw as any}});
            await tx.wallet.update({where:{userId:current.userId},data:{balance:{increment:current.amount}}});
          }
        });
        await audit(prisma,req,"WALLET_WITHDRAW_REJECTED",{transactionId:r.id,amount:p.data.amountMGA,method:p.data.method,errorCode:pr.errorCode});
        return res.status(502).json({transactionId:r.id,status:"FAILED",error:pr.errorCode||"PAYMENT_PROVIDER_REJECTED"});
      }
      await prisma.transaction.update({where:{id:r.id},data:{providerReference:pr.providerReference,providerStatus:pr.providerStatus,providerPayload:pr.raw as any}});
    }

    await audit(
      prisma,
      req,
      "WALLET_WITHDRAW_REQUEST",
      {
        transactionId:r.id,
        amount:p.data.amountMGA,
        method:p.data.method
      }
    );

    return res.status(202).json({
      transactionId:r.id,
      status:"PENDING",
      balanceMGA:r.balance.toString()
    });

  }catch(e:any){

    if(e?.message==="INSUFFICIENT_FUNDS"){
      return res.status(400).json({
        error:"Insufficient funds"
      });
    }

    /*
     * Same idempotency key raced with another request.
     * The losing transaction failed, so fetch the committed
     * transaction using the main Prisma client.
     */
    if(e?.code==="P2002"){
      const existing=await prisma.transaction.findUnique({
        where:{
          userId_idempotencyKey:{
            userId:req.userId,
            idempotencyKey:key
          }
        }
      });

      if(!existing){
        return res.status(409).json({
          error:"Idempotency conflict; please retry"
        });
      }

      if(
        existing.type!==TransactionType.WITHDRAWAL ||
        existing.amount!==amount ||
        existing.method!==p.data.method
      ){
        return res.status(409).json({
          error:"IDEMPOTENCY_KEY_CONFLICT"
        });
      }

      return res.json({
        transactionId:existing.id,
        status:existing.status
      });
    }

    const text=String(e?.message||"");
    const serializationConflict=
      e?.code==="P2034" ||
      e?.meta?.code==="40001" ||
      text.includes("40001") ||
      text.includes("serialization failure") ||
      text.includes("could not serialize access");

    if(serializationConflict){
      return res.status(409).json({
        error:"Wallet operation conflicted; please retry"
      });
    }

    console.error("WALLET WITHDRAW ERROR:",{
      name:e?.name,
      code:e?.code,
      message:e?.message,
      requestId:req.get("X-Request-ID")
    });

    return res.status(500).json({
      error:"Internal server error"
    });
  }
});

app.get("/api/wallet/transactions",auth,async(req:any,res)=>{
  const rows=await prisma.transaction.findMany({where:{userId:req.userId},orderBy:{createdAt:"desc"},take:100});
  res.json(rows.map(r=>({...r,amount:r.amount.toString(),fee:r.fee.toString()})));
});

/*
 * ============================================================
 * GAME LOBBY
 * ============================================================
 *
 * CREATE:
 *   - authenticated user becomes creator
 *   - client cannot choose arbitrary participant userIds
 *   - game starts in WAITING
 *   - no wallet debit yet
 *
 * JOIN:
 *   - authenticated user joins as req.userId
 *   - duplicate joins are rejected
 *   - maximum 4 players
 *
 * START:
 *   - creator only
 *   - 2..4 participants required
 *   - all participants must use the same entry fee
 *   - wallets are checked and debited atomically
 *   - authoritative game state is created
 */

app.post("/api/games",auth,async(req:any,res)=>{
  const b=z.object({
    entryFeeMGA:z.number().int().positive()
  }).safeParse(req.body);

  const idempotencyKey=String(
    req.headers["idempotency-key"]||""
  ).trim();

  if(
    !b.success ||
    !idempotencyKey ||
    idempotencyKey.length>128
  ){
    return res.status(400).json({
      error:"Invalid request or Idempotency-Key"
    });
  }

  const entryFee=b.data.entryFeeMGA;

  if(!isValidEntryFee(entryFee)){
    return res.status(400).json({
      error:"Invalid entry fee"
    });
  }

  const requestHash=crypto
    .createHash("sha256")
    .update(JSON.stringify({
      entryFeeMGA:entryFee,
      endpoint:"/api/games"
    }))
    .digest("hex");

  try{
    const existing=await prisma.idempotencyRecord.findUnique({
      where:{
        userId_key_endpoint:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:"/api/games"
        }
      }
    });

    if(existing){
      if(existing.requestHash!==requestHash){
        return res.status(409).json({
          error:"IDEMPOTENCY_KEY_CONFLICT"
        });
      }

      if(existing.responseStatus && existing.responseJson){
        return res
          .status(existing.responseStatus)
          .json(existing.responseJson);
      }

      return res.status(409).json({
        error:"Game creation already in progress; please retry"
      });
    }

    let game:any=null;

    for(let attempt=1;attempt<=10;attempt++){
      try{
        game=await prisma.$transaction(async(tx)=>{
          await tx.idempotencyRecord.create({
            data:{
              userId:req.userId,
              key:idempotencyKey,
              endpoint:"/api/games",
              requestHash
            }
          });

          const created=await tx.game.create({
            data:{
              creator:{
                connect:{id:req.userId}
              },
              status:"WAITING",
              grossAmount:0n,
              commission:0n,
              prizePool:0n,
              winnerPayout:0n,
              adminRevenue:0n,
              participants:{
                create:{
                  userId:req.userId,
                  entryFee:BigInt(entryFee)
                }
              }
            },
            include:{
              participants:true
            }
          });

          return created;
        },{
          isolationLevel:"ReadCommitted"
        });

        break;

      }catch(e:any){
        const errorText=String(e?.message||"");

        const serializationConflict=
          e?.code==="P2034" ||
          e?.meta?.code==="40001" ||
          errorText.includes("40001") ||
          errorText.includes("TransactionWriteConflict") ||
          errorText.includes("serialization failure") ||
          errorText.includes("could not serialize access");

        if(!serializationConflict || attempt===10){
          throw e;
        }

        const delayMs=50*attempt;

        console.log("GAME_CREATE_SERIALIZATION_RETRY",{
          attempt,
          delayMs,
          errorCode:e?.code,
          errorName:e?.name
        });

        await new Promise(resolve=>
          setTimeout(resolve,delayMs)
        );
      }
    }

    if(!game){
      throw new Error("GAME_CREATE_RETRY_FAILED");
    }

    const response={
      gameId:game.id,
      status:"WAITING",
      creatorId:req.userId,
      entry_fee_mga:String(entryFee),
      player_count:1,
      max_players:4
    };

    await prisma.idempotencyRecord.update({
      where:{
        userId_key_endpoint:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:"/api/games"
        }
      },
      data:{
        responseStatus:201,
        responseJson:response
      }
    });

    await audit(
      prisma,
      req,
      "GAME_CREATED",
      {
        gameId:game.id,
        status:"WAITING",
        entryFeeMGA:String(entryFee)
      }
    );

    return res.status(201).json(response);

  }catch(e:any){

    if(e?.code==="P2002"){
      const existing=await prisma.idempotencyRecord.findUnique({
        where:{
          userId_key_endpoint:{
            userId:req.userId,
            key:idempotencyKey,
            endpoint:"/api/games"
          }
        }
      });

      if(existing){
        if(existing.requestHash!==requestHash){
          return res.status(409).json({
            error:"IDEMPOTENCY_KEY_CONFLICT"
          });
        }

        if(existing.responseStatus && existing.responseJson){
          return res
            .status(existing.responseStatus)
            .json(existing.responseJson);
        }
      }

      return res.status(409).json({
        error:"Game creation already in progress; please retry"
      });
    }

    console.error("GAME CREATE ERROR:",{
      name:e?.name,
      code:e?.code,
      message:e?.message,
      requestId:req.get("X-Request-ID")
    });

    return res.status(500).json({
      error:"Internal server error"
    });
  }
});


/*
 * JOIN GAME
 *
 * IMPORTANT:
 * The client does NOT send userId.
 * The authenticated identity req.userId is always used.
 */
app.post("/api/games/:gameId/join",auth,async(req:any,res)=>{
  const b=z.object({
    entryFeeMGA:z.number().int().positive()
  }).safeParse(req.body);

  const idempotencyKey=String(
    req.headers["idempotency-key"]||""
  ).trim();

  if(
    !b.success ||
    !idempotencyKey ||
    idempotencyKey.length>128
  ){
    return res.status(400).json({
      error:"Invalid request or Idempotency-Key"
    });
  }

  const entryFee=b.data.entryFeeMGA;

  if(!isValidEntryFee(entryFee)){
    return res.status(400).json({
      error:"Invalid entry fee"
    });
  }

  const requestHash=crypto
    .createHash("sha256")
    .update(JSON.stringify({
      gameId:req.params.gameId,
      entryFeeMGA:entryFee,
      endpoint:"/api/games/:gameId/join"
    }))
    .digest("hex");

  try{
    const existing=await prisma.idempotencyRecord.findUnique({
      where:{
        userId_key_endpoint:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:`/api/games/${req.params.gameId}/join`
        }
      }
    });

    if(existing){
      if(existing.requestHash!==requestHash){
        return res.status(409).json({
          error:"IDEMPOTENCY_KEY_CONFLICT"
        });
      }

      if(existing.responseStatus && existing.responseJson){
        return res
          .status(existing.responseStatus)
          .json(existing.responseJson);
      }

      return res.status(409).json({
        error:"Join already in progress; please retry"
      });
    }

    let result:any=null;
    for(let attempt=1;attempt<=10;attempt++){
      try{
        result=await prisma.$transaction(async(tx)=>{
          await tx.idempotencyRecord.create({
        data:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:`/api/games/${req.params.gameId}/join`,
          requestHash
        }
      });

      const game=await tx.game.findUnique({
        where:{id:req.params.gameId},
        include:{participants:true}
      });

      if(!game){
        throw new Error("GAME_NOT_FOUND");
      }

      if(game.status!=="WAITING"){
        throw new Error("GAME_NOT_WAITING");
      }

      if(game.participants.some(p=>p.userId===req.userId)){
        throw new Error("ALREADY_PARTICIPANT");
      }

      if(game.participants.length>=4){
        throw new Error("GAME_FULL");
      }

      const creatorParticipant=game.participants.find(
        p=>p.userId===game.creatorId
      );

      if(
        !creatorParticipant ||
        creatorParticipant.entryFee!==BigInt(entryFee)
      ){
        throw new Error("ENTRY_FEE_MISMATCH");
      }

      await tx.gameParticipant.create({
        data:{
          gameId:game.id,
          userId:req.userId,
          entryFee:BigInt(entryFee)
        }
      });

          return {
            gameId:game.id,
            playerCount:game.participants.length+1,
            entryFee
          };
        },{
          isolationLevel:"Serializable"
        });
        break;
      }catch(e:any){
        const text=String(e?.message||"");
        const serializationConflict =
          e?.code==="P2034" ||
          e?.meta?.code==="40001" ||
          text.includes("40001") ||
          text.includes("TransactionWriteConflict") ||
          text.includes("serialization failure") ||
          text.includes("could not serialize access");

        if(!serializationConflict || attempt===10){
          throw e;
        }

        const delayMs=50*attempt;
        console.log("GAME_JOIN_SERIALIZATION_RETRY",{
          gameId:req.params.gameId,
          userId:req.userId,
          attempt,
          delayMs
        });

        await new Promise(resolve=>setTimeout(resolve,delayMs));
      }
    }

    if(!result){
      throw new Error("GAME_JOIN_RETRY_FAILED");
    }

    const response={
      gameId:result.gameId,
      status:"WAITING",
      player_count:result.playerCount,
      max_players:4,
      entry_fee_mga:String(result.entryFee)
    };

    await prisma.idempotencyRecord.update({
      where:{
        userId_key_endpoint:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:`/api/games/${req.params.gameId}/join`
        }
      },
      data:{
        responseStatus:200,
        responseJson:response
      }
    });

    await audit(
      prisma,
      req,
      "GAME_JOINED",
      {
        gameId:req.params.gameId
      }
    );

    publishGameUpdate(req.params.gameId,null,"PLAYER_JOINED");
    return res.json(response);

  }catch(e:any){

    const text=String(e?.message||"");

    const status=
      text==="GAME_NOT_FOUND" ? 404 :
      text==="GAME_NOT_WAITING" ? 409 :
      text==="ALREADY_PARTICIPANT" ? 409 :
      text==="GAME_FULL" ? 409 :
      text==="ENTRY_FEE_MISMATCH" ? 409 :
      e?.code==="P2002" ? 409 :
      text.includes("40001") ? 409 :
      500;

    if(status===500){
      console.error("GAME JOIN ERROR:",{
        name:e?.name,
        code:e?.code,
        message:e?.message,
        gameId:req.params.gameId,
        userId:req.userId,
        requestId:req.get("X-Request-ID")
      });
    }

    return res.status(status).json({
      error:
        status===500
          ? "Internal server error"
          : text
    });
  }
});


/*
 * START GAME
 *
 * Only the creator can start the lobby.
 * At this point all participants are verified and debited
 * atomically with game creation + authoritative state.
 */
app.post("/api/games/:gameId/start",auth,async(req:any,res)=>{
  const idempotencyKey=String(
    req.headers["idempotency-key"]||""
  ).trim();

  if(
    !idempotencyKey ||
    idempotencyKey.length>128
  ){
    return res.status(400).json({
      error:"Idempotency-Key header is required"
    });
  }

  const requestHash=crypto
    .createHash("sha256")
    .update(JSON.stringify({
      gameId:req.params.gameId,
      endpoint:"/api/games/:gameId/start"
    }))
    .digest("hex");

  try{
    const existing=await prisma.idempotencyRecord.findUnique({
      where:{
        userId_key_endpoint:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:`/api/games/${req.params.gameId}/start`
        }
      }
    });

    if(existing){
      if(existing.requestHash!==requestHash){
        return res.status(409).json({
          error:"IDEMPOTENCY_KEY_CONFLICT"
        });
      }

      if(existing.responseStatus && existing.responseJson){
        return res
          .status(existing.responseStatus)
          .json(existing.responseJson);
      }

      return res.status(409).json({
        error:"Game start already in progress; please retry"
      });
    }

    let result:any=null;

    for(let attempt=1;attempt<=10;attempt++){
      try{
        result=await prisma.$transaction(async(tx)=>{
      await tx.idempotencyRecord.create({
        data:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:`/api/games/${req.params.gameId}/start`,
          requestHash
        }
      });

      const game=await tx.game.findUnique({
        where:{id:req.params.gameId},
        include:{participants:true}
      });

      if(!game){
        throw new Error("GAME_NOT_FOUND");
      }

      if(game.creatorId!==req.userId){
        throw new Error("ONLY_CREATOR_CAN_START");
      }

      if(game.status!=="WAITING"){
        throw new Error("GAME_NOT_WAITING");
      }

      if(
        game.participants.length<2 ||
        game.participants.length>4
      ){
        throw new Error("2_TO_4_PLAYERS_REQUIRED");
      }

      const entryFee=game.participants[0].entryFee;

      if(
        !game.participants.every(
          p=>p.entryFee===entryFee
        )
      ){
        throw new Error("ENTRY_FEE_MISMATCH");
      }

      const playerIds=game.participants.map(
        p=>p.userId
      );

      const state=newGame(
        playerIds,
        LUDO_MADA_RULESET_V1
      );

      const gross=entryFee*BigInt(playerIds.length);
      const {commission,prizePool}=split(gross);

      /*
       * Check ALL wallets before any debit.
       */
      for(const p of game.participants){
        const wallet=await tx.wallet.findUnique({
          where:{userId:p.userId}
        });

        if(
          !wallet ||
          wallet.balance<entryFee
        ){
          throw new Error("INSUFFICIENT_FUNDS");
        }
      }

      const updated=await tx.game.update({
        where:{
          id:game.id
        },
        data:{
          status:"PLAYING",
          grossAmount:gross,
          commission,
          prizePool,
          winnerPayout:0n,
          adminRevenue:commission,
          currentTurnUserId:state.players[0].id,
          dice:null,
          diceRolled:false,
          stateVersion:state.stateVersion
        }
      });

      for(const p of game.participants){
        const fee=entryFee;

        await tx.wallet.update({
          where:{userId:p.userId},
          data:{
            balance:{
              decrement:fee
            }
          }
        });

        await tx.transaction.create({
          data:{
            userId:p.userId,
            type:"WITHDRAWAL",
            method:"MVOLA",
            amount:fee,
            idempotencyKey:`game:${game.id}:${p.userId}`,
            status:"SUCCESS",
            gameId:game.id,
            reference:"GAME_ENTRY"
          }
        });

        await tx.ledgerEntry.create({
          data:{
            gameId:game.id,
            userId:p.userId,
            type:"ENTRY_FEE",
            amount:fee
          }
        });
      }

      await tx.ledgerEntry.create({
        data:{
          gameId:game.id,
          type:"COMMISSION",
          amount:commission
        }
      });

      await tx.authoritativeGameState.create({
        data:{
          gameId:game.id,
          stateVersion:state.stateVersion,
          state:state as any
        }
      });

      await appendGameEvent(
        tx,
        game.id,
        state.stateVersion,
        "GAME_STARTED",
        req.userId,
        {
          players:state.players.map(p=>({
            id:p.id,
            color:p.color
          })),
          rulesetId:state.ruleset.id,
          rulesetVersion:state.ruleset.version
        }
      );

      return {
        game:updated,
        state
      };
        },{
          isolationLevel:"Serializable"
        });

        break;

      }catch(e:any){
        const serializationConflict=
          e?.code==="P2034" ||
          e?.meta?.code==="40001" ||
          String(e?.message||"").includes("40001") ||
          String(e?.message||"").includes("TransactionWriteConflict");

        if(!serializationConflict || attempt===10){
          throw e;
        }

        const delayMs=50*attempt;

        console.log("GAME_START_SERIALIZATION_RETRY",{
          gameId:req.params.gameId,
          attempt,
          delayMs
        });

        await new Promise(resolve=>
          setTimeout(resolve,delayMs)
        );
      }
    }

    if(!result){
      throw new Error("GAME_START_RETRY_FAILED");
    }

    const response={
      gameId:result.game.id,
      status:"PLAYING",
      player_count:result.state.players.length,
      stateVersion:result.state.stateVersion,
      gross_amount:result.game.grossAmount.toString(),
      commission_10_percent:result.game.commission.toString(),
      prize_pool:result.game.prizePool.toString(),
      winner_payout:"0",
      admin_revenue:result.game.adminRevenue.toString()
    };

    await prisma.idempotencyRecord.update({
      where:{
        userId_key_endpoint:{
          userId:req.userId,
          key:idempotencyKey,
          endpoint:`/api/games/${req.params.gameId}/start`
        }
      },
      data:{
        responseStatus:200,
        responseJson:response
      }
    });

    await audit(
      prisma,
      req,
      "GAME_STARTED",
      {
        gameId:req.params.gameId,
        playerCount:result.state.players.length,
        gross:result.game.grossAmount.toString()
      }
    );

    publishGameUpdate(req.params.gameId,result.state.stateVersion,"GAME_STARTED");
    return res.json(response);

  }catch(e:any){

    const text=String(e?.message||"");

    if(text==="INSUFFICIENT_FUNDS"){
      return res.status(400).json({
        error:"Insufficient funds"
      });
    }

    const status=
      text==="GAME_NOT_FOUND" ? 404 :
      text==="ONLY_CREATOR_CAN_START" ? 403 :
      text==="GAME_NOT_WAITING" ? 409 :
      text==="2_TO_4_PLAYERS_REQUIRED" ? 409 :
      text==="ENTRY_FEE_MISMATCH" ? 409 :
      e?.code==="P2002" ? 409 :
      text.includes("40001") ? 409 :
      500;

    if(status===500){
      console.error("GAME START ERROR:",{
        name:e?.name,
        code:e?.code,
        message:e?.message,
        gameId:req.params.gameId,
        userId:req.userId,
        requestId:req.get("X-Request-ID")
      });
    }

    return res.status(status).json({
      error:
        status===500
          ? "Internal server error"
          : text
    });
  }
});












app.get("/api/games/:gameId/lobby",auth,async(req:any,res)=>{
  try{
    const game=await prisma.game.findUnique({
      where:{id:req.params.gameId},
      include:{participants:{orderBy:{createdAt:"asc"},select:{userId:true}}}
    });
    if(!game) return res.status(404).json({error:"Game not found"});
    const isParticipant=game.participants.some((p:any)=>p.userId===req.userId);
    if(!isParticipant) return res.status(403).json({error:"Forbidden"});
    return res.json({
      gameId:game.id,
      creatorId:game.creatorId,
      status:game.status,
      participants:game.participants.map((p:any)=>({userId:p.userId}))
    });
  }catch(e:any){
    console.error("GAME LOBBY ERROR:",e?.message);
    return res.status(500).json({error:"Internal server error"});
  }
});


app.get("/api/games/:gameId/events",auth,async(req:any,res:any)=>{
  let unsubscribe:(()=>void)|undefined;
  let heartbeat:ReturnType<typeof setInterval>|undefined;

  try{
    const game=await prisma.game.findUnique({
      where:{id:req.params.gameId},
      include:{participants:{select:{userId:true}}}
    });

    if(!game) return res.status(404).json({error:"Game not found"});
    if(!game.participants.some((p:any)=>p.userId===req.userId)){
      return res.status(403).json({error:"Forbidden"});
    }

    try{
      unsubscribe=subscribeGameUpdates(req.params.gameId,req.userId,(event)=>{
        if(res.writableEnded || res.destroyed) return;
        res.write(
          "id: "+event.id+"\nevent: game-update\ndata: "+
          JSON.stringify(event)+"\n\n"
        );
      });
    }catch(e:any){
      const code=String(e?.message||"");
      if(code==="GAME_STREAM_CAPACITY_REACHED" || code==="USER_STREAM_CAPACITY_REACHED"){
        return res.status(429).json({error:"Too many live game streams"});
      }
      throw e;
    }

    res.status(200);
    res.setHeader("Content-Type","text/event-stream; charset=utf-8");
    res.setHeader("Cache-Control","no-cache, no-transform");
    res.setHeader("Connection","keep-alive");
    res.setHeader("X-Accel-Buffering","no");
    res.setHeader("X-Content-Type-Options","nosniff");
    res.flushHeaders();
    res.write("event: ready\ndata: "+JSON.stringify({
      gameId:req.params.gameId,
      refresh:"GET /api/games/"+req.params.gameId+"/state"
    })+"\n\n");

    heartbeat=setInterval(()=>{
      if(!res.writableEnded && !res.destroyed) res.write(": keep-alive\n\n");
    },25_000);
    if(typeof heartbeat.unref==="function") heartbeat.unref();

    res.on("close",()=>{
      if(heartbeat) clearInterval(heartbeat);
      unsubscribe?.();
    });
  }catch(e:any){
    if(heartbeat) clearInterval(heartbeat);
    unsubscribe?.();
    console.error("GAME EVENT STREAM ERROR",{
      name:e?.name,
      message:e?.message,
      gameId:req.params.gameId,
      userId:req.userId,
      requestId:req.requestId
    });
    if(!res.headersSent) return res.status(500).json({error:"Internal server error"});
    return res.end();
  }
});

app.get("/api/games/:gameId/ai-suggestion",auth,async(req:any,res:any)=>{
  try{
    const game=await prisma.game.findUnique({
      where:{id:req.params.gameId},
      include:{participants:{select:{userId:true}}}
    });

    if(!game) return res.status(404).json({error:"Game not found"});
    if(!game.participants.some((p:any)=>p.userId===req.userId)){
      return res.status(403).json({error:"Forbidden"});
    }
    if(game.status!=="PLAYING"){
      return res.status(409).json({error:"GAME_NOT_PLAYING"});
    }

    const stored=await prisma.authoritativeGameState.findUnique({
      where:{gameId:req.params.gameId},
      select:{state:true,stateVersion:true}
    });
    if(!stored) return res.status(404).json({error:"Game state not found"});

    const state=stored.state as unknown as GameState;
    if(state.players[state.currentPlayerIndex]?.id!==req.userId){
      return res.status(409).json({error:"NOT_YOUR_TURN"});
    }
    if(state.dice===null){
      return res.status(409).json({error:"DICE_NOT_ROLLED"});
    }

    const tokenIndex=chooseAiMove(state,req.userId);
    return res.json({
      gameId:req.params.gameId,
      stateVersion:state.stateVersion,
      tokenIndex,
      available:tokenIndex!==null,
      reason:tokenIndex===null?"NO_LEGAL_MOVE":"HEURISTIC_SUGGESTION",
      strategy:"ludomada-heuristic-v1"
    });
  }catch(e:any){
    console.error("AI SUGGESTION ERROR",{
      name:e?.name,
      message:e?.message,
      gameId:req.params.gameId,
      userId:req.userId,
      requestId:req.requestId
    });
    return res.status(500).json({error:"Internal server error"});
  }
});

app.get("/api/games/:gameId/state",auth,async(req:any,res)=>{
  const totalStart=process.hrtime.bigint();

      const routeStart=process.hrtime.bigint();
try{
    const beforeQueryMs=Number(process.hrtime.bigint()-routeStart)/1e6;

    const queryStart=process.hrtime.bigint();

    const rows:any[]=await prisma.$queryRawUnsafe(`
      SELECT
        g.id AS "gameId",
        g.status::text AS "status",
        g."creatorId" AS "creatorId",
        g."winnerId" AS "winnerId",

        (
          SELECT gp."entryFee"::text
          FROM "GameParticipant" gp
          WHERE gp."gameId" = g.id
          ORDER BY gp."createdAt" ASC
          LIMIT 1
        ) AS "entryFee",

        COALESCE(
          (
            SELECT json_agg(
              json_build_object(
                'userId', gp."userId"
              )
              ORDER BY gp."createdAt" ASC
            )
            FROM "GameParticipant" gp
            WHERE gp."gameId" = g.id
          ),
          '[]'::json
        ) AS "participants",

        ags.state AS "state"

      FROM "Game" g

      LEFT JOIN "AuthoritativeGameState" ags
        ON ags."gameId" = g.id

      WHERE g.id = $1

        AND EXISTS (
          SELECT 1
          FROM "GameParticipant" gp_auth
          WHERE gp_auth."gameId" = g.id
            AND gp_auth."userId" = $2
        )

      LIMIT 1
    `,req.params.gameId,req.userId);

    const queryMs=Number(
      process.hrtime.bigint()-queryStart
    )/1e6;

    if(!rows.length){
      const exists:any[]=await prisma.$queryRawUnsafe(`
        SELECT 1
        FROM "Game"
        WHERE "id" = $1
        LIMIT 1
      `,req.params.gameId);

      if(!exists.length){
        return res.status(404).json({error:"Game not found"});
      }

      return res.status(403).json({error:"Forbidden"});
    }

    const row=rows[0];

    if(row.state===null || row.state===undefined){
      return res.status(404).json({error:"Game state not found"});
    }

    const beforeResponseMs=Number(
      process.hrtime.bigint()-routeStart
    )/1e6;

    const totalMs=Number(
      process.hrtime.bigint()-totalStart
    )/1e6;

    if (PERF_DIAGNOSTICS_ENABLED) console.log("STATE_DETAILED_TIMING",{
      routeBeforeQueryMs:Number(beforeQueryMs.toFixed(2)),
      queryMs:Number(queryMs.toFixed(2)),
      beforeResponseMs:Number(beforeResponseMs.toFixed(2)),
      totalMs:Number(totalMs.toFixed(2)),
      participantsCount:Array.isArray(row.participants)
        ? row.participants.length
        : 0
    });return res.json({
      gameId:row.gameId,
      status:row.status,
      entryFee:row.entryFee ?? "0",
      creatorId:row.creatorId,
      winnerId:row.winnerId,
      participants:row.participants ?? [],
      ...(row.state && typeof row.state === "object" ? row.state : {}),
      stateVersion:row.state?.stateVersion,
      players:row.state?.players ?? [],
      state:row.state
    });

  }catch(e){
    console.error("STATE_TRUE_SINGLE_QUERY_ERROR",e);
    return res.status(500).json({
      error:"Internal server error"
    });
  }
});
app.get("/api/games/:gameId/legal-moves",auth,async(req:any,res)=>{
  const totalStart=process.hrtime.bigint();

  try{
    const rows:any[]=await prisma.$queryRawUnsafe(`
      SELECT
        g.id AS "gameId",
        g.status::text AS "status",
        ags.state::text AS "state"
      FROM "Game" g
      LEFT JOIN "AuthoritativeGameState" ags
        ON ags."gameId" = g.id
      WHERE g.id = $1
        AND EXISTS (
          SELECT 1
          FROM "GameParticipant" gp_auth
          WHERE gp_auth."gameId" = g.id
            AND gp_auth."userId" = $2
        )
      LIMIT 1
    `,req.params.gameId,req.userId);

    if(!rows.length){
      const exists:any[]=await prisma.$queryRawUnsafe(`
        SELECT 1
        FROM "Game"
        WHERE "id" = $1
        LIMIT 1
      `,req.params.gameId);

      if(!exists.length){
        return res.status(404).json({error:"Game not found"});
      }

      return res.status(403).json({error:"Forbidden"});
    }

    const row=rows[0];

    if(row.state===null || row.state===undefined){
      return res.status(404).json({error:"Game state not found"});
    }

    const state=JSON.parse(row.state) as GameState;
    const moves=legalMoves(state,req.userId);

    const totalMs=Number(
      process.hrtime.bigint()-totalStart
    )/1e6;

    return res.json({
      gameId:row.gameId,
      status:row.status,
      moves,
      stateVersion:state.stateVersion,
      dice:state.dice,
      currentPlayerIndex:state.currentPlayerIndex
    });
  }catch(e:any){
    console.error("LEGAL_MOVES_ERROR",{
      name:e?.name,
      message:e?.message,
      gameId:req.params.gameId,
      userId:req.userId
    });

    return res.status(500).json({
      error:"Internal server error"
    });
  }
});

app.post("/api/games/:gameId/roll",auth,async(req:any,res)=>{
  try{
    const b=z.object({
      expectedVersion:z.number().int().nonnegative()
    }).parse(req.body);

    const idempotencyKey=req.header("Idempotency-Key");

    if(!idempotencyKey){
      return res.status(400).json({
        error:"Idempotency-Key header is required"
      });
    }
    const out=await authoritativeCommands.roll(
      req.params.gameId,
      req.userId,
      b.expectedVersion,
      idempotencyKey
    );

    await audit(prisma,req,"GAME_ROLL",{
      gameId:req.params.gameId,
      dice:out.result.dice,
      stateVersion:out.result.stateVersion
    });

    publishGameUpdate(req.params.gameId,out.result.stateVersion,"DICE_ROLLED");
    res.json({
      gameId:req.params.gameId,
      dice:out.result.dice,
      stateVersion:out.result.stateVersion,
      autoSkipped:out.result.autoSkipped,
      currentPlayerIndex:out.result.currentPlayerIndex
    });
  }catch(e:any){
    const status=
      e.message==="NOT_YOUR_TURN" ||
      e.message==="DICE_ALREADY_ROLLED" ||
      e.message==="GAME_FINISHED" ||
      e.message==="GAME_NOT_PLAYING" ||
      e.message==="NOT_GAME_PARTICIPANT" ||
      e.message==="STATE_VERSION_CONFLICT" ||
      e.message==="IDEMPOTENCY_KEY_CONFLICT"
        ? 409
        : 400;

    res.status(status).json({
      error:e.message
    });
  }
});

// Move validation is intentionally conservative in V4: the server accepts only a
// pawn index after a server-generated dice roll. Full board rules belong here, not in Android.
app.post("/api/games/:gameId/move",auth,async(req:any,res)=>{
  let b:{
    tokenIndex:number;
    expectedVersion:number;
  } | undefined;

  let idempotencyKey:string | undefined;

  try{
    b=z.object({
      tokenIndex:z.number().int().min(0).max(3),
      expectedVersion:z.number().int().nonnegative()
    }).parse(req.body);

    idempotencyKey=req.header("Idempotency-Key") ?? undefined;

    if(!idempotencyKey){
      return res.status(400).json({
        error:"Idempotency-Key header is required"
      });
    }
    const out=await authoritativeCommands.move(
      req.params.gameId,
      req.userId,
      b!.tokenIndex,
      b!.expectedVersion,
      idempotencyKey
    );

    await audit(prisma,req,"GAME_MOVE",{
      gameId:req.params.gameId,
      tokenIndex:b!.tokenIndex,
      stateVersion:out.result.stateVersion
    });

    publishGameUpdate(req.params.gameId,out.result.stateVersion,out.result.finished ? "GAME_FINISHED" : "TOKEN_MOVED");
    res.json({
      gameId:req.params.gameId,
      ...out.result
    });
  }catch(e:any){
    console.error("MOVE ERROR:", {
      name:e?.name,
      message:e?.message,
      stack:e?.stack,
      gameId:req.params.gameId,
      userId:req.userId,
      tokenIndex:b?.tokenIndex,
      expectedVersion:b?.expectedVersion,
      idempotencyKey
    });

    const status=
      e.message==="NOT_YOUR_TURN" ||
      e.message==="DICE_NOT_ROLLED" ||
      e.message==="INVALID_TOKEN_INDEX" ||
      e.message==="ILLEGAL_MOVE" ||
      e.message==="STATE_VERSION_CONFLICT" ||
      e.message==="GAME_FINISHED" ||
      e.message==="NOT_GAME_PARTICIPANT" ||
      e.message==="IDEMPOTENCY_KEY_CONFLICT"
        ? 409
        : 400;

    res.status(status).json({
      error:e.message
    });
  }
});

// Only GAME_SERVER can settle a game. In production this role should be held by
// an internal service identity, not an Android token.
app.post("/api/games/:gameId/finish",auth,requireRole("GAME_SERVER"),async(req:any,res)=>{
  return res.status(410).json({
    error:"GAME_FINISH_ENDPOINT_DEPRECATED",
    message:"Game completion and prize payout are handled automatically by the authoritative game engine."
  });
});

app.get("/api/games/:gameId/ledger",auth,async(req:any,res)=>{
  const g=await prisma.game.findUnique({where:{id:req.params.gameId},include:{participants:true}});
  if(!g)return res.status(404).json({error:"Game not found"});
  if(!g.participants.some(p=>p.userId===req.userId) && req.auth.role!=="ADMIN" && req.auth.role!=="GAME_SERVER")return res.status(403).json({error:"Forbidden"});
  const entries=await prisma.ledgerEntry.findMany({where:{gameId:g.id},orderBy:{createdAt:"asc"}});
  res.json({game_id:g.id,gross_amount:g.grossAmount.toString(),commission_10_percent:g.commission.toString(),prize_pool:g.prizePool.toString(),winner_payout:g.winnerPayout.toString(),admin_revenue:g.adminRevenue.toString(),entries:entries.map(e=>({...e,amount:e.amount.toString()}))});
});

app.use((err:any,req:any,res:any,_next:any)=>{
  console.error("GLOBAL ERROR:", {
    name: err?.name,
    message: err?.message,
    stack: err?.stack,
    method: req.method,
    url: req.originalUrl,
    requestId: req.requestId
  });

  res.status(500).json({
    error:"Internal server error"
  });
});
async function startServer() {
  const server = app.listen(port, () => {
    console.log(`Ludo Mada V4 API listening on :${port}`);
  });
  return server;
}

const serverPromise = startServer();

let shuttingDown = false;

async function shutdown(signal: string) {
  if (shuttingDown) {
    return;
  }

  shuttingDown = true;

  console.log(`Received ${signal}, shutting down gracefully`);

  const server = await serverPromise;

  server.close(async () => {
    try {
      await prisma.$disconnect();
      await pool.end();
      process.exit(0);
    } catch (error) {
      console.error("SHUTDOWN ERROR:", error);
      process.exit(1);
    }
  });

  setTimeout(() => {
    console.error("Forced shutdown timeout reached");
    process.exit(1);
  }, 10_000).unref();
}

process.on("SIGTERM", () => {
  void shutdown("SIGTERM");
});

process.on("SIGINT", () => {
  void shutdown("SIGINT");
});
