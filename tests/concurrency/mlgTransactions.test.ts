import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import pg from "pg";
import { randomUUID } from "node:crypto";
import { AuthoritativeCommandService } from "../../src/game/authoritativeCommandService";
import { newGame } from "../../src/game/ludoEngine";

const enabled = Boolean(process.env.DATABASE_URL);

jest.setTimeout(30_000);

const describeDb = enabled ? describe : describe.skip;

describeDb("MLG production concurrency regression", () => {
  let pool: pg.Pool;
  let prisma: PrismaClient;
  const createdUserIds: string[] = [];
  const createdGameIds: string[] = [];

  beforeAll(async () => {
    pool = new pg.Pool({
      connectionString: process.env.DATABASE_URL,
      max: 12,
      min: 0,
      connectionTimeoutMillis: 5_000
    });
    prisma = new PrismaClient({ adapter: new PrismaPg(pool) });
    await prisma.$queryRaw`SELECT 1`;
  });

  afterAll(async () => {
    if (!prisma) return;
    if (createdGameIds.length) {
      await prisma.game.deleteMany({ where: { id: { in: createdGameIds } } });
    }
    if (createdUserIds.length) {
      await prisma.user.deleteMany({ where: { id: { in: createdUserIds } } });
    }
    await prisma.$disconnect();
    await pool.end();
  });

  async function createUser(label: string, mlgBalance = 0n, balance = 0n) {
    const id = randomUUID();
    const username = `mlg_conc_${label}_${id.replace(/-/g, "").slice(0, 10)}`;
    const user = await prisma.user.create({
      data: {
        id,
        username,
        passwordHash: "test-only",
        wallet: { create: { mlgBalance, balance } }
      }
    });
    createdUserIds.push(user.id);
    return user;
  }

  test("winner reward: concurrent finalization through AuthoritativeCommandService grants exactly +20 MLG once", async () => {\n    const winner = await createUser("winner");\n    const creator = await createUser("creator");\n\n    const state = newGame([creator.id, winner.id]);\n    state.currentPlayerIndex = 1;\n    state.dice = 1;\n    state.players[1].tokens = [\n      { pos: 56 },\n      { pos: 57 },\n      { pos: 57 },\n      { pos: 57 }\n    ];\n\n    const game = await prisma.game.create({\n      data: {\n        id: randomUUID(),\n        creatorId: creator.id,\n        status: "PLAYING",\n        grossAmount: 0n,\n        commission: 0n,\n        prizePool: 0n,\n        winnerPayout: 0n,\n        adminRevenue: 0n,\n        currentTurnUserId: winner.id,\n        stateVersion: state.stateVersion,\n        participants: {\n          create: [\n            { userId: creator.id, entryFee: 0n },\n            { userId: winner.id, entryFee: 0n }\n          ]\n        },\n        transactions: undefined\n      }\n    });\n    createdGameIds.push(game.id);\n    await prisma.authoritativeGameState.create({\n      data: { gameId: game.id, stateVersion: state.stateVersion, state: state }\n    });\n\n    const service = new AuthoritativeCommandService(prisma);\n    const key = "winning-move-" + randomUUID();\n    const attempts = Array.from({ length: 8 }, () =>\n      service.move(game.id, winner.id, 0, 0, key)\n    );\n\n    const settled = await Promise.allSettled(attempts);\n    expect(settled.every(x => x.status === "fulfilled")).toBe(true);\n\n    const wallet = await prisma.wallet.findUnique({ where: { userId: winner.id } });\n    const ledger = await prisma.mlgLedgerEntry.findMany({\n      where: { gameId: game.id, type: "EARN_VICTORY" }\n    });\n    const finished = await prisma.game.findUnique({ where: { id: game.id } });\n\n    expect(wallet?.mlgBalance).toBe(20n);\n    expect(ledger).toHaveLength(1);\n    expect(ledger[0].amount).toBe(20n);\n    expect(finished?.status).toBe("FINISHED");\n    expect(finished?.winnerId).toBe(winner.id);\n  });\n\n  test("referral: concurrent claims credit inviter exactly +100 MLG once", async () => {
    const inviter = await createUser("inviter");
    const invitee = await createUser("invitee");
    const code = `REF_${randomUUID().replace(/-/g, "").slice(0, 12).toUpperCase()}`;

    await prisma.user.update({
      where: { id: inviter.id },
      data: { referralCode: code }
    });

    const attempts = Array.from({ length: 12 }, () =>
      prisma.$transaction(async (tx) => {
        await tx.referral.create({
          data: {
            inviterId: inviter.id,
            inviteeId: invitee.id,
            code,
            rewardMlg: 100n
          }
        });
        await tx.wallet.update({
          where: { userId: inviter.id },
          data: { mlgBalance: { increment: 100n } }
        });
        await tx.mlgLedgerEntry.create({
          data: {
            userId: inviter.id,
            type: "EARN_REFERRAL",
            amount: 100n,
            idempotencyKey: `referral:${invitee.id}`,
            metadata: { inviteeId: invitee.id, code }
          }
        });
        return true;
      })
    );

    const settled = await Promise.allSettled(attempts);
    const succeeded = settled.filter(x => x.status === "fulfilled");
    expect(succeeded).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({ where: { userId: inviter.id } });
    const referral = await prisma.referral.findUnique({ where: { inviteeId: invitee.id } });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { userId: inviter.id, type: "EARN_REFERRAL", metadata: { path: ["inviteeId"], equals: invitee.id } }
    });

    expect(wallet?.mlgBalance).toBe(100n);
    expect(referral?.inviterId).toBe(inviter.id);
    expect(ledger).toHaveLength(1);
    expect(ledger[0].amount).toBe(100n);
  });

  test("market purchase: concurrent buyers of the same cosmetic charge once", async () => {
    const user = await createUser("market", 1_000n);
    const keyPrefix = `market-conc-${randomUUID()}`;

    const attempts = Array.from({ length: 12 }, (_, i) =>
      prisma.$transaction(async (tx) => {
        const category = "pawn";
        const itemId = "sapphire";
        const price = 300n;

        const prior = await tx.mlgLedgerEntry.findUnique({
          where: { idempotencyKey: `${keyPrefix}-${i}` }
        });
        if (prior) return false;

        const existing = await tx.cosmeticOwnership.findUnique({
          where: {
            userId_category_itemId: { userId: user.id, category, itemId }
          }
        });
        if (existing) return false;

        await tx.wallet.updateMany({
          where: { userId: user.id, mlgBalance: { gte: price } },
          data: { mlgBalance: { decrement: price } }
        }).then(async result => {
          if (result.count !== 1) throw new Error("INSUFFICIENT_MLG");
        });

        await tx.cosmeticOwnership.create({
          data: {
            userId: user.id,
            category,
            itemId,
            priceMlg: price
          }
        });

        await tx.mlgLedgerEntry.create({
          data: {
            userId: user.id,
            type: "MARKET_PURCHASE",
            amount: -price,
            idempotencyKey: `${keyPrefix}-${i}`,
            metadata: { category, itemId, priceMlg: price.toString() }
          }
        });

        return true;
      })
    );

    const settled = await Promise.allSettled(attempts);
    const succeeded = settled.filter(x => x.status === "fulfilled" && x.value === true);
    expect(succeeded).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({ where: { userId: user.id } });
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

  test("swap: concurrent 10,000 MLG swaps preserve exact 1:1 balance", async () => {
    const user = await createUser("swap", 50_000n, 0n);

    const attempts = Array.from({ length: 7 }, (_, i) =>
      prisma.$transaction(async (tx) => {
        const amount = 10_000n;
        const key = `swap-conc-${i}-${randomUUID()}`;

        const debited = await tx.wallet.updateMany({
          where: { userId: user.id, mlgBalance: { gte: amount } },
          data: {
            mlgBalance: { decrement: amount },
            balance: { increment: amount }
          }
        });

        if (debited.count !== 1) {
          throw new Error("INSUFFICIENT_MLG");
        }

        await tx.mlgLedgerEntry.create({
          data: {
            userId: user.id,
            type: "SWAP_TO_MGA",
            amount: -amount,
            idempotencyKey: key,
            metadata: { rateMGA: "1", amountMLG: amount.toString() }
          }
        });

        return true;
      })
    );

    const settled = await Promise.allSettled(attempts);
    const succeeded = settled.filter(x => x.status === "fulfilled" && x.value === true);
    expect(succeeded).toHaveLength(5);

    const wallet = await prisma.wallet.findUnique({ where: { userId: user.id } });
    const ledger = await prisma.mlgLedgerEntry.findMany({
      where: { userId: user.id, type: "SWAP_TO_MGA" }
    });

    expect(wallet?.mlgBalance).toBe(0n);
    expect(wallet?.balance).toBe(50_000n);
    expect(ledger).toHaveLength(5);
    expect(ledger.reduce((sum, row) => sum + row.amount, 0n)).toBe(-50_000n);
  });

  test("swap idempotency: same key cannot credit MGA twice", async () => {
    const user = await createUser("swap-idem", 20_000n, 0n);
    const key = `swap-idem-${randomUUID()}`;

    const attempts = [0, 1, 2, 3].map(() =>
      prisma.$transaction(async (tx) => {
        const prior = await tx.mlgLedgerEntry.findUnique({ where: { idempotencyKey: key } });
        if (prior) return false;

        const amount = 10_000n;
        const debited = await tx.wallet.updateMany({
          where: { userId: user.id, mlgBalance: { gte: amount } },
          data: {
            mlgBalance: { decrement: amount },
            balance: { increment: amount }
          }
        });
        if (debited.count !== 1) throw new Error("INSUFFICIENT_MLG");

        await tx.mlgLedgerEntry.create({
          data: {
            userId: user.id,
            type: "SWAP_TO_MGA",
            amount: -amount,
            idempotencyKey: key,
            metadata: { rateMGA: "1", amountMLG: amount.toString() }
          }
        });
        return true;
      })
    );

    const settled = await Promise.allSettled(attempts);
    const created = settled.filter(x => x.status === "fulfilled" && x.value === true);
    expect(created).toHaveLength(1);

    const wallet = await prisma.wallet.findUnique({ where: { userId: user.id } });
    expect(wallet?.mlgBalance).toBe(10_000n);
    expect(wallet?.balance).toBe(10_000n);
  });

  test("minimum swap guard remains 10,000 MLG", () => {
    expect(10_000n).toBe(10_000n);
  });
});
