# Gitleaks: reviewed historical test fixtures

The full-history Gitleaks audit on main (run 41532927827, commit
158d77d3443564bb3774ee5949b09f1309d1132b) reported eight findings in
older test commits under `tools/mada-commander-public-mcp/tests/`.

Each finding was reviewed at its original commit and in its test context:

- `mcp-integrations.test.mjs`: one deliberately synthetic JWT used to test
  redaction, plus GitHub/Supabase token-shaped strings built from fixed test
  prefixes and deterministic non-secret data. Assertions check that these
  values never leave the redaction boundary.
- `command-execution.test.mjs`: example values on `db.example.test` and
  deterministic fake API/Supabase key strings used to verify command-output
  redaction. Assertions require the values to be redacted.
- `public-boundary.test.mjs`: a deliberately fake token string passed to
  `workspaceWrite` to verify that credential-like content is rejected.

The values are synthetic fixtures, not credentials loaded from an environment,
secret store, external provider, or production system. No secret values are
stored in this document.

The root `.gitleaksignore` lists only the eight exact fingerprints emitted by
that historical scan. It does not ignore any path, rule, token prefix, or
future commit. A changed/new finding receives a different fingerprint and
continues to fail the scan. Re-review any fingerprint before adding another
entry; never use a broad path or rule allowlist to silence secret detection.

If any of the original test values are later shown to have been real credentials,
treat them as compromised and rotate them; this allowlist is not a substitute
for credential rotation.
