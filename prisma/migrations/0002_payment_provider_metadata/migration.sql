ALTER TABLE "Transaction" ADD COLUMN "providerReference" TEXT;
ALTER TABLE "Transaction" ADD COLUMN "providerStatus" TEXT;
ALTER TABLE "Transaction" ADD COLUMN "customerPhone" TEXT;
ALTER TABLE "Transaction" ADD COLUMN "providerPayload" JSONB;
CREATE INDEX "Transaction_providerReference_idx" ON "Transaction"("providerReference");
