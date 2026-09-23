import crypto from "node:crypto";
import { PaymentMethod } from "@prisma/client";
import { PaymentProvider, PaymentRequest, ProviderResult } from "./types";
import { mvolaSandboxEnabled, providerLiveEnabled } from "./config";
import { AirtelMoneyProvider } from "./airtel";

function disabled(method: PaymentMethod): PaymentProvider {
  return {
    method,
    enabled: () => false,
    async create() { return { accepted: false, errorCode: "PROVIDER_NOT_CONFIGURED" }; },
    async query() { return { accepted: false, errorCode: "PROVIDER_NOT_CONFIGURED" }; },
    verifyWebhook() { return false; },
  };
}

/**
 * A credential gate for providers whose approved merchant API contract has not
 * been supplied.  It intentionally performs no network I/O: guessing a mobile
 * money endpoint, request signature, or callback format is unsafe.
 */
function officialContractRequired(method: PaymentMethod): PaymentProvider {
  return {
    method,
    enabled: () => false,
    async create() { return { accepted: false, errorCode: "OFFICIAL_PROVIDER_API_CONTRACT_REQUIRED" }; },
    async query() { return { accepted: false, errorCode: "OFFICIAL_PROVIDER_API_CONTRACT_REQUIRED" }; },
    verifyWebhook() { return false; },
  };
}

