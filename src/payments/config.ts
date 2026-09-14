import { PaymentMethod } from "@prisma/client";

const env = (key: string) => Boolean(process.env[key]?.trim());

export function paymentConfig() {
  return {
    MVOLA: { configured: (env("MVOLA_API_KEY") || (env("MVOLA_CLIENT_ID") && env("MVOLA_CLIENT_SECRET"))) && env("MVOLA_PARTNER_MSISDN") && env("MVOLA_PARTNER_NAME") },
    AIRTEL_MONEY: { configured: env("AIRTEL_CLIENT_ID") && env("AIRTEL_CLIENT_SECRET") },
    ORANGE_MONEY: { configured: env("ORANGE_CLIENT_ID") && env("ORANGE_CLIENT_SECRET") },
    BINANCE: { configured: env("BINANCE_PAY_CERTIFICATE_SN") && env("BINANCE_PAY_API_SECRET") },
  } satisfies Record<PaymentMethod, {configured:boolean}>;
}
