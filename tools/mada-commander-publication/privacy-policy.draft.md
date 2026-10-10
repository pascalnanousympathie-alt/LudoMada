# Mada Commander Privacy Policy — DRAFT, not yet published

**Do not publish until the operator verifies actual hosting, retention, subprocessors and contact details.**

## Scope and data processed
Mada Commander is intended to let each signed-in developer connect their own device/workspace and request approved diagnostics. The planned implementation stores the verified Google account subject, email and display name; hashes of web-session, MCP access/refresh, pairing and agent tokens; agent name and last check-in; project aliases/labels, branch and short commit hash; diagnostic job status and bounded sanitized results; and hashed rate-limit records.

The local agent stores its raw agent token and registered absolute project paths in `~/.config/mada-commander/agent.json` with owner-only file and directory permissions. Absolute paths are not uploaded in heartbeats. The server receives project alias/label, branch and short commit hash, and narrow results from fixed diagnostic profiles.

## Purpose and access
Data is used for authentication, authorization of a user's own linked agents, approved diagnostics, results, rate-limiting, security and support. The server must reject access across accounts. The public service does not expose arbitrary shell commands, unrestricted file access, browser sessions, APK installation or GitHub write actions.

## Retention in the current code draft
During service requests, expired Google/OAuth/session/pairing records and old rate-limit records are eligible for cleanup. Queued/running jobs older than one day are marked failed; finished jobs are eligible for deletion after 30 days. These behaviors must be verified in production, including database backups and infrastructure logs. Do not claim unrelated data is automatically deleted until verified.

## Providers and deletion
Before launch, list the actual hosting provider, Postgres provider, Google OAuth, logs, backups, subprocessors, retention periods and data deletion path. Agent revocation disables a linked agent. An account-deletion route is included in the local implementation; it must be tested against the deployed database and backups before this policy can promise complete deletion.

## Security
Protected MCP calls require short-lived OAuth tokens tied to the correct resource and scope. Agent, session, pairing and OAuth tokens are stored as hashes. Tools enforce user ownership server-side and return bounded/redacted results. Private Termux runtime keys, the private Commander token and private browser credentials must never be sent to the public client.

## Contact
Replace this section with a monitored privacy contact controlled by the verified publisher before publication.
