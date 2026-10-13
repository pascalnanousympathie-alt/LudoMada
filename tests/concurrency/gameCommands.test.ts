import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import pg from "pg";
import { randomUUID } from "node:crypto";
import { newGame } from "../../src/game/ludoEngine";
import { AuthoritativeCommandService } from "../../src/game/authoritativeCommandService";

const enabled = Boolean(process.env.DATABASE_URL);
const describeDb = enabled ? describe : describe.skip;

jest.setTimeout(60_000);

describeDb("authoritative multiplayer concurrency", () => {
  let prisma: PrismaClient;
  let pool: pg.Pool;
  const userIds: string[] = [];
  const gameIds: string[] = [];

  beforeAll(async () => {
    pool = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 12, min: 0 });
    prisma = new PrismaClient({ adapter: new PrismaPg(pool) });
    await prisma.$queryRaw`SELECT 1`;
  });

  afterAll(async () => {
    if (gameIds.length) await prisma.game.deleteMany({ where: { id: { in: gameIds } } });
    if (userIds.length) await prisma.user.deleteMany({ where: { id: { in: userIds } } });
    await prisma.$disconnect();
    await pool.end();
  });

  async function user(label: string) {
    const id = randomUUID();
    const username = "game_conc_" + label + "_" + id.replace(/-/g, "").slice(0, 10);
    const row = await prisma.user.create({
      data: { id, username, passwordHash: "test-only", wallet: { create: {} } }
    });
    userIds.push(row.id);
    return row;
  }

  async function createGame(playerIds: string[]) {
    const id = randomUUID();
    await prisma.game.create({
      data: {
        id,
        creatorId: playerIds[0],
        status: "PLAYING",
        grossAmount: 0n,
        commission: 0n,
        prizePool: 0n,
        participants: { create: playerIds.map(userId => ({ userId, entryFee: 0n })) }
      }
    });
    gameIds.push(id);
    return id;
  }

  test("concurrent rolls: one authoritative mutation wins the state-version race", async () => {
    const a = await user("roll-a");
    const b = await user("roll-b");
    const gameId = await createGame([a.id, b.id]);
    const state = newGame([a.id, b.id]);
    await prisma.authoritativeGameState.create({ data: { gameId, stateVersion: 0, state: state as any } });

    const service = new AuthoritativeCommandService(prisma);
    const results = await Promise.allSettled(Array.from({ length: 8 }, (_, i) =>
      service.roll(gameId, a.id, 0, "roll-conc-" + i + "-" + randomUUID())
    ));

    expect(results.filter(x => x.status === "fulfilled")).toHaveLength(1);
    const current = await prisma.authoritativeGameState.findUnique({ where: { gameId } });
    const game = await prisma.game.findUnique({ where: { id: gameId } });
    const saved = current?.state as any;
    expect(saved.dice === null || Number.isInteger(saved.dice)).toBe(true);
    expect(saved.stateVersion).toBe(game?.stateVersion);
    expect(saved.currentPlayerIndex).toBeGreaterThanOrEqual(0);
    expect(saved.currentPlayerIndex).toBeLessThan(2);
  });

  test("same roll idempotency key replays the original result", async () => {
    const a = await user("idem-a");
    const b = await user("idem-b");
    const gameId = await createGame([a.id, b.id]);
    const state = newGame([a.id, b.id]);
    await prisma.authoritativeGameState.create({ data: { gameId, stateVersion: 0, state: state as any } });

    const service = new AuthoritativeCommandService(prisma);
    const key = "roll-idem-" + randomUUID();
    const first = await service.roll(gameId, a.id, 0, key);
    const second = await service.roll(gameId, a.id, 0, key);

    expect(second.result).toEqual(first.result);
    const row = await prisma.gameCommand.findUnique({ where: { idempotencyKey: key } });
    expect(row).not.toBeNull();
    expect(await prisma.gameCommand.count({ where: { gameId, commandType: "ROLL" } })).toBe(1);
  });
});
