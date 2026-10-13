-- MLG economy: reward balance, immutable ledger, referral attribution, cosmetic ownership
CREATE TYPE "MlgLedgerType" AS ENUM (
  'EARN_VICTORY',
  'EARN_REFERRAL',
  'MARKET_PURCHASE',
  'SWAP_TO_MGA',
  'ADMIN_ADJUSTMENT'
);

ALTER TABLE "Wallet"
  ADD COLUMN "mlgBalance" BIGINT NOT NULL DEFAULT 0;

ALTER TABLE "User"
  ADD COLUMN "referralCode" TEXT;

CREATE UNIQUE INDEX "User_referralCode_key" ON "User"("referralCode");

CREATE TABLE "MlgLedgerEntry" (
  "id" TEXT NOT NULL,
  "userId" TEXT NOT NULL,
  "gameId" TEXT,
  "type" "MlgLedgerType" NOT NULL,
  "amount" BIGINT NOT NULL,
  "idempotencyKey" TEXT NOT NULL,
  "metadata" JSONB,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT "MlgLedgerEntry_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "MlgLedgerEntry_idempotencyKey_key" ON "MlgLedgerEntry"("idempotencyKey");
CREATE INDEX "MlgLedgerEntry_userId_createdAt_idx" ON "MlgLedgerEntry"("userId", "createdAt");
CREATE INDEX "MlgLedgerEntry_gameId_createdAt_idx" ON "MlgLedgerEntry"("gameId", "createdAt");

ALTER TABLE "MlgLedgerEntry"
  ADD CONSTRAINT "MlgLedgerEntry_userId_fkey"
  FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "MlgLedgerEntry"
  ADD CONSTRAINT "MlgLedgerEntry_gameId_fkey"
  FOREIGN KEY ("gameId") REFERENCES "Game"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "Referral" (
  "id" TEXT NOT NULL,
  "inviterId" TEXT NOT NULL,
  "inviteeId" TEXT NOT NULL,
  "code" TEXT NOT NULL,
  "rewardMlg" BIGINT NOT NULL DEFAULT 100,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT "Referral_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Referral_inviteeId_key" ON "Referral"("inviteeId");
CREATE UNIQUE INDEX "Referral_inviterId_inviteeId_key" ON "Referral"("inviterId", "inviteeId");
CREATE INDEX "Referral_code_createdAt_idx" ON "Referral"("code", "createdAt");

ALTER TABLE "Referral"
  ADD CONSTRAINT "Referral_inviterId_fkey"
  FOREIGN KEY ("inviterId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "Referral"
  ADD CONSTRAINT "Referral_inviteeId_fkey"
  FOREIGN KEY ("inviteeId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE "CosmeticOwnership" (
  "id" TEXT NOT NULL,
  "userId" TEXT NOT NULL,
  "category" TEXT NOT NULL,
  "itemId" TEXT NOT NULL,
  "priceMlg" BIGINT NOT NULL,
  "purchasedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT "CosmeticOwnership_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "CosmeticOwnership_userId_category_itemId_key" ON "CosmeticOwnership"("userId", "category", "itemId");
CREATE INDEX "CosmeticOwnership_userId_purchasedAt_idx" ON "CosmeticOwnership"("userId", "purchasedAt");

ALTER TABLE "CosmeticOwnership"
  ADD CONSTRAINT "CosmeticOwnership_userId_fkey"
  FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Keep the basic/free cosmetic set usable without a purchase transaction.
-- Existing users receive referral codes lazily from the /api/mlg/summary route.
