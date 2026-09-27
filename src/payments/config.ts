import { PaymentMethod } from "@prisma/client";

const env = (key: string) => Boolean(process.env[key]?.trim());
const providerLiveEnabled = (provider: "MVOLA" | "BINANCE") => process.env[`PAYMENTS_${provider}_LIVE_ENABLED`] === "true";
const mvolaSandboxEnabled = () => process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED === "true";
const airtelSandboxEnabled = () => process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED === "true";

export function paymentConfig() {
  return {
    MVOLA: { configured: (env("MVOLA_API_KEY") || (env("MVOLA_CLIENT_ID") && env("MVOLA_CLIENT_SECRET"))) && env("MVOLA_PARTNER_MSISDN") && env("MVOLA_PARTNER_NAME"), enabled: mvolaSandboxEnabled() || providerLiveEnabled("MVOLA") },
    // Airtel collection uses the documented UAT Open API contract. Sandbox mode
    // is explicit so credentials alone never activate provider traffic.
    AIRTEL_MONEY: { configured: env("AIRTEL_CLIENT_ID") && env("AIRTEL_CLIENT_SECRET"), enabled: airtelSandboxEnabled() && env("AIRTEL_CLIENT_ID") && env("AIRTEL_CLIENT_SECRET") },
    // Orange remains blocked until its approved merchant API contract is available.
    ORANGE_MONEY: { configured: env("ORANGE_CLIENT_ID") && env("ORANGE_CLIENT_SECRET"), enabled: false },
    BINANCE: { configured: env("BINANCE_PAY_CERTIFICATE_SN") && env("BINANCE_PAY_API_SECRET") && env("BINANCE_PAY_CURRENCY") && env("BINANCE_PAY_MGA_RATE"), enabled: providerLiveEnabled("BINANCE"), currency: process.env.BINANCE_PAY_CURRENCY || null, mgaRate: Number.isFinite(Number(process.env.BINANCE_PAY_MGA_RATE)) ? Number(process.env.BINANCE_PAY_MGA_RATE) : null },
  } satisfies Record<PaymentMethod, { configured: boolean; enabled: boolean; [key: string]: unknown }>;
}

export { providerLiveEnabled, mvolaSandboxEnabled, airtelSandboxEnabled };
