import { PaymentMethod } from "@prisma/client";

const env = (key: string) => Boolean(process.env[key]?.trim());
const providerLiveEnabled = (provider: "MVOLA" | "BINANCE") => process.env[`PAYMENTS_${provider}_LIVE_ENABLED`] === "true";
const mvolaSandboxEnabled = () => process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED === "true";
const airtelSandboxEnabled = () => process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED === "true";

function binanceCurrencies(): string[] {
  const raw = process.env.BINANCE_PAY_CURRENCIES || process.env.BINANCE_PAY_CURRENCY || "";
  return Array.from(new Set(raw.split(",").map(v => v.trim().toUpperCase()).filter(Boolean)));
}

function binanceRates(): Record<string, number> {
  const out: Record<string, number> = {};
  try {
    const parsed = JSON.parse(process.env.BINANCE_PAY_MGA_RATES_JSON || "{}");
    if (parsed && typeof parsed === "object") {
      for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
        const n = Number(value);
        if (Number.isFinite(n) && n > 0) out[key.trim().toUpperCase()] = n;
      }
    }
  } catch {}
  const single = process.env.BINANCE_PAY_CURRENCY?.trim().toUpperCase();
  const singleRate = Number(process.env.BINANCE_PAY_MGA_RATE);
  if (single && Number.isFinite(singleRate) && singleRate > 0 && !out[single]) out[single] = singleRate;
  return out;
}

export function paymentConfig() {
  return {
    MVOLA: { configured: (env("MVOLA_API_KEY") || (env("MVOLA_CLIENT_ID") && env("MVOLA_CLIENT_SECRET"))) && env("MVOLA_PARTNER_MSISDN") && env("MVOLA_PARTNER_NAME"), enabled: mvolaSandboxEnabled() || providerLiveEnabled("MVOLA") },
    // Airtel collection uses the documented UAT Open API contract. Sandbox mode
    // is explicit so credentials alone never activate provider traffic.
    AIRTEL_MONEY: { configured: env("AIRTEL_CLIENT_ID") && env("AIRTEL_CLIENT_SECRET"), enabled: airtelSandboxEnabled() && env("AIRTEL_CLIENT_ID") && env("AIRTEL_CLIENT_SECRET") },
    // Orange remains blocked until its approved merchant API contract is available.
    ORANGE_MONEY: { configured: env("ORANGE_CLIENT_ID") && env("ORANGE_CLIENT_SECRET"), enabled: false },
    BINANCE: {
      configured: env("BINANCE_PAY_CERTIFICATE_SN") && env("BINANCE_PAY_API_SECRET") && binanceCurrencies().length > 0 && Object.keys(binanceRates()).length > 0,
      enabled: providerLiveEnabled("BINANCE"),
      currency: binanceCurrencies()[0] || null,
      currencies: binanceCurrencies(),
      rates: binanceRates(),
      mgaRate: binanceCurrencies()[0] ? (binanceRates()[binanceCurrencies()[0]] ?? null) : null
    },
  } satisfies Record<PaymentMethod, { configured: boolean; enabled: boolean; [key: string]: unknown }>;
}

export { providerLiveEnabled, mvolaSandboxEnabled, airtelSandboxEnabled };
