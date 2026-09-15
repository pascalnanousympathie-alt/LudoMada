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
    delete process.env.PAYMENTS_MVOLA_SANDBOX_ENABLED;
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

  test("Airtel and Orange never call an undocumented provider API", async () => {
    for (const method of [PaymentMethod.AIRTEL_MONEY, PaymentMethod.ORANGE_MONEY]) {
      const provider = providerFor(method);
      expect(provider.enabled()).toBe(false);
      await expect(provider.create({ transactionId: "test", amountMGA: 1n, method, type: "DEPOSIT" as any })).resolves.toMatchObject({ accepted: false, errorCode: "OFFICIAL_PROVIDER_API_CONTRACT_REQUIRED" });
    }
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