class MvolaProvider implements PaymentProvider {
  method = PaymentMethod.MVOLA;
  private baseUrl() { return (process.env.MVOLA_BASE_URL || "https://pre-api.mvola.mg").replace(/\/$/, ""); }
  private tokenUrl() { return process.env.MVOLA_TOKEN_URL || "https://developer.mvola.mg/oauth2/token"; }
  private timeoutMs() {
    const value = Number(process.env.MVOLA_TIMEOUT_MS || 15000);
    return Number.isFinite(value) && value > 0 ? Math.min(value, 60000) : 15000;
  }
  private async fetchWithTimeout(url: string, init: RequestInit) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs());
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }
  enabled() {
    return Boolean(
      (mvolaSandboxEnabled() || providerLiveEnabled("MVOLA")) &&
      (process.env.MVOLA_API_KEY?.trim() || (process.env.MVOLA_CLIENT_ID?.trim() && process.env.MVOLA_CLIENT_SECRET?.trim())) &&
      process.env.MVOLA_PARTNER_MSISDN?.trim() &&
      process.env.MVOLA_PARTNER_NAME?.trim()
    );
  }
  private async accessToken(): Promise<string> {
    if (process.env.MVOLA_CLIENT_ID?.trim() && process.env.MVOLA_CLIENT_SECRET?.trim()) {
      const basic = Buffer.from(`${process.env.MVOLA_CLIENT_ID}:${process.env.MVOLA_CLIENT_SECRET}`).toString("base64");
      const body = new URLSearchParams({ grant_type: "client_credentials", ...(process.env.MVOLA_OAUTH_SCOPE?.trim() ? { scope: process.env.MVOLA_OAUTH_SCOPE.trim() } : {}) });
      const response = await this.fetchWithTimeout(this.tokenUrl(), {
        method: "POST",
        headers: { Authorization: `Basic ${basic}`, "content-type": "application/x-www-form-urlencoded", Accept: "application/json" },
        body,
      });
      const data: any = await response.json().catch(() => ({}));
      if (!response.ok || !data?.access_token) throw new Error(`MVOLA_TOKEN_HTTP_${response.status}`);
      return String(data.access_token);
    }
    if (process.env.MVOLA_API_KEY?.trim()) return process.env.MVOLA_API_KEY.trim();
    const basic = Buffer.from(`${process.env.MVOLA_CLIENT_ID}:${process.env.MVOLA_CLIENT_SECRET}`).toString("base64");
    const body = new URLSearchParams({ grant_type: "client_credentials" });
    const response = await this.fetchWithTimeout(this.tokenUrl(), {
      method: "POST",
      headers: { Authorization: `Basic ${basic}`, "content-type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body,
    });
    const data: any = await response.json().catch(() => ({}));
    if (!response.ok || !data?.access_token) throw new Error(`MVOLA_TOKEN_HTTP_${response.status}`);
    return String(data.access_token);
  }
  private headers(token: string, correlationId: string) {
    return {
      ...(process.env.MVOLA_CLIENT_ID?.trim() && process.env.MVOLA_CLIENT_SECRET?.trim() ? { Authorization: `Bearer ${token}` } : { apikey: token }),
      Version: "1.0", "X-CorrelationID": correlationId,
      UserLanguage: process.env.MVOLA_USER_LANGUAGE || "MG",
      UserAccountIdentifier: `msisdn;${process.env.MVOLA_PARTNER_MSISDN}`,
      partnerName: process.env.MVOLA_PARTNER_NAME!, "Content-Type": "application/json", "Cache-Control": "no-cache",
    };
  }
  async create(r: PaymentRequest): Promise<ProviderResult> {
    if (r.type === "WITHDRAWAL") return { accepted: false, errorCode: "MVOLA_WITHDRAWAL_API_NOT_SUPPORTED" };
    if (!r.customerPhone) return { accepted: false, errorCode: "MVOLA_CUSTOMER_PHONE_REQUIRED" };
    const token = await this.accessToken();
    const correlationId = crypto.randomUUID();
    const requestDate = new Date().toISOString();
    const payload = {
      amount: r.amountMGA.toString(), currency: "Ar", descriptionText: `LudoMada ${r.transactionId}`.slice(0, 50),
      requestingOrganisationTransactionReference: r.transactionId.slice(0, 50), requestDate,
      originalTransactionReference: "",
      debitParty: [{ key: "msisdn", value: r.customerPhone }],
      creditParty: [{ key: "msisdn", value: process.env.MVOLA_PARTNER_MSISDN }],
      metadata: [
        { key: "partnerName", value: process.env.MVOLA_PARTNER_NAME },
        { key: "fc", value: process.env.MVOLA_METADATA_FC || "USD" },
        { key: "amountFc", value: process.env.MVOLA_METADATA_AMOUNT_FC || "1" },
      ],
    };
    const callback = process.env.MVOLA_CALLBACK_URL?.trim();
    const headers: Record<string, string> = this.headers(token, correlationId);
    if (callback) headers["X-Callback-URL"] = callback;
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/mvola/mm/transactions/type/merchantpay/1.0.0/`, {
      method: "POST", headers, body: JSON.stringify(payload),
    });
    const data: any = await response.json().catch(() => ({}));
    const accepted = response.ok && String(data?.status || "").toLowerCase() === "pending";
    return {
      accepted,
      providerReference: data?.serverCorrelationId,
      providerStatus: data?.status,
      raw: data,
      errorCode: accepted ? undefined : String(data?.code || "") === "900910" ? "MVOLA_OAUTH_SCOPE_NOT_AUTHORIZED" : `MVOLA_HTTP_${response.status}`,
    };
  }
  async query(providerReference: string, context?: { expectedAmountMGA?: bigint; customerPhone?: string; type?: "DEPOSIT" | "WITHDRAWAL" }): Promise<ProviderResult> {
    const token = await this.accessToken();
    const correlationId = crypto.randomUUID();
    const headers = this.headers(token, correlationId);
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/mvola/mm/transactions/type/merchantpay/1.0.0/status/${encodeURIComponent(providerReference)}`, {
      method: "GET", headers,
    });
    const data: any = await response.json().catch(() => ({}));
    const status = String(data?.status || data?.transactionStatus || "").toLowerCase();
    let settled = response.ok && status === "completed";
    let details: any = undefined;
    let errorCode: string | undefined = response.ok ? undefined : String(data?.code || "") === "900910" ? "MVOLA_OAUTH_SCOPE_NOT_AUTHORIZED" : `MVOLA_HTTP_${response.status}`;
    const transactionId = String(data?.objectReference || "").trim();
    if (settled && transactionId) {
      const detailsResponse = await this.fetchWithTimeout(`${this.baseUrl()}/mvola/mm/transactions/type/merchantpay/1.0.0/${encodeURIComponent(transactionId)}`, {
        method: "GET", headers: this.headers(token, crypto.randomUUID()),
      });
      details = await detailsResponse.json().catch(() => ({}));
      const amount = String(details?.amount ?? "").trim();
      const currency = String(details?.currency ?? "").trim();
      const debit = String(details?.debitParty?.find?.((x: any) => x?.key === "msisdn")?.value ?? "").trim();
      const credit = String(details?.creditParty?.find?.((x: any) => x?.key === "msisdn")?.value ?? "").trim();
      const detailStatus = String(details?.transactionStatus ?? "").toLowerCase();
      const amountMatches = context?.expectedAmountMGA === undefined || amount === context.expectedAmountMGA.toString();
      const debitMatches = !context?.customerPhone || debit === context.customerPhone.trim();
      const creditMatches = !process.env.MVOLA_PARTNER_MSISDN?.trim() || credit === process.env.MVOLA_PARTNER_MSISDN.trim();
      settled = detailsResponse.ok && detailStatus === "completed" && currency === "Ar" && amountMatches && debitMatches && creditMatches;
      if (!settled) errorCode = "MVOLA_TRANSACTION_DETAILS_MISMATCH";
    }
    return {
      accepted: response.ok,
      settled,
      providerReference: transactionId || providerReference,
      providerStatus: data?.status || data?.transactionStatus,
      raw: details ? { status: data, details } : data,
      errorCode,
    };
  }
  verifyWebhook() { return false; }
}

