import bcrypt from "bcryptjs";
import { PrismaClient } from "@prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const databaseUrl = process.env.DATABASE_URL ?? "";
if (
  process.env.CI !== "true" ||
  process.env.NODE_ENV !== "staging" ||
  process.env.PORT !== "3101" ||
  !databaseUrl
) {
  throw new Error("NATIVE_TEST_SEED_REQUIRES_GITHUB_CI_STAGING");
}

const database = new URL(databaseUrl);
if (
  !["127.0.0.1", "localhost"].includes(database.hostname) ||
  database.port !== "5432" ||
  database.pathname !== "/ludomada_ci"
) {
  throw new Error("NATIVE_TEST_SEED_REFUSES_NON_DISPOSABLE_DATABASE");
}

const fixtures = ["testplayer1", "testplayer2"];
const passwordHash = await bcrypt.hash("TestPassword123", 12);
const fixtureBalance = 1_000_000n;
const prisma = new PrismaClient({
  adapter: new PrismaPg({ connectionString: databaseUrl })
});

try {
  await prisma.$connect();
  for (const username of fixtures) {
    const user = await prisma.user.upsert({
      where: { username },
      create: {
        username,
        passwordHash,
        wallet: { create: { balance: fixtureBalance } }
      },
      update: { passwordHash }
    });

    await prisma.wallet.upsert({
      where: { userId: user.id },
      create: { userId: user.id, balance: fixtureBalance },
      update: { balance: fixtureBalance }
    });
  }
  console.log("NATIVE_TEST_FIXTURES_READY count=2");
} finally {
  await prisma.$disconnect();
}
