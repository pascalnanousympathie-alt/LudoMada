import crypto from "node:crypto";
import { PaymentMethod } from "@prisma/client";
import { providerFor } from "../../src/payments/providers";

describe("payment providers", () => {
  const oldFetch = global.fetch;
  beforeEach(() => {
    delete process.env.MVOLA_API_KEY;
    delete process.env.MVOLA_CLIENT_ID;
    delete process.env.MVOLA_CLIENT_SECRET;
    delete process.env.MVOLA_PARTNER_MSISDN;
    delete process.env.MVOLA_PARTNER_NAME;
    delete process.env.MVOLA_TIMEOUT_MS;
    delete process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED;
    delete process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED;
    delete process.env.AIRTEL_CLIENT_ID;
    delete process.env.AIRTEL_CLIENT_SECRET;
    delete process.env.AIRTEL_API_BASE_URL;
    delete process.env.AIRTEL_COUNTRY;
    delete process.env.AIRTEL_CURRENCY;
    delete process.env.AIRTEL_TIMEOUT_MS;
    delete process.env.AIRTEL_MESSAGE_SIGNING_ENABLED;
    process.env.BINANCE_PAY_CERTIFICATE_SN = "test-cert";
    process.env.BINANCE_PAY_API_SECRET = "test-secret";
    process.env.BINANCE_PAY_CURRENCY = "USDT";
    process.env.BINANCE_PAY_MGA_RATE = "5000";
    delete process.env.PAYMENTS_MVOLA_LIVE_ENABLED;
    delete process.env.PAYMENTS_BINANCE_LIVE_ENABLED;
  });
  afterEach(() => { global.fetch = oldFetch; });

  test("local-money providers remain disabled without credentials", () => {
    delete process.env.MVOLA_API_KEY; delete process.env.MVOLA_CLIENT_ID; delete process.env.MVOLA_CLIENT_SECRET; delete process.env.MVOLA_PARTNER_MSISDN; delete process.env.MVOLA_PARTNER_NAME;
    expect(providerFor(PaymentMethod.MVOLA).enabled()).toBe(false);
    expect(providerFor(PaymentMethod.AIRTEL_MONEY).enabled()).toBe(false);
    expect(providerFor(PaymentMethod.ORANGE_MONEY).enabled()).toBe(false);
  });

  test("MVola API-key configuration enables provider only with partner identity", () => {
    process.env.MVOLA_API_KEY = "test-api-key";
    process.env.MVOLA_PARTNER_MSISDN = "0343500004";
    process.env.MVOLA_PARTNER_NAME = "LudoMada";
    expect(providerFor(PaymentMethod.MVOLA).enabled()).toBe(false);
    process.env.PAYMENTS_MVOLA_LIVE_ENABLED = "true";
    expect(providerFor(PaymentMethod.MVOLA).enabled()).toBe(true);
  });

  test("MVola sandbox gate enables provider without enabling live mode", () => {
    process.env.MVOLA_API_KEY = "test-api-key";
    process.env.MVOLA_PARTNER_MSISDN = "0343500004";
    process.env.MVOLA_PARTNER_NAME = "LudoMada";
    process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED = "true";
    expect(providerFor(PaymentMethod.MVOLA).enabled()).toBe(true);
    expect(process.env.PAYMENTS_MVOLA_LIVE_ENABLED).toBeUndefined();
  });

  test("MVola request timeout aborts stalled network calls", async () => {
    process.env.MVOLA_API_KEY = "test-api-key";
    process.env.MVOLA_PARTNER_MSISDN = "0343500004";
    process.env.MVOLA_PARTNER_NAME = "LudoMada";
    process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED = "true";
    process.env.MVOLA_TIMEOUT_MS = "20";
    global.fetch = jest.fn(async (_url: any, init: any) => new Promise((_resolve, reject) => {
      init.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
    })) as any;
    await expect(providerFor(PaymentMethod.MVOLA).create({ transactionId: "test-timeout", amountMGA: 500n, method: PaymentMethod.MVOLA, type: "DEPOSIT", customerPhone: "0343500003" })).rejects.toThrow("aborted");
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  test("MVola withdrawal is rejected because Merchant Pay is inbound-only", async () => {
    process.env.MVOLA_API_KEY = "test-api-key";
    process.env.MVOLA_PARTNER_MSISDN = "0343500004";
    process.env.MVOLA_PARTNER_NAME = "LudoMada";
    process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED = "true";
    await expect(providerFor(PaymentMethod.MVOLA).create({ transactionId: "test-withdrawal", amountMGA: 500n, method: PaymentMethod.MVOLA, type: "WITHDRAWAL", customerPhone: "0343500003" })).resolves.toMatchObject({ accepted: false, errorCode: "MVOLA_WITHDRAWAL_API_NOT_SUPPORTED" });
  });

  test("MVola completed status is settled only when transaction details match", async () => {
    process.env.MVOLA_API_KEY = "test-api-key";
    process.env.MVOLA_PARTNER_MSISDN = "0343500004";
    process.env.MVOLA_PARTNER_NAME = "LudoMada";
    process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED = "true";
    let call = 0;
    global.fetch = jest.fn(async () => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ status: "completed", serverCorrelationId: "corr-1", objectReference: "tx-1" }), { status: 200 });
      return new Response(JSON.stringify({ amount: "500", currency: "Ar", transactionReference: "tx-1", transactionStatus: "completed", debitParty: [{ key: "msisdn", value: "0343500003" }], creditParty: [{ key: "msisdn", value: "0343500004" }] }), { status: 200 });
    }) as any;
    await expect(providerFor(PaymentMethod.MVOLA).query("corr-1", { expectedAmountMGA: 500n, customerPhone: "0343500003", type: "DEPOSIT" })).resolves.toMatchObject({ accepted: true, settled: true, providerReference: "tx-1" });
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });

  test("MVola completed status is not settled when transaction details mismatch", async () => {
    process.env.MVOLA_API_KEY = "test-api-key";
    process.env.MVOLA_PARTNER_MSISDN = "0343500004";
    process.env.MVOLA_PARTNER_NAME = "LudoMada";
    process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED = "true";
    let call = 0;
    global.fetch = jest.fn(async () => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ status: "completed", serverCorrelationId: "corr-2", objectReference: "tx-2" }), { status: 200 });
      return new Response(JSON.stringify({ amount: "501", currency: "Ar", transactionReference: "tx-2", transactionStatus: "completed", debitParty: [{ key: "msisdn", value: "0343500003" }], creditParty: [{ key: "msisdn", value: "0343500004" }] }), { status: 200 });
    }) as any;
    await expect(providerFor(PaymentMethod.MVOLA).query("corr-2", { expectedAmountMGA: 500n, customerPhone: "0343500003", type: "DEPOSIT" })).resolves.toMatchObject({ accepted: true, settled: false, errorCode: "MVOLA_TRANSACTION_DETAILS_MISMATCH" });
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });

  test("Binance webhook signature is verified safely", () => {
    const p = providerFor(PaymentMethod.BINANCE);
    const ts = Date.now().toString();
    const nonce = "12345678901234567890123456789012";
    const body = JSON.stringify({ bizType: "PAY", bizStatus: "PAY_SUCCESS" });
    const sig = crypto.createHmac("sha512", "test-secret")
      .update(`${ts}\n${nonce}\n${body}\n`).digest("hex").toUpperCase();
    expect(p.verifyWebhook({ "binancepay-timestamp": ts, "binancepay-nonce": nonce, "binancepay-signature": sig }, body)).toBe(true);
    expect(p.verifyWebhook({ "binancepay-timestamp": ts, "binancepay-nonce": nonce, "binancepay-signature": "00" }, body)).toBe(false);
    expect(p.verifyWebhook({ "binancepay-timestamp": ts, "binancepay-nonce": "too-short", "binancepay-signature": sig }, body)).toBe(false);
    const staleTimestamp = (Date.now() - 1_001).toString();
    const staleSignature = crypto.createHmac("sha512", "test-secret")
      .update(`${staleTimestamp}\n${nonce}\n${body}\n`).digest("hex").toUpperCase();
    expect(p.verifyWebhook({ "binancepay-timestamp": staleTimestamp, "binancepay-nonce": nonce, "binancepay-signature": staleSignature }, body)).toBe(false);
  });

  test("Airtel sandbox is enabled only with credentials and explicit sandbox gate", () => {
    process.env.AIRTEL_CLIENT_ID = "test-client";
    process.env.AIRTEL_CLIENT_SECRET = "test-secret";
    expect(providerFor(PaymentMethod.AIRTEL_MONEY).enabled()).toBe(false);
    process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED = "true";
    expect(providerFor(PaymentMethod.AIRTEL_MONEY).enabled()).toBe(true);
  });

  test("Airtel v2 collection signs payload with AES-256-CBC and RSA-OAEP", async () => {
    process.env.AIRTEL_CLIENT_ID = "test-client";
    process.env.AIRTEL_CLIENT_SECRET = "test-secret";
    process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED = "true";
    const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
    const publicDer = publicKey.export({ type: "spki", format: "der" }).toString("base64");
    let call = 0;
    let paymentRequest: any;
    global.fetch = jest.fn(async (url: any, init: any) => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ access_token: "uat-token", expires_in: 180 }), { status: 200 });
      if (call === 2) return new Response(JSON.stringify({ data: { key_id: 100, key: publicDer, valid_upto: "31-12-2099 23:59:59" }, status: { success: true } }), { status: 200 });
      paymentRequest = { url: String(url), headers: init.headers, body: JSON.parse(init.body), rawBody: init.body };
      return new Response(JSON.stringify({ data: { transaction: { id: "test-airtel-1", status: "TIP" } }, status: { success: true } }), { status: 202 });
    }) as any;
    const result = await providerFor(PaymentMethod.AIRTEL_MONEY).create({ transactionId: "test-airtel-1", amountMGA: 500n, method: PaymentMethod.AIRTEL_MONEY, type: "DEPOSIT", customerPhone: "+261 33 123 4567" });
    expect(result).toMatchObject({ accepted: true, providerReference: "test-airtel-1", providerStatus: "TIP" });
    expect(paymentRequest.url).toBe("https://openapiuat.airtel.africa/merchant/v2/payments/");
    expect(paymentRequest.headers.Authorization).toBe("Bearer uat-token");
    expect(paymentRequest.headers["X-Country"]).toBe("MG");
    expect(paymentRequest.headers["X-Currency"]).toBe("MGA");
    expect(paymentRequest.headers["Content-Type"]).toBe("application/json");
    expect(paymentRequest.headers["x-signature"]).toBeTruthy();
    expect(paymentRequest.headers["x-key"]).toBeTruthy();
    expect(paymentRequest.body).toEqual({ reference: "test-airtel-1", subscriber: { country: "MG", currency: "MGA", msisdn: "331234567" }, transaction: { amount: 500, country: "MG", currency: "MGA", id: "test-airtel-1" } });

    const keyIv = crypto.privateDecrypt({
      key: privateKey,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: "sha256",
    }, Buffer.from(paymentRequest.headers["x-key"], "base64")).toString("utf8");
    const [keyB64, ivB64] = keyIv.split(":");
    expect(Buffer.from(keyB64, "base64")).toHaveLength(32);
    expect(Buffer.from(ivB64, "base64")).toHaveLength(16);
    const decipher = crypto.createDecipheriv("aes-256-cbc", Buffer.from(keyB64, "base64"), Buffer.from(ivB64, "base64"));
    const decrypted = Buffer.concat([decipher.update(Buffer.from(paymentRequest.headers["x-signature"], "base64")), decipher.final()]).toString("utf8");
    expect(decrypted).toBe(paymentRequest.rawBody);
    expect(call).toBe(3);
  });

  test("Airtel collection can omit message signing when explicitly disabled", async () => {
    process.env.AIRTEL_CLIENT_ID = "test-client";
    process.env.AIRTEL_CLIENT_SECRET = "test-secret";
    process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED = "true";
    process.env.AIRTEL_MESSAGE_SIGNING_ENABLED = "false";
    let call = 0;
    let headers: any;
    global.fetch = jest.fn(async (url: any, init: any) => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ access_token: "uat-token", expires_in: 180 }), { status: 200 });
      headers = init.headers;
      return new Response(JSON.stringify({ data: { transaction: { id: "test-airtel-2", status: "TIP" } }, status: { success: true } }), { status: 200 });
    }) as any;
    await expect(providerFor(PaymentMethod.AIRTEL_MONEY).create({ transactionId: "test-airtel-2", amountMGA: 500n, method: PaymentMethod.AIRTEL_MONEY, type: "DEPOSIT", customerPhone: "0331234567" })).resolves.toMatchObject({ accepted: true, providerReference: "test-airtel-2" });
    expect(headers["x-signature"]).toBeUndefined();
    expect(headers["x-key"]).toBeUndefined();
    expect(call).toBe(2);
  });

  test("Airtel transaction enquiry settles only TS", async () => {
    process.env.AIRTEL_CLIENT_ID = "test-client";
    process.env.AIRTEL_CLIENT_SECRET = "test-secret";
    process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED = "true";
    let call = 0;
    global.fetch = jest.fn(async () => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ access_token: "uat-token", expires_in: 180 }), { status: 200 });
      return new Response(JSON.stringify({ status: { success: true }, data: { transaction: { id: "merchant-id-1", airtel_money_id: "am-1", message: "success", status: "TS" } } }), { status: 200 });
    }) as any;
    await expect(providerFor(PaymentMethod.AIRTEL_MONEY).query("merchant-id-1", { type: "DEPOSIT" })).resolves.toMatchObject({ accepted: true, settled: true, providerStatus: "TS", providerReference: "merchant-id-1" });
    expect((global.fetch as jest.Mock).mock.calls[1][0]).toBe("https://openapiuat.airtel.africa/standard/v1/payments/merchant-id-1");
  });

  test("Airtel non-success status never settles", async () => {
    process.env.AIRTEL_CLIENT_ID = "test-client";
    process.env.AIRTEL_CLIENT_SECRET = "test-secret";
    process.env.PAYMENTS_AIRTEL_SANDBOX_ENABLED = "true";
    let call = 0;
    global.fetch = jest.fn(async () => {
      call += 1;
      if (call === 1) return new Response(JSON.stringify({ access_token: "uat-token", expires_in: 180 }), { status: 200 });
      return new Response(JSON.stringify({ status: { success: true }, data: { transaction: { id: "merchant-id-2", status: "TIP" } } }), { status: 200 });
    }) as any;
    await expect(providerFor(PaymentMethod.AIRTEL_MONEY).query("merchant-id-2", { type: "DEPOSIT" })).resolves.toMatchObject({ accepted: true, settled: false, providerStatus: "TIP" });
  });

  test("Orange remains blocked until its official contract is available", async () => {
    const provider = providerFor(PaymentMethod.ORANGE_MONEY);
    expect(provider.enabled()).toBe(false);
    await expect(provider.create({ transactionId: "test", amountMGA: 1n, method: PaymentMethod.ORANGE_MONEY, type: "DEPOSIT" as any })).resolves.toMatchObject({ accepted: false, errorCode: "OFFICIAL_PROVIDER_API_CONTRACT_REQUIRED" });
  });

  test("Binance create converts MGA and sanitizes merchant trade number", async () => {
    process.env.PAYMENTS_BINANCE_LIVE_ENABLED = "true";
    let captured: any;
    global.fetch = jest.fn(async (_url: any, init: any) => {
      captured = JSON.parse(init.body);
      return new Response(JSON.stringify({ status: "SUCCESS", code: "000000", data: { prepayId: "123456789", status: "INITIAL", checkoutUrl: "https://pay" } }), { status: 200, headers: { "content-type": "application/json" } });
    }) as any;
    const result = await providerFor(PaymentMethod.BINANCE).create({ transactionId: "550e8400-e29b-41d4-a716-446655440000", amountMGA: 1000n, method: PaymentMethod.BINANCE, type: "DEPOSIT", customerPhone: "0340000000" });
    expect(result.accepted).toBe(true);
    expect(captured.merchantTradeNo).toMatch(/^[A-Za-z0-9]{1,32}$/);
    expect(captured.orderAmount).toBe(0.2);
    expect(captured.currency).toBe("USDT");
  });

  test("Binance query settles only a PAID order", async () => {
    process.env.PAYMENTS_BINANCE_LIVE_ENABLED = "true";
    global.fetch = jest.fn(async () => new Response(JSON.stringify({ status: "SUCCESS", data: { status: "INITIAL", prepayId: "123" } }), { status: 200 })) as any;
    await expect(providerFor(PaymentMethod.BINANCE).query("123")).resolves.toMatchObject({ accepted: true, settled: false, providerStatus: "INITIAL" });
    global.fetch = jest.fn(async () => new Response(JSON.stringify({ status: "SUCCESS", data: { status: "PAID", prepayId: "123" } }), { status: 200 })) as any;
    await expect(providerFor(PaymentMethod.BINANCE).query("123")).resolves.toMatchObject({ accepted: true, settled: true, providerStatus: "PAID" });
  });
});
