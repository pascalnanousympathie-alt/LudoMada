# V6.4 Load / Security Test Plan

Run the k6 smoke/load test against a disposable staging environment:

`k6 run -e API_BASE_URL=https://staging.example loadtests/games.js`

Before increasing VUs, verify:
- database connection pool limits
- rate limits
- p95/p99 latency
- error rate
- serialization/deadlock behavior
- no duplicate GameCommand records
- no double settlement in wallet/ledger

Do not point these tests at a real-money production environment.
