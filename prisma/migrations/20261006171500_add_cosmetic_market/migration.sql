CREATE TABLE "CosmeticPurchase" (
  "id" TEXT NOT NULL,
  "userId" TEXT NOT NULL,
  "category" TEXT NOT NULL,
  "itemId" TEXT NOT NULL,
  "priceMGA" BIGINT NOT NULL,
  "idempotencyKey" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT "CosmeticPurchase_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "CosmeticPurchase_userId_category_itemId_key"
  ON "CosmeticPurchase"("userId","category","itemId");

CREATE UNIQUE INDEX "CosmeticPurchase_userId_idempotencyKey_key"
  ON "CosmeticPurchase"("userId","idempotencyKey");

CREATE INDEX "CosmeticPurchase_userId_createdAt_idx"
  ON "CosmeticPurchase"("userId","createdAt");

CREATE INDEX "CosmeticPurchase_category_itemId_idx"
  ON "CosmeticPurchase"("category","itemId");

ALTER TABLE "CosmeticPurchase"
  ADD CONSTRAINT "CosmeticPurchase_userId_fkey"
  FOREIGN KEY ("userId") REFERENCES "User"("id")
  ON DELETE CASCADE ON UPDATE CASCADE;
