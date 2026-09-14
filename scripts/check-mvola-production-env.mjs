const required = [
  'MVOLA_CLIENT_ID',
  'MVOLA_CLIENT_SECRET',
  'MVOLA_BASE_URL',
  'MVOLA_CALLBACK_URL',
];

const placeholders = new Set([
  '',
  'REPLACE_WITH_MVOLA_PRODUCTION_CLIENT_ID',
  'REPLACE_WITH_MVOLA_PRODUCTION_CLIENT_SECRET',
  'REPLACE_WITH_MVOLA_PRODUCTION_BASE_URL',
]);

const missing = required.filter((key) => {
  const value = (process.env[key] ?? '').trim();
  return placeholders.has(value);
});

if (missing.length) {
  console.error(`MVOLA_PRODUCTION_ENV=BLOCKED missing_or_placeholder=${missing.join(',')}`);
  process.exit(2);
}

try {
  const url = new URL(process.env.MVOLA_BASE_URL);
  if (url.protocol !== 'https:') throw new Error('MVOLA_BASE_URL must use https');
  const callback = new URL(process.env.MVOLA_CALLBACK_URL);
  if (callback.protocol !== 'https:') throw new Error('MVOLA_CALLBACK_URL must use https');
} catch (err) {
  console.error(`MVOLA_PRODUCTION_ENV=BLOCKED invalid_url=${err.message}`);
  process.exit(3);
}

console.log('MVOLA_PRODUCTION_ENV=READY_FOR_CREDENTIAL_INJECTION');
