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
- mada_mcp_integrations: queue an inventory of the authenticated user's local, owner-managed MCP registry
- mada_mcp_tools: list only exact tools in the selected local MCP read-only allowlist
- mada_mcp_call: invoke one exact locally allowlisted tool; write-capable tools are opt-in and separately controlled

These names describe a target interface only. They are not implemented in the current 44-tool private server. Update the listing and review cases to match the deployed public server before submission.


## Optional third-party MCP integrations

Third-party MCP processes run only on the paired local agent, never as arbitrary code on the public Vercel service. The agent accepts only a server alias, a tool name, and bounded arguments from the public queue. The command, fixed process arguments, HTTPS endpoint, environment-variable mapping and allowed tool names live in the local owner-only config. Credentials remain in the local environment and are not sent to the public service.

- The integration registry is deny-by-default; all sample servers start disabled.
- With `writeEnabled=false`, only the explicit `readOnlyTools` names may be called. Write-capable names are hidden even if they are present in `allowedTools`.
- The agent must not accept an executable, shell string, endpoint URL, or credential value as a public tool argument.
- Remote server metadata, tool descriptions and results are untrusted data. Limit request/response sizes, redact credentials, and do not treat returned text as instructions.
- Remote integrations can still act with the local credential's privileges. Use least-privilege tokens and leave integrations disabled until the endpoint/auth method and tool names are verified.
- Browser automation through Playwright is separate from the existing Opera noVNC/CDP bridge; do not claim it controls the existing Opera profile unless a dedicated connection has been implemented and tested.

## Required enforcement
- Validate OAuth access tokens on every request; do not trust an email or user ID passed as a tool argument.
- Resolve user and linked device from the validated token on the server, not from model-supplied path/device identifiers.
- Use per-user pairing secrets, rotate/revoke them, store hashes where possible, rate-limit requests, audit security events, and redact secrets.
- Never run arbitrary shell commands from the public MCP. Use fixed command profiles and parameter validation.
- Require explicit confirmation for destructive operations, and exclude arbitrary destructive operations from the first public release.
- Treat the current private Commander tunnel as a distinct environment.
