const enabled = Boolean(process.env.DATABASE_URL);

jest.setTimeout(60_000);

const describeDb = enabled ? describe : describe.skip;

describeDb("MLG HTTP route concurrency", () => {
  let app: any;
  let prisma: any;
  let pool: any;
  let httpServer: any;
  let baseUrl = "";
  const createdUserIds: string[] = [];

  beforeAll(async () => {
    process.env.NODE_ENV = "test";
    process.env.JWT_SECRET = process.env.JWT_SECRET || "local-test-jwt-secret-012345678901234567890123";
    process.env.CORS_ORIGINS = "";

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
    const username = `mlg_${label}_${Date.now().toString().slice(-8)}_${Math.random().toString(36).slice(2, 6)}`;
    const { response, body } = await request("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username,
        password: "TestPassword!12345"
      })
    });
    if (response.status !== 201) {
      throw new Error(`REGISTER_FAILED status=${response.status} body=${JSON.stringify(body)}`);
    }
    createdUserIds.push(body.user.id);
    return { id: body.user.id as string, token: body.accessToken as string };
  }

  function authHeaders(token: string, key?: string) {
    return {
      "Content-Type": "application/json",
      "Authorization": "Bearer " + token,
      ...(key ? { "Idempotency-Key": key } : {})
    };
  }

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
});
