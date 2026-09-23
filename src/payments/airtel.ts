import crypto from "node:crypto";
import { PaymentMethod } from "@prisma/client";
import { airtelSandboxEnabled } from "./config";
import { PaymentProvider, PaymentRequest, ProviderQueryContext, ProviderResult } from "./types";

const UAT_BASE_URL = "https://openapiuat.airtel.mg";
const SUCCESS_STATUS = "TS";
const RSA_KEY_CACHE_MS = 5 * 60 * 1000;

let rsaKeyCache: { key: crypto.KeyObject; expiresAt: number } | undefined;

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : value == null ? "" : String(value).trim();
}

function transactionOf(data: any): any {
  return data?.data?.transaction ?? data?.transaction ?? {};
}

function messageSigningEnabled(): boolean {
  return process.env.AIRTEL_MESSAGE_SIGNING_ENABLED !== "false";
}

function rsaPublicKey(value: string): crypto.KeyObject {
  const normalized = value.trim();
  if (normalized.includes("BEGIN PUBLIC KEY")) {
    return crypto.createPublicKey(normalized);
  }
  return crypto.createPublicKey({
    key: Buffer.from(normalized, "base64"),
    format: "der",
    type: "spki",
  });
}

export class AirtelMoneyProvider implements PaymentProvider {
  method = PaymentMethod.AIRTEL_MONEY;
  private token?: { value: string; expiresAt: number };

  private timeoutMs(): number {
    const value = Number(process.env.AIRTEL_TIMEOUT_MS || 15000);
    return Number.isFinite(value) && value > 0 ? Math.min(value, 60000) : 15000;
  }

  private country(): string { return (process.env.AIRTEL_COUNTRY || "MG").trim().toUpperCase(); }
  private currency(): string { return (process.env.AIRTEL_CURRENCY || "MGA").trim().toUpperCase(); }
  private baseUrl(): string {
    const base = (process.env.AIRTEL_API_BASE_URL || UAT_BASE_URL).trim().replace(/\/$/, "");
    let parsed: URL;
    try { parsed = new URL(base); } catch { throw new Error("AIRTEL_API_BASE_URL_INVALID"); }
    if (parsed.protocol !== "https:") throw new Error("AIRTEL_API_BASE_URL_MUST_USE_HTTPS");
    if (base !== UAT_BASE_URL && !process.env.AIRTEL_RELAY_TOKEN?.trim()) {
      throw new Error("AIRTEL_RELAY_TOKEN_REQUIRED");
    }
    return base;
  }
  private relayHeaders(): Record<string, string> {
    const base = this.baseUrl();
    if (base === UAT_BASE_URL) return {};
    return { "X-Relay-Token": process.env.AIRTEL_RELAY_TOKEN!.trim() };
  }

