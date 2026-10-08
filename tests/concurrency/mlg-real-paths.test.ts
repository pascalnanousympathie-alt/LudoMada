import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import pg from "pg";
import http from "node:http";
import { randomUUID } from "node:crypto";
import { newGame } from "../../src/game/ludoEngine";

const describeDb = process.env.DATABASE_URL ? describe : describe.skip;

jest.setTimeout(60_000);

describeDb("MLG real transaction paths", () => {
  let prisma: PrismaClient;
  let pool: pg.Pool;
  let app: any;
  let server: http.Server;
  const userIds: string[] = [];
  const gameIds: string[] = [];

  async function api(path: string, init: RequestInit = {}) {
    return fetch("http://127.0.0.1:" + (server.address() as any).port + path, init);
  }

  async function register(label: string) {
    const username = "real_mlg_" + label + "_" + randomUUID().replace(/-/g, "").slice(0, 10);
    const res = await api("/api/auth/register", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ username, password: "ConcurrencyTestPassword123!" })
    });
    expect(res.status).toBe(201);
    const body = await res.json() as any;
    userIds.push(body.user.id);
    return { id: body.user.id, token: body.accessToken };
  }

  beforeAll(async () => {
    pool = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 12, min: 0 });
    prisma = new PrismaClient({ adapter: new PrismaPg(pool) });
    await prisma.$queryRaw`SELECT 1`;
    ({ app } = require("../../src/server"));
    server = app.listen(0);
    await new Promise<void>(resolve => server.once("listening", () => resolve()));
  });

  afterAll(async () => {
    if (gameIds.length) await prisma.game.deleteMany({ where: { id: { in: gameIds } } });
    if (userIds.length) await prisma.user.deleteMany({ where: { id: { in: userIds } } });
    if (server) await new Promise<void>(resolve => server.close(() => resolve()));
    if (prisma) await prisma.$disconnect();
    if (pool) await pool.end();
  });

  test("authoritative online winner path credits exactly +20 MLG once", async () => {
    const creator = await register("creator");
    const winner = await register("winner");
    const { AuthoritativeCommandService } = require("../../src/game/authoritativeCommandService");
    const service = new AuthoritativeCommandService(prisma);
    const gameId = randomUUID();
    const game = await prisma.game.create({
      data: {
        id: gameId,
        creatorId: creator.id,
        status: "PLAYING",
        grossAmount: 0n,
        commission: 0n,
        prizePool: 0n,
        participants: { create: [
          { userId: creator.id, entryFee: 0n },
          { userId: winner.id, entryFee: 0n }
        ] }
      }
    });
    gameIds.push(game.id);

    const state = newGame([creator.id, winner.id]);
    state.currentPlayerIndex = 1;
    state.dice = 1;
    state.players[1].tokens = [{ pos: 56 }, { pos: 57 }, { pos: 57 }, { pos: 57 }];
    await prisma.authoritativeGameState.create({
      data: { gameId, stateVersion: state.stateVersion, state: state as any }
    });

    const results = await Promise.allSettled(Array.from({ length: 8 }, (_, i) =>
      service.move(gameId, winner.id, 0, 0, "winner-conc-" + i + "-" + randomUUID())
    ));
    const success = results.filter(x => x.status === "fulfilled");
    expect(success).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({ where: { userId: winner.id } });
    const ledger = await prisma.mlgLedgerEntry.findMany({ where: { gameId, type: "EARN_VICTORY" } });
    const payout = await prisma.transaction.findMany({ where: { gameId, type: "DEPOSIT", status: "SUCCESS" } });
    const finalGame = await prisma.game.findUnique({ where: { id: gameId } });

    expect(wallet?.mlgBalance).toBe(20n);
    expect(ledger).toHaveLength(1);
    expect(ledger[0].amount).toBe(20n);
    expect(payout).toHaveLength(1);
    expect(finalGame?.status).toBe("FINISHED");
    expect(finalGame?.winnerId).toBe(winner.id);
  });

  test("referral HTTP path: 12 concurrent claims credit inviter exactly +100 MLG once", async () => {
    const inviter = await register("inviter");
    const invitee = await register("invitee");
    const summaryRes = await api("/api/mlg/summary", { headers: { authorization: "Bearer " + inviter.token } });
    expect(summaryRes.status).toBe(200);
    const summary = await summaryRes.json() as any;
    const claimResults = await Promise.all(Array.from({ length: 12 }, (_, i) =>
      api("/api/mlg/referral/claim", {
        method: "POST",
        headers: {
          authorization: "Bearer " + invitee.token,
          "content-type": "application/json",
          "idempotency-key": "ref-real-" + i + "-" + randomUUID()
        },
        body: JSON.stringify({ referralCode: summary.referralCode })
      })
    )));

    expect(claimResults.filter(r => r.status === 200)).toHaveLength(1);
    const wallet = await prisma.wallet.findUnique({ where: { userId: inviter.id } });
    const referral = await prisma.referral.findUnique({ where: { inviteeId: invitee.id } });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { userId: inviter.id, type: "EARN_REFERRAL" }
    });
    expect(wallet?.mlgBalance).toBe(100n);
    expect(referral?.inviterId).toBe(inviter.id);
    expect(ledger).toHaveLength(1);
    expect(ledger[0].amount).toBe(100n);
  });

  test("market HTTP path: 12 concurrent different idempotency keys charge one ownership once", async () => {
    const user = await register("market");
    await prisma.wallet.update({ where: { userId: user.id }, data: { mlgBalance: 1000n } });

    const results = await Promise.all(Array.from({ length: 12 }, (_, i) =>
      api("/api/market/purchase", {
        method: "POST",
        headers: {
          authorization: "Bearer " + user.token,
          "content-type": "application/json",
          "idempotency-key": "market-real-" + i + "-" + randomUUID()
        },
        body: JSON.stringify({ category: "pawn", itemId: "sapphire" })
      })
    )));

    expect(results.filter(r => r.status === 200)).toHaveLength(1);
    const wallet = await prisma.wallet.findUnique({ where: { userId: user.id } });
    const ownership = await prisma.cosmeticOwnership.findMany({ where: { userId: user.id, category: "pawn", itemId: "sapphire" } });
    const ledger = await prisma.mlgLedgerEntry.findMany({ where: { userId: user.id, type: "MARKET_PURCHASE" } });
    expect(wallet?.mlgBalance).toBe(700n);
    expect(ownership).toHaveLength(1);
    expect(ledger).toHaveLength(1);
    expect(ledger[0].amount).toBe(-300n);
  });

  test("swap HTTP path: concurrent 10,000 MLG swaps preserve exact 1:1", async () => {
    const user = await register("swap");
    await prisma.wallet.update({ where: { userId: user.id }, data: { mlgBalance: 50_000n } });

    const results = await Promise.all(Array.from({ length: 7 }, (_, i) =>
      api("/api/mlg/swap-to-mga", {
        method: "POST",
        headers: {
          authorization: "Bearer " + user.token,
          "content-type": "application/json",
          "idempotency-key": "swap-real-" + i + "-" + randomUUID()
        },
        body: JSON.stringify({ amountMLG: 10_000 })
      })
    )));

    expect(results.filter(r => r.status === 200)).toHaveLength(5);
    const wallet = await prisma.wallet.findUnique({ where: { userId: user.id } });
    const ledger = await prisma.mlgLedgerEntry.findMany({ where: { userId: user.id, type: "SWAP_TO_MGA" } });
    expect(wallet?.mlgBalance).toBe(0n);
    expect(wallet?.balance).toBe(50_000n);
    expect(ledger).toHaveLength(5);
    expect(ledger.reduce((sum, row) => sum + row.amount, 0n)).toBe(-50_000n);
  });

  test("swap HTTP idempotency: same key replay cannot credit MGA twice", async () => {
    const user = await register("swapidem");
    await prisma.wallet.update({ where: { userId: user.id }, data: { mlgBalance: 20_000n } });
    const key = "swap-same-" + randomUUID();

    const results = await Promise.all(Array.from({ length: 6 }, () =>
      api("/api/mlg/swap-to-mga", {
        method: "POST",
        headers: {
          authorization: "Bearer " + user.token,
          "content-type": "application/json",
          "idempotency-key": key
        },
        body: JSON.stringify({ amountMLG: 10_000 })
      })
    )));

    expect(results.every(r => r.status === 200)).toBe(true);
    const wallet = await prisma.wallet.findUnique({ where: { userId: user.id } });
    const ledger = await prisma.mlgLedgerEntry.findMany({ where: { userId: user.id, type: "SWAP_TO_MGA" } });
    expect(wallet?.mlgBalance).toBe(10_000n);
    expect(wallet?.balance).toBe(10_000n);
    expect(ledger).toHaveLength(1);
  });
});