class BinancePayProvider implements PaymentProvider {
  method = PaymentMethod.BINANCE;
  enabled() { return Boolean(providerLiveEnabled("BINANCE") && process.env.BINANCE_PAY_CERTIFICATE_SN?.trim() && process.env.BINANCE_PAY_API_SECRET?.trim() && process.env.BINANCE_PAY_CURRENCY?.trim() && process.env.BINANCE_PAY_MGA_RATE?.trim()); }
  private sign(timestamp: string, nonce: string, body: string) { return crypto.createHmac("sha512", process.env.BINANCE_PAY_API_SECRET!).update(`${timestamp}\n${nonce}\n${body}\n`).digest("hex").toUpperCase(); }
  private async post(path: string, payload: unknown): Promise<{ ok: boolean; body: any; errorCode?: string }> {
    const body = JSON.stringify(payload), timestamp = Date.now().toString(), nonce = crypto.randomBytes(16).toString("hex");
    try {
      const r = await fetch(`https://bpay.binanceapi.com${path}`, { method: "POST", headers: { "content-type": "application/json", "BinancePay-Timestamp": timestamp, "BinancePay-Nonce": nonce, "BinancePay-Certificate-SN": process.env.BINANCE_PAY_CERTIFICATE_SN!, "BinancePay-Signature": this.sign(timestamp, nonce, body) }, body });
      const responseBody = await r.json().catch(() => ({}));
      return { ok: r.ok, body: responseBody, errorCode: r.ok ? undefined : `BINANCE_HTTP_${r.status}` };
    } catch {
      return { ok: false, body: {}, errorCode: "BINANCE_NETWORK_ERROR" };
    }
  }
  async create(r: PaymentRequest): Promise<ProviderResult> {
    const merchantTradeNo = r.transactionId.replace(/[^A-Za-z0-9]/g, "").slice(0, 32), rate = Number(process.env.BINANCE_PAY_MGA_RATE), currency = String(process.env.BINANCE_PAY_CURRENCY).toUpperCase();
    if (!merchantTradeNo || !Number.isFinite(rate) || rate <= 0 || !currency) return { accepted: false, errorCode: "BINANCE_CURRENCY_RATE_NOT_CONFIGURED" };
    const amount = Number(r.amountMGA) / rate, response = await this.post("/binancepay/openapi/order", { merchantTradeNo, orderAmount: Number(amount.toFixed(8)), currency, description: `LudoMada ${merchantTradeNo}`, goodsType: "02" }), x = response.body;
    const accepted = response.ok && x?.status === "SUCCESS";
    return { accepted, providerReference: x?.data?.prepayId, providerStatus: x?.data?.status, checkoutUrl: x?.data?.checkoutUrl, raw: x, errorCode: accepted ? undefined : response.errorCode || x?.code || "BINANCE_ORDER_REJECTED" };
  }
  async query(providerReference: string): Promise<ProviderResult> { const response = await this.post("/binancepay/openapi/order/query", { prepayId: providerReference }), x = response.body, accepted = response.ok && x?.status === "SUCCESS", settled = accepted && x?.data?.status === "PAID"; return { accepted, settled, providerReference: x?.data?.transactionId || providerReference, providerStatus: x?.data?.status, raw: x, errorCode: accepted ? undefined : response.errorCode || x?.code || "BINANCE_QUERY_REJECTED" }; }
  verifyWebhook(headers: Record<string, string | undefined>, rawBody: string) {
    const ts = headers["binancepay-timestamp"], nonce = headers["binancepay-nonce"], sig = headers["binancepay-signature"];
    const timestamp = Number(ts);
    // Binance Pay specifies a 32-character alphanumeric nonce and a one-second
    // timestamp window. Reject stale or malformed signed requests before HMAC.
    if (!ts || !nonce || !sig || !/^[A-Za-z0-9]{32}$/.test(nonce) || !Number.isSafeInteger(timestamp) || Math.abs(Date.now() - timestamp) > 1_000) return false;
    const expected = this.sign(ts, nonce, rawBody), a = Buffer.from(sig), b = Buffer.from(expected); return a.length === b.length && crypto.timingSafeEqual(a, b);
  }
}

export function providerFor(method: PaymentMethod): PaymentProvider {
  if (method === PaymentMethod.MVOLA) return new MvolaProvider();
  if (method === PaymentMethod.AIRTEL_MONEY) return new AirtelMoneyProvider();
  if (method === PaymentMethod.BINANCE) return new BinancePayProvider();
  if (method === PaymentMethod.ORANGE_MONEY) return officialContractRequired(method);
  return disabled(method);
}
