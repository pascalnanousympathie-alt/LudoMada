import 'dotenv/config';
import { PaymentMethod } from '@prisma/client';
import { providerFor } from '../src/payments/providers.ts';
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';

const customer = process.env.MVOLA_TEST_CUSTOMER_PHONE?.trim();
const amountMGA = 500n;
if (!customer) throw new Error('MVOLA_TEST_CUSTOMER_PHONE missing');
const provider = providerFor(PaymentMethod.MVOLA);
if (!provider.enabled()) throw new Error('MVOLA provider is not enabled');
const pendingFile = '.ludomada-agent/state/mvola-sandbox-pending.env';
let transactionId = `mvola-sandbox-${Date.now()}`;
let reference = process.env.MVOLA_EXISTING_SERVER_CORRELATION_ID?.trim() || '';
if (!reference && existsSync(pendingFile)) {
  const pending = readFileSync(pendingFile, 'utf8').match(/^serverCorrelationId=(.+)$/m);
  reference = pending?.[1]?.trim() || '';
}
if (reference) {
  console.log(`SMOKE_RESUME serverCorrelationId=${reference}`);
} else {
  console.log(`SMOKE_START transaction=${transactionId} amountMGA=${amountMGA} customer=${customer}`);
  const result = await provider.create({
    transactionId,
    amountMGA,
    method: PaymentMethod.MVOLA,
    type: 'DEPOSIT',
    customerPhone: customer,
  });
  console.log(JSON.stringify({
    transactionId,
    accepted: result.accepted,
    errorCode: result.errorCode,
    providerReference: result.providerReference,
    providerStatus: result.providerStatus,
    raw: result.raw,
  }, null, 2));
  if (!result.accepted || !result.providerReference) process.exit(2);
  reference = result.providerReference;
  writeFileSync(pendingFile, `updated_at=${new Date().toISOString()}\ntransactionId=${transactionId}\nserverCorrelationId=${reference}\n`);
}
for (let i = 1; i <= 12; i++) {
  await new Promise(r => setTimeout(r, 5000));
  const q = await provider.query(reference);
  console.log(JSON.stringify({poll:i, settled:q.settled, providerReference:q.providerReference, providerStatus:q.providerStatus, raw:q.raw}, null, 2));
  reference = q.providerReference || reference;
  if (q.settled) { try { unlinkSync(pendingFile); } catch {} process.exit(0); }
  const status = String(q.providerStatus || '').toUpperCase();
  if (['FAILED','CANCELLED','CANCELED','REJECTED','EXPIRED'].includes(status)) { try { unlinkSync(pendingFile); } catch {} process.exit(3); }
}
process.exit(4);
