# LudoMada — MVola Production / GO LIVE Checklist

## Current code status

- Payment enum already contains `MVOLA`.
- Wallet deposit/withdrawal routes already pass the selected payment method through the provider layer.
- `src/payments/config.ts` recognizes `MVOLA_CLIENT_ID` + `MVOLA_CLIENT_SECRET` as credential presence.
- `src/payments/providers.ts` currently keeps MVola disabled; no unverified live endpoint/authentication flow is assumed.
- No real MVola secret is committed to Git.

## Official onboarding gate

MVola's published terms state that API access requires a certified MVola account, that the test environment is SANDBOX, and that the real production environment becomes effective only after a partner request and MVola approval called GO LIVE.

Source: https://developer.mvola.mg/authenticationendpoint/privacy_policy.do

## Information to obtain from MVola Developer

Record the exact values supplied by MVola in the secure production secret store; never place them in Git:

- Production `client_id`
- Production `client_secret` / private authentication key as specified by MVola
- Official production API base URL
- Exact authentication method and token endpoint from the current approved API documentation
- Exact Merchant Payment endpoint(s)
- Required request headers and merchant identifiers
- Required callback/webhook URL and event format, if applicable
- Production certificate/key requirements, if applicable
- Sandbox test evidence and GO LIVE approval
- Rate limits, transaction limits, timeout/retry guidance, and reconciliation/query endpoint details

## Before enabling MVola in LudoMada

1. Confirm the MVola developer account is certified.
2. Confirm the LudoMada application is approved for the required Merchant Payment API.
3. Complete the official SANDBOX test cases.
4. Register/approve the HTTPS callback URL.
5. Obtain production credentials and the exact production endpoints from MVola.
6. Add secrets only to the production environment/secret manager.
7. Implement the MVola adapter from the current official API specification; do not copy old/unverified endpoint examples.
8. Add automated tests for authentication, payment creation, status/query, callback verification, timeout/retry, idempotency and failure mapping.
9. Run a small controlled production verification transaction only after GO LIVE approval.
10. Reconcile the provider result against the LudoMada transaction/ledger before marking the payment flow PASS.

## Current gate

**BLOCKED — waiting for official MVola production credentials + current approved API specification + GO LIVE approval.**

## Runtime safety gate

MVola is disabled unless both its required configuration and
`PAYMENTS_MVOLA_LIVE_ENABLED=true` are present. The switch is deliberately
provider-specific and must only be set after this checklist is complete. A
disabled provider is rejected before the wallet routes create a transaction or
debit a balance.

This is intentional. Credentials are never fabricated and the provider remains disabled until the official production contract is available.
