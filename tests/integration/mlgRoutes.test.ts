const enabled = Boolean(process.env.DATABASE_URL);

jest.setTimeout(60_000);

import { randomUUID } from "node:crypto";
import { issueAccessToken } from "../../src/security";
import { newGame } from "../../src/game/ludoEngine";
import { AuthoritativeCommandService } from "../../src/game/authoritativeCommandService";

const describeDb = enabled ? describe : describe.skip;

describeDb("MLG HTTP route concurrency", () => {
  let app: any;
  let prisma: any;
  let pool: any;
  let httpServer: any;
  let baseUrl = "";
  const createdUserIds: string[] = [];
  const createdGameIds: string[] = [];

  beforeAll(async () => {
    process.env.NODE_ENV = "test";
    process.env.JWT_SECRET = process.env.JWT_SECRET || "local-test-jwt-secret-012345678901234567890123";
    process.env.CORS_ORIGINS = "";
    process.env.PG_POOL_MAX = "20";

    const mod = await import("../../src/server");
    app = mod.app;
    prisma = mod.prisma;
    pool = mod.pool;

    httpServer = app.listen(0);
    await new Promise<void>((resolve) => httpServer.once("listening", resolve));
    const address = httpServer.address();
    if (!address || typeof address === "string") throw new Error("TEST_SERVER_ADDRESS_UNAVAILABLE");
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterAll(async () => {
    if (createdGameIds.length) {
      await prisma.game.deleteMany({ where: { id: { in: createdGameIds } } });
    }
    if (createdUserIds.length) {
      await prisma.user.deleteMany({ where: { id: { in: createdUserIds } } });
    }
    if (httpServer) await new Promise<void>((resolve) => httpServer.close(() => resolve()));
    await prisma.$disconnect();
    await pool.end();
  });

  async function request(path: string, init?: RequestInit) {
    const response = await fetch(baseUrl + path, init);
    const body = await response.json().catch(() => ({}));
    return { response, body };
  }

  async function register(label: string) {
    const id = randomUUID();
    const username = `mlg_http_${label}_${id.replace(/-/g, "").slice(0, 12)}`;
    await prisma.user.create({
      data: {
        id,
        username,
        passwordHash: "test-only",
        wallet: { create: {} }
      }
    });
    createdUserIds.push(id);
    return { id, token: issueAccessToken(id, "PLAYER", process.env.JWT_SECRET!) };
  }

  function authHeaders(token: string, key?: string) {
    return {
      "Content-Type": "application/json",
      "Authorization": "Bearer " + token,
      ...(key ? { "Idempotency-Key": key } : {})
    };
  }

  test("authoritative online winner concurrency: exactly one +20 MLG reward", async () => {
    const creator = await register("winner-creator");
    const winner = await register("winner");
    const gameId = randomUUID();

    await prisma.game.create({
      data: {
        id: gameId,
        creatorId: creator.id,
        status: "PLAYING",
        grossAmount: 0n,
        commission: 0n,
        prizePool: 0n,
        participants: {
          create: [
            { userId: creator.id, entryFee: 0n },
            { userId: winner.id, entryFee: 0n }
          ]
        }
      }
    });
    createdGameIds.push(gameId);

    const state = newGame([creator.id, winner.id]);
    state.currentPlayerIndex = 1;
    state.dice = 1;
    state.players[1].tokens = [
      { pos: 56 },
      { pos: 57 },
      { pos: 57 },
      { pos: 57 }
    ];

    await prisma.authoritativeGameState.create({
      data: {
        gameId,
        stateVersion: state.stateVersion,
        state: state as any
      }
    });

    const service = new AuthoritativeCommandService(prisma);
    const results = await Promise.allSettled(
      Array.from({ length: 8 }, (_, i) =>
        service.move(
          gameId,
          winner.id,
          0,
          0,
          "winner-real-" + i + "-" + randomUUID()
        )
      )
    );

    expect(results.filter(x => x.status === "fulfilled")).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({
      where: { userId: winner.id },
      select: { mlgBalance: true }
    });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { gameId, type: "EARN_VICTORY" }
    });
    const payouts = await prisma.transaction.findMany({
      where: { gameId, type: "DEPOSIT", status: "SUCCESS" }
    });
    const finalGame = await prisma.game.findUnique({
      where: { id: gameId },
      select: { status: true, winnerId: true }
    });

    expect(wallet?.mlgBalance).toBe(20n);
    expect(ledger).toHaveLength(1);
    expect(ledger[0].amount).toBe(20n);
    expect(payouts).toHaveLength(1);
    expect(finalGame?.status).toBe("FINISHED");
    expect(finalGame?.winnerId).toBe(winner.id);
  });
  test("referral concurrent claim: exactly one +100 MLG award", async () => {
    const inviter = await register("inviter");
    const invitee = await register("invitee");

    const summary = await request("/api/mlg/summary", {
      headers: authHeaders(inviter.token)
    });
    expect(summary.response.status).toBe(200);
    const referralCode = summary.body.referralCode;
    expect(typeof referralCode).toBe("string");

    const results = await Promise.all(
      Array.from({ length: 12 }, (_, i) =>
        request("/api/mlg/referral/claim", {
          method: "POST",
          headers: authHeaders(invitee.token, "ref-http-" + i),
          body: JSON.stringify({ referralCode })
        })
      )
    );

    const rewards = results.filter(x => x.response.status === 200 && x.body.rewardMLG === "100");
    expect(rewards).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({
      where: { userId: inviter.id },
      select: { mlgBalance: true }
    });
    const referral = await prisma.referral.findUnique({
      where: { inviteeId: invitee.id }
    });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { userId: inviter.id, type: "EARN_REFERRAL" }
    });

    expect(wallet?.mlgBalance).toBe(100n);
    expect(referral?.inviterId).toBe(inviter.id);
    expect(ledger).toHaveLength(1);
  });

  test("market purchase concurrent: exactly one charge and one ownership", async () => {
    const user = await register("market");

    await prisma.wallet.update({
      where: { userId: user.id },
      data: { mlgBalance: 1_000n }
    });

    const results = await Promise.all(
      Array.from({ length: 12 }, (_, i) =>
        request("/api/market/purchase", {
          method: "POST",
          headers: authHeaders(user.token, "market-http-" + i),
          body: JSON.stringify({
            category: "pawn",
            itemId: "sapphire"
          })
        })
      )
    );

    const charged = results.filter(x => x.response.status === 200 && x.body.chargedMLG === "300");
    expect(charged).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({
      where: { userId: user.id },
      select: { mlgBalance: true }
    });
    const ownership = await prisma.cosmeticOwnership.findMany({
      where: { userId: user.id, category: "pawn", itemId: "sapphire" }
    });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { userId: user.id, type: "MARKET_PURCHASE" }
    });

    expect(wallet?.mlgBalance).toBe(700n);
    expect(ownership).toHaveLength(1);
    expect(ledger).toHaveLength(1);
    expect(ledger[0].amount).toBe(-300n);
  });

  test("swap concurrent: five 10,000 MLG swaps settle exactly 1:1", async () => {
    const user = await register("swap");

    await prisma.wallet.update({
      where: { userId: user.id },
      data: { mlgBalance: 50_000n, balance: 0n }
    });

    const results = await Promise.all(
      Array.from({ length: 7 }, (_, i) =>
        request("/api/mlg/swap-to-mga", {
          method: "POST",
          headers: authHeaders(user.token, "swap-http-" + i),
          body: JSON.stringify({ amountMLG: 10_000 })
        })
      )
    );

    const succeeded = results.filter(x => x.response.status === 200);
    expect(succeeded).toHaveLength(5);

    const wallet = await prisma.wallet.findUnique({
      where: { userId: user.id },
      select: { mlgBalance: true, balance: true }
    });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { userId: user.id, type: "SWAP_TO_MGA" }
    });

    expect(wallet?.mlgBalance).toBe(0n);
    expect(wallet?.balance).toBe(50_000n);
    expect(ledger).toHaveLength(5);
    expect(ledger.reduce((sum: bigint, row: any) => sum + row.amount, 0n)).toBe(-50_000n);
  });

  test("swap idempotency conflict rejects same key with a different amount", async () => {
    const user = await register("swap-conflict");

    await prisma.wallet.update({
      where: { userId: user.id },
      data: { mlgBalance: 20_000n, balance: 0n }
    });

    const key = "swap-conflict-key";
    const first = await request("/api/mlg/swap-to-mga", {
      method: "POST",
      headers: authHeaders(user.token, key),
      body: JSON.stringify({ amountMLG: 10_000 })
    });
    expect(first.response.status).toBe(200);

    const second = await request("/api/mlg/swap-to-mga", {
      method: "POST",
      headers: authHeaders(user.token, key),
      body: JSON.stringify({ amountMLG: 20_000 })
    });
    expect(second.response.status).toBe(409);
    expect(second.body.error).toBe("IDEMPOTENCY_KEY_CONFLICT");
  });
  test("weekly leaderboard ranks real victory-ledger entries and is read-only", async () => {
    const top = await register("leader-top");
    const runnerUp = await register("leader-runner");
    const outsideWindow = await register("leader-old");
    const topUser = await prisma.user.findUnique({ where: { id: top.id }, select: { username: true } });
    const runnerUser = await prisma.user.findUnique({ where: { id: runnerUp.id }, select: { username: true } });
    const oldUser = await prisma.user.findUnique({ where: { id: outsideWindow.id }, select: { username: true } });
    const now = Date.now();
    const day = 24 * 60 * 60 * 1000;
    const createVictory = (userId: string, daysAgo: number) =>
      prisma.mlgLedgerEntry.create({
        data: {
          userId,
          type: "EARN_VICTORY",
          amount: 20n,
          idempotencyKey: "leaderboard-test-" + randomUUID(),
          createdAt: new Date(now - daysAgo * day)
        }
      });

    await Promise.all([
      createVictory(top.id, 1),
      createVictory(top.id, 3),
      createVictory(top.id, 9),
      createVictory(runnerUp.id, 2),
      createVictory(outsideWindow.id, 8),
      createVictory(outsideWindow.id, 10)
    ]);

    const unauthenticated = await request("/api/mlg/leaderboard?period=week");
    expect(unauthenticated.response.status).toBe(401);

    const response = await request("/api/mlg/leaderboard?period=week", {
      headers: authHeaders(top.token)
    });
    expect(response.response.status).toBe(200);
    expect(response.body.period).toBe("week");
    expect(response.body.windowDays).toBe(7);
    // Other tests may add legitimate victories to this isolated CI database. Verify
    // this scenario's players and ordering without assuming the entire global leaderboard
    // contains only the two rows created in this test.
    expect(response.body.items[0]).toMatchObject({
      rank: 1,
      username: topUser.username,
      wins: 2,
      earnedMLG: "40"
    });
    const runnerRow = response.body.items.find((row: any) => row.username === runnerUser.username);
    expect(runnerRow).toMatchObject({
      username: runnerUser.username,
      wins: 1,
      earnedMLG: "20"
    });
    expect(runnerRow.rank).toBeGreaterThan(1);
    expect(response.body.items.some((row: any) => row.username === oldUser.username)).toBe(false);

    const wallet = await prisma.wallet.findUnique({
      where: { userId: top.id },
      select: { mlgBalance: true, balance: true }
    });
    expect(wallet?.mlgBalance).toBe(0n);
    expect(wallet?.balance).toBe(0n);

    const unsupportedPeriod = await request("/api/mlg/leaderboard?period=all", {
      headers: authHeaders(top.token)
    });
    expect(unsupportedPeriod.response.status).toBe(400);
    expect(unsupportedPeriod.body.error).toBe("Unsupported leaderboard period");
  });


});
