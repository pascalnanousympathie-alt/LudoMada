# Public vs private tool boundary

## Private-only: never include on the public listing
The existing personal Commander tools touch one local Termux account and its credentials. These capabilities stay private:
- arbitrary exec and long-running shell process tools
- arbitrary file write/edit/move, rollback, and unrestricted local workspace access
- JavaScript evaluation in the local browser/CDP session
- APK installation and device-app control
- Git commits and external GitHub reviews/merges/actions
- configurable developer jobs that accept shell steps

## Public service target
The public MCP should expose only per-user, server-enforced operations such as:
- mada_agent_status: status for the authenticated user's paired runtime, without local absolute paths
- mada_list_projects: aliases/names of that user's explicitly linked projects
- mada_run_diagnostics: a closed allowlist of non-destructive checks; no arbitrary command string
- mada_job_status: short status/log summaries for that user's own jobs, with secret redaction
- mada_connection_help: connection and revocation guidance

These names describe a target interface only. They are not implemented in the current 44-tool private server. Update the listing and review cases to match the deployed public server before submission.

## Required enforcement
- Validate OAuth access tokens on every request; do not trust an email or user ID passed as a tool argument.
- Resolve user and linked device from the validated token on the server, not from model-supplied path/device identifiers.
- Use per-user pairing secrets, rotate/revoke them, store hashes where possible, rate-limit requests, audit security events, and redact secrets.
- Never run arbitrary shell commands from the public MCP. Use fixed command profiles and parameter validation.
- Require explicit confirmation for destructive operations, and exclude arbitrary destructive operations from the first public release.
- Treat the current private Commander tunnel as a distinct environment.
