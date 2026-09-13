import crypto from "node:crypto";
import { PaymentMethod } from "@prisma/client";
import { PaymentProvider, PaymentRequest, ProviderResult } from "./types";

function disabled(method: PaymentMethod): PaymentProvider {
  return {
    method,
    enabled: () => false,
    async create() { return {accepted:false,errorCode:"PROVIDER_NOT_CONFIGURED"}; },
    async query() { return {accepted:false,errorCode:"PROVIDER_NOT_CONFIGURED"}; },
    verifyWebhook() { return false; },
  };
}

class BinancePayProvider implements PaymentProvider {
  method = PaymentMethod.BINANCE;
  enabled() { return Boolean(process.env.BINANCE_PAY_CERTIFICATE_SN && process.env.BINANCE_PAY_API_SECRET && process.env.BINANCE_PAY_CURRENCY && process.env.BINANCE_PAY_MGA_RATE); }
  private sign(timestamp:string, nonce:string, body:string) {
    return crypto.createHmac("sha512", process.env.BINANCE_PAY_API_SECRET!).update(`${timestamp}\n${nonce}\n${body}\n`).digest("hex").toUpperCase();
  }
  private async post(path:string, payload:unknown):Promise<any> {
    const body=JSON.stringify(payload), timestamp=Date.now().toString(), nonce=crypto.randomBytes(16).toString("hex");
    const r=await fetch(`https://bpay.binanceapi.com${path}`, {method:"POST",headers:{"content-type":"application/json","BinancePay-Timestamp":timestamp,"BinancePay-Nonce":nonce,"BinancePay-Certificate-SN":process.env.BINANCE_PAY_CERTIFICATE_SN!,"BinancePay-Signature":this.sign(timestamp,nonce,body)},body});
    return r.json();
  }
  async create(r:PaymentRequest):Promise<ProviderResult> {
    const merchantTradeNo=r.transactionId.replace(/[^A-Za-z0-9]/g, "").slice(0,32);
    const rate=Number(process.env.BINANCE_PAY_MGA_RATE);
    const currency=String(process.env.BINANCE_PAY_CURRENCY).toUpperCase();
    if(!merchantTradeNo || !Number.isFinite(rate) || rate<=0 || !currency) return {accepted:false,errorCode:"BINANCE_CURRENCY_RATE_NOT_CONFIGURED"};
    const amount=Number(r.amountMGA)/rate;
    const x=await this.post("/binancepay/openapi/order",{merchantTradeNo,orderAmount:Number(amount.toFixed(8)),currency,description:`LudoMada ${merchantTradeNo}`,goodsType:"02"});
    return {accepted:x?.status==="SUCCESS",providerReference:x?.data?.prepayId,providerStatus:x?.data?.status,checkoutUrl:x?.data?.checkoutUrl,raw:x,errorCode:x?.status==="SUCCESS"?undefined:x?.code};
  }
  async query(providerReference:string):Promise<ProviderResult> {
    const x=await this.post("/binancepay/openapi/order/query",{prepayId:providerReference});
    return {accepted:x?.status==="SUCCESS",providerReference:x?.data?.transactionId||providerReference,providerStatus:x?.data?.status,raw:x,errorCode:x?.status==="SUCCESS"?undefined:x?.code};
  }
  verifyWebhook(headers:Record<string,string|undefined>, rawBody:string) {
    const ts=headers["binancepay-timestamp"], nonce=headers["binancepay-nonce"], sig=headers["binancepay-signature"];
    if(!ts||!nonce||!sig||Math.abs(Date.now()-Number(ts))>60_000) return false;
    const expected=this.sign(ts,nonce,rawBody);
    const a=Buffer.from(sig); const b=Buffer.from(expected);
    return a.length===b.length && crypto.timingSafeEqual(a,b);
  }
}

export function providerFor(method: PaymentMethod): PaymentProvider {
  if(method===PaymentMethod.BINANCE) return new BinancePayProvider();
  return disabled(method);
}
