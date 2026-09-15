import { PaymentMethod, TransactionType } from "@prisma/client";

export type PaymentRequest = {
  transactionId: string;
  amountMGA: bigint;
  method: PaymentMethod;
  type: TransactionType;
  customerPhone?: string;
};

export type ProviderResult = {
  /** `accepted` means the provider accepted the API request, not that money settled. */
  accepted: boolean;
  /** Set only after an authoritative provider query confirms settlement. */
  settled?: boolean;
  providerReference?: string;
  providerStatus?: string;
  checkoutUrl?: string;
  raw?: unknown;
  errorCode?: string;
};

export interface PaymentProvider {
  method: PaymentMethod;
  enabled(): boolean;
  create(request: PaymentRequest): Promise<ProviderResult>;
  query(providerReference: string): Promise<ProviderResult>;
  verifyWebhook(headers: Record<string,string|undefined>, rawBody: string): boolean;
}
