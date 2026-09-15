import { PaymentMethod } from "@prisma/client";

const env = (key: string) => Boolean(process.env[key]?.trim());
const providerLiveEnabled = (provider: "MVOLA" | "BINANCE") => process.env[`PAYMENTS_${provider}_LIVE_ENABLED`] === "true";
const mvolaSandboxEnabled = () => process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED === "true";

export function paymentConfig() {
  return {
    MVOLA: { configured: (env("MVOLA_API_KEY") || (env("MVOLA_CLIENT_ID") && env("MVOLA_CLIENT_SECRET"))) && env("MVOLA_PARTNER_MSISDN") && env("MVOLA_PARTNER_NAME"), enabled: mvolaSandboxEnabled() || providerLiveEnabled("MVOLA") },
    // These providers deliberately remain disabled until their approved merchant
    // API contracts are available in this project. Credentials alone must not
    // cause an undocumented endpoint to be called.
    AIRTEL_MONEY: { configured: env("AIRTEL_CLIENT_ID") && env("AIRTEL_CLIENT_SECRET"), enabled: false },
    ORANGE_MONEY: { configured: env("ORANGE_CLIENT_ID") && env("ORANGE_CLIENT_SECRET"), enabled: false },
    BINANCE: { configured: env("BINANCE_PAY_CERTIFICATE_SN") && env("BINANCE_PAY_API_SECRET") && env("BINANCE_PAY_CURRENCY") && env("BINANCE_PAY_MGA_RATE"), enabled: providerLiveEnabled("BINANCE") },
  } satisfies Record<PaymentMethod, { configured: boolean; enabled: boolean }>;
}

export { providerLiveEnabled, mvolaSandboxEnabled };
