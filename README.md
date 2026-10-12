# Plugnostics-Hub 0.1.0 — experimental MVP

Independent Paper plugin for local diagnostics and optional upload to an **administrator-selected** GitHub repository. No centralized report repository is used. The GitHub source repository must contain only project source, never server logs.

## Install

1. Place `Plugnostics-Hub-0.1.0.jar` in `plugins/`, then restart Paper.
2. Local reports are written to `plugins/PlugnosticsHub/reports/` every 30 minutes (first report after ~1 minute).
3. To enable GitHub upload, create your own **private** report repository, set `github.enabled: true` and `github.repository: "OWNER/REPO"` in config.yml, and configure `PLUGNOSTICS_GITHUB_TOKEN` as an environment variable on the server host. Token requires Contents: Read and write permission **only** on that report repository. Never put tokens in config.yml or reports.
4. Restart Paper. GitHub upload uses HTTPS GitHub Contents API. The configured branch must exist.

## Scope and limitations

- Initial implementation reads newly appended lines from `logs/latest.log` (starting from plugin enable), groups lines by exact `[PluginName]` tags, and records a plugin inventory. Untagged lines go to `Unknown`.
- Heuristic error/warning classification; not a root-cause analyzer. Not all plugin logs contain recognizable tags. No TPS/MSPT collection, crash recovery, command access, web UI or player-name anonymization yet.
- Local JSON reports include log content. Automated token/password/IP/UUID redaction is **best effort, not a guarantee**. Review reports before enabling remote upload; prefer private repositories.
- Report files are retained locally; no automatic cleanup in this MVP. Each GitHub upload creates a timestamped file and commit; long-term batching/retention remain future work.
- This artifact was compiled against local API *signature stubs* because Paper API dependencies were not available in the build environment. Java compilation and JAR structure were checked, but runtime compatibility **has not been verified on Paper**. Test on a staging server first.

## Build from source

Use Java 21+ and Paper API for your server version as `compileOnly`; compile `src/main/java`, package class files together with `src/main/resources`. Do **not** include the API stubs in the distributed JAR.

## Logs and code are separate

The official repository contains source and builds only. Each administrator configures their own report repository.