  enabled(): boolean {
    return airtelSandboxEnabled() &&
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
      headers: { "Content-Type": "application/json", Accept: "*/*", ...this.relayHeaders() },
      body: JSON.stringify({
        client_id: process.env.AIRTEL_CLIENT_ID,
        client_secret: process.env.AIRTEL_CLIENT_SECRET,
        grant_type: "client_credentials",
      }),
    });
    const data: any = await response.json().catch(() => ({}));
    const token = text(data?.access_token);
    if (!response.ok || !token) throw new Error(`AIRTEL_AUTH_HTTP_${response.status}`);
    const expiresIn = Number(data?.expires_in || 180);
    this.token = {
      value: token,
      expiresAt: Date.now() + Math.max(30, Number.isFinite(expiresIn) ? expiresIn - 30 : 150) * 1000,
    };
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
    let phone = value.trim().replace(/[\s()-]/g, "").replace(/^\+/, "");
    if (phone.startsWith("261") && phone.length >= 11) phone = phone.slice(3);
    if (phone.length === 10 && phone.startsWith("0")) phone = phone.slice(1);
    return phone;
  }

  private async encryptionKey(token: string): Promise<crypto.KeyObject> {
    if (rsaKeyCache && Date.now() < rsaKeyCache.expiresAt) return rsaKeyCache.key;
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/v1/rsa/encryption-keys`, {
      method: "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "*/*",
        "X-Country": this.country(),
        "X-Currency": this.currency(),
        ...this.relayHeaders(),
      },
    });
    const data: any = await response.json().catch(() => ({}));
    const keyText = text(data?.data?.key);
    if (!response.ok || !keyText) throw new Error(`AIRTEL_RSA_KEY_HTTP_${response.status}`);
    const key = rsaPublicKey(keyText);
    rsaKeyCache = { key, expiresAt: Date.now() + RSA_KEY_CACHE_MS };
    return key;
  }

  private async signingHeaders(token: string, payloadJson: string): Promise<Record<string, string>> {
    if (!messageSigningEnabled()) return {};
    const publicKey = await this.encryptionKey(token);
    const aesKey = crypto.randomBytes(32);
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipheriv("aes-256-cbc", aesKey, iv);
    const encryptedPayload = Buffer.concat([cipher.update(payloadJson, "utf8"), cipher.final()]).toString("base64");
    const keyIv = `${aesKey.toString("base64")}:${iv.toString("base64")}`;
    const encryptedKeyIv = crypto.publicEncrypt({
      key: publicKey,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: "sha256",
    }, Buffer.from(keyIv, "utf8")).toString("base64");
    return { "x-signature": encryptedPayload, "x-key": encryptedKeyIv };
  }
  async create(request: PaymentRequest): Promise<ProviderResult> {
    if (request.type === "WITHDRAWAL") {
      return { accepted: false, errorCode: "AIRTEL_WITHDRAWAL_API_NOT_SUPPORTED" };
    }
    if (!request.customerPhone) {
      return { accepted: false, errorCode: "AIRTEL_CUSTOMER_PHONE_REQUIRED" };
    }
    if (request.amountMGA <= 0n || request.amountMGA > BigInt(Number.MAX_SAFE_INTEGER)) {
      return { accepted: false, errorCode: "AIRTEL_AMOUNT_INVALID" };
    }
    const msisdn = this.normalizeMsisdn(request.customerPhone);
    if (!/^\d{8,15}$/.test(msisdn)) {
      return { accepted: false, errorCode: "AIRTEL_CUSTOMER_PHONE_INVALID" };
    }

    const token = await this.accessToken();
    const reference = request.transactionId.slice(0, 64);
    const payload = {
      reference,
      subscriber: { country: this.country(), currency: this.currency(), msisdn },
      transaction: {
        amount: Number(request.amountMGA),
        country: this.country(),
        currency: this.currency(),
        id: reference,
      },
    };
    const payloadJson = JSON.stringify(payload);
    const headers: Record<string, string> = {
      ...this.headers(token),
      ...(await this.signingHeaders(token, payloadJson)),
    };
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/merchant/v2/payments/`, {
      method: "POST",
      headers: { ...headers, ...this.relayHeaders() },
      body: payloadJson,
    });
    const data: any = await response.json().catch(() => ({}));
    const transaction = transactionOf(data);
    const providerReference = text(transaction?.id || data?.data?.transactionId);
    const providerStatus = text(transaction?.status);
    const accepted = response.ok && Boolean(providerReference);
    return {
      accepted,
      providerReference: providerReference || undefined,
      providerStatus: providerStatus || undefined,
      raw: data,
      errorCode: accepted ? undefined : response.ok ? "AIRTEL_API_REJECTED" : `AIRTEL_HTTP_${response.status}`,
    };
  }

  async uatDiagnostics(): Promise<{ oauthStatus: number; tokenSet: boolean; rsaStatus?: number; keySet?: boolean }> {
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/auth/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "*/*", ...this.relayHeaders() },
      body: JSON.stringify({
        client_id: process.env.AIRTEL_CLIENT_ID,
        client_secret: process.env.AIRTEL_CLIENT_SECRET,
        grant_type: "client_credentials",
      }),
    });
    const data: any = await response.json().catch(() => ({}));
    const token = text(data?.access_token);
    const result: { oauthStatus: number; tokenSet: boolean; rsaStatus?: number; keySet?: boolean } = {
      oauthStatus: response.status,
      tokenSet: Boolean(token),
    };
    if (!response.ok || !token) return result;
    try {
      const key = await this.encryptionKey(token);
      result.rsaStatus = 200;
      result.keySet = Boolean(key);
    } catch (error: any) {
      const match = String(error?.message || "").match(/AIRTEL_RSA_KEY_HTTP_(\\d+)/);
      result.rsaStatus = match ? Number(match[1]) : 0;
      result.keySet = false;
    }
    return result;
  }

  async query(providerReference: string, _context?: ProviderQueryContext): Promise<ProviderResult> {
    const token = await this.accessToken();
    const response = await this.fetchWithTimeout(`${this.baseUrl()}/standard/v1/payments/${encodeURIComponent(providerReference)}`, {
      method: "GET",
      headers: { ...this.headers(token), ...this.relayHeaders() },
    });
    const data: any = await response.json().catch(() => ({}));
    const transaction = transactionOf(data);
    const status = text(transaction?.status).toUpperCase();
    const apiSuccess = data?.status?.success;
    const accepted = response.ok && apiSuccess !== false;
    const settled = accepted && status === SUCCESS_STATUS;
    return {
      accepted,
      settled,
      providerReference: text(transaction?.id || providerReference),
      providerStatus: status || undefined,
      raw: data,
      errorCode: settled ? undefined : accepted ? undefined : response.ok ? "AIRTEL_API_REJECTED" : `AIRTEL_HTTP_${response.status}`,
    };
  }

  verifyWebhook(): boolean { return false; }
}
