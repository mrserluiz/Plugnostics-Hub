# Plugnostics-Hub 0.1.1 — experimental MVP

Independent Paper plugin for local diagnostics and optional upload to an **administrator-selected** GitHub repository. No centralized report repository is used. The GitHub source repository must contain only project source, never server logs.

## Install

1. Place `Plugnostics-Hub-0.1.1.jar` in `plugins/`, then restart Paper.
2. Local reports are written to `plugins/PlugnosticsHub/reports/` every 30 minutes (first report after ~1 minute).
3. To enable GitHub upload, create your own **private** report repository, set `github.enabled: true` and `github.repository: "OWNER/REPO"` in config.yml, and paste your token as a single line into `plugins/PlugnosticsHub/github-token.txt`, created empty at startup. Use the hosting file manager or SFTP. The file takes priority; `PLUGNOSTICS_GITHUB_TOKEN` remains an environment-variable fallback. Existing configurations also use this default filename without migration. Token requires Contents: Read and write permission **only** on that report repository. Never put tokens in config.yml or reports.
4. Restart Paper. GitHub upload uses HTTPS GitHub Contents API. Initialize your report repository with a README so the configured branch exists. Successful upload confirms authentication in the console. HTTP 401 indicates invalid credentials; 403 can indicate insufficient permissions or rate limits; 404 can indicate an inaccessible repository/branch.

## Scope and limitations

- Initial implementation reads newly appended lines from `logs/latest.log` (starting from plugin enable), groups lines by exact `[PluginName]` tags, and records a plugin inventory. Untagged lines go to `Unknown`.
- Heuristic error/warning classification; not a root-cause analyzer. Not all plugin logs contain recognizable tags. No TPS/MSPT collection, crash recovery, command access, web UI or player-name anonymization yet.
- Local JSON reports include log content. Automated token/password/IP/UUID redaction is **best effort, not a guarantee**. Review reports before enabling remote upload; prefer private repositories.
- Report files are retained locally; no automatic cleanup in this MVP. Each GitHub upload creates a timestamped file and commit; long-term batching/retention remain future work.
- GitHub Actions builds against the real Paper API. Runtime compatibility has not been verified on a live Paper server. Test on a staging server first.

## Build from source

Use Java 21+ and Paper API for your server version as `compileOnly`; compile `src/main/java`, package class files together with `src/main/resources`. Do **not** include the API stubs in the distributed JAR.

## Logs and code are separate

The official repository contains source and builds only. Each administrator configures their own report repository.

## Credential privacy

The token file is never included in reports. Its current value and recognizable GitHub token patterns are redacted from captured logs. Never upload the credential file to GitHub. Redaction of other data remains best effort.
