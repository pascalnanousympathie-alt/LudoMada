import { PaymentMethod } from "@prisma/client";
import { airtelSandboxEnabled } from "./config";
import { PaymentProvider, PaymentRequest, ProviderQueryContext, ProviderResult } from "./types";

const UAT_BASE_URL = "https://openapiuat.airtel.africa";
const SUCCESS_STATUS_CODES = new Set(["TS", "SUCCESS"]);

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : value == null ? "" : String(value).trim();
}

function transactionOf(data: any): any {
  return data?.data?.transaction ?? data?.transaction ?? {};
}

export class AirtelMoneyProvider implements PaymentProvider {
  method = PaymentMethod.AIRTEL_MONEY;
  private token?: { value: string; expiresAt: number };

  private baseUrl(): string {
    return (process.env.AIRTEL_API_BASE_URL || UAT_BASE_URL).replace(/\/$/, "");
  }

  private sandboxBaseUrl(): boolean {
    try {
      const url = new URL(this.baseUrl());
      return url.protocol === "https:" && url.hostname === "openapiuat.airtel.africa";
    } catch {
      return false;
    }
  }

  private timeoutMs(): number {
    const value = Number(process.env.AIRTEL_TIMEOUT_MS || 15000);
    return Number.isFinite(value) && value > 0 ? Math.min(value, 60000) : 15000;
  }

  private country(): string { return (process.env.AIRTEL_COUNTRY || "MG").trim().toUpperCase(); }
  private currency(): string { return (process.env.AIRTEL_CURRENCY || "MGA").trim().toUpperCase(); }

  enabled(): boolean {
    return airtelSandboxEnabled() && this.sandboxBaseUrl() &&
      Boolean(process.env.AIRTEL_CLIENT_ID?.trim() && process.env.AIRTEL_CLIENT_SECRET?.trim());
  }

  private async fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs());
    try {
      return await fetch(url, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }
  private async accessToken(): Promise<string> {
    if (!this.enabled()) throw new Error("AIRTEL_PROVIDER_NOT_ENABLED");
    if (this.token && Date.now() < this.token.expiresAt) return this.token.value;
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/auth/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "*/*" },
      body: JSON.stringify({
        client_id: process.env.AIRTEL_CLIENT_ID,
        client_secret: process.env.AIRTEL_CLIENT_SECRET,
        grant_type: "client_credentials",
      }),
    });
    const data: any = await response.json().catch(() => ({}));
    const token = text(data?.access_token);
    if (!response.ok || !token) throw new Error(`AIRTEL_AUTH_HTTP_${response.status}`);
    const expiresIn = Number(data?.expires_in || 3600);
    this.token = { value: token, expiresAt: Date.now() + Math.max(60, expiresIn - 60) * 1000 };
    return token;
  }

  private headers(token: string): Record<string, string> {
    return {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "*/*",
      "X-Country": this.country(),
      "X-Currency": this.currency(),
    };
  }

  private normalizeMsisdn(value: string): string {
    return value.trim().replace(/^\+/, "").replace(/[\s()-]/g, "");
  }
  async create(request: PaymentRequest): Promise<ProviderResult> {
    if (request.type === "WITHDRAWAL") return { accepted: false, errorCode: "AIRTEL_WITHDRAWAL_API_NOT_SUPPORTED" };
    if (!request.customerPhone) return { accepted: false, errorCode: "AIRTEL_CUSTOMER_PHONE_REQUIRED" };
    if (request.amountMGA <= 0n || request.amountMGA > BigInt(Number.MAX_SAFE_INTEGER)) return { accepted: false, errorCode: "AIRTEL_AMOUNT_INVALID" };
    const msisdn = this.normalizeMsisdn(request.customerPhone);
    if (!/^\d{8,15}$/.test(msisdn)) return { accepted: false, errorCode: "AIRTEL_CUSTOMER_PHONE_INVALID" };
    const token = await this.accessToken();
    const reference = request.transactionId.slice(0, 64);
    const payload = {
      reference,
      subscriber: { country: this.country(), currency: this.currency(), msisdn },
      transaction: { amount: Number(request.amountMGA), country: this.country(), currency: this.currency(), id: reference },
    };
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/merchant/v1/payments/`, {
      method: "POST", headers: this.headers(token), body: JSON.stringify(payload),
    });
    const data: any = await response.json().catch(() => ({}));
    const transaction = transactionOf(data);
    const providerReference = text(transaction?.id || transaction?.airtel_money_id || data?.data?.transactionId);
    const apiSuccess = data?.status?.success;
    const accepted = response.ok && apiSuccess !== false && Boolean(providerReference);
    return {
      accepted,
      providerReference: providerReference || undefined,
      providerStatus: text(transaction?.status || transaction?.status_code) || undefined,
      raw: data,
      errorCode: accepted ? undefined : response.ok ? "AIRTEL_API_REJECTED" : `AIRTEL_HTTP_${response.status}`,
    };
  }

  async query(providerReference: string, context?: ProviderQueryContext): Promise<ProviderResult> {
    const token = await this.accessToken();
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/standard/v1/payments/${encodeURIComponent(providerReference)}`, {
      method: "GET", headers: this.headers(token),
    });
    const data: any = await response.json().catch(() => ({}));
    const transaction = transactionOf(data);
    const status = text(transaction?.status || transaction?.status_code).toUpperCase();
    const amountText = text(transaction?.amount);
    const phoneText = text(transaction?.msisdn || transaction?.subscriber?.msisdn);
    const amountMatches = !context?.expectedAmountMGA || !amountText || amountText === context.expectedAmountMGA.toString();
    const phoneMatches = !context?.customerPhone || !phoneText || this.normalizeMsisdn(phoneText) === this.normalizeMsisdn(context.customerPhone);
    const apiSuccess = data?.status?.success;
    const accepted = response.ok && apiSuccess !== false;
    const settled = accepted && SUCCESS_STATUS_CODES.has(status) && amountMatches && phoneMatches;
    let errorCode: string | undefined;
    if (!accepted) errorCode = response.ok ? "AIRTEL_API_REJECTED" : `AIRTEL_HTTP_${response.status}`;
    else if (SUCCESS_STATUS_CODES.has(status) && !amountMatches) errorCode = "AIRTEL_TRANSACTION_AMOUNT_MISMATCH";
    else if (SUCCESS_STATUS_CODES.has(status) && !phoneMatches) errorCode = "AIRTEL_TRANSACTION_PHONE_MISMATCH";
    return {
      accepted, settled,
      providerReference: text(transaction?.id || providerReference),
      providerStatus: status || undefined,
      raw: data,
      errorCode,
    };
  }

  verifyWebhook(): boolean { return false; }
}
