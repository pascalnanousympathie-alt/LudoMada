# LudoMada Autonomous Release Pipeline

The local orchestration follows Scheduler -> Supervisor -> Agent -> Tests -> Result.

## Components
- `scheduler.sh`: starts/periodically checks the pipeline.
- `supervisor.sh`: health/timeout supervision and automatic restart of a stopped pipeline.
- `pipeline.sh`: executes safe verification stages through the existing restricted agent.
- `agent.sh`: restricted project operations; sensitive files and arbitrary shell execution remain blocked.
- `pipeline.conf`: intervals, retry/timeout limits and stage configuration.

## Failure policy
A failed stage is recorded with its log. The supervisor restarts a stopped pipeline; it does not blindly edit production code or expose secrets. Root-cause correction remains an explicit coding-agent operation, followed by rebuild and retest.

## Secrets
`.env`, `.env.*`, private keys, tokens and passwords are not read or committed by this orchestration layer. Provider credentials belong in Railway/GitHub secret storage.

## Start
Run `./.ludomada-agent/scheduler.sh once` for one supervised run, or `./.ludomada-agent/scheduler.sh daemon` for periodic supervision.
